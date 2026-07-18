import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "googlehome.mcp"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)

    jvm {
        // Produces a `runJvm` task (and a runnable distribution) for the ktor webserver entrypoint.
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("googlehome.mcp.MainKt")
            }
            // `runBootstrapJvm` task: obtain a master token (Chrome sign-in / oauth_token exchange).
            executable(compilationName = "main", disambiguationSuffix = "bootstrap") {
                mainClass.set("googlehome.mcp.bootstrap.BootstrapMainKt")
            }
            // `runJvmVerify` task: live smoke test of the master-token -> foyer path.
            executable(compilationName = "main", disambiguationSuffix = "verify") {
                mainClass.set("googlehome.mcp.verify.VerifyMainKt")
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    // JS-free reactor module for the `chasm` host. Ktor has no wasmWasi artifact, so this target sees
    // ONLY the Ktor-free `commonMain` core; its transport is the `host_http_fetch` wasm import.
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Ktor-FREE pure core only — both wasmJs (via ktorMain) and wasmWasi build against this.
                // (These two publish wasmWasi artifacts; Ktor does not, which is the whole reason for
                // the ktorMain split below.)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                // NOTE: the MCP SDK is intentionally absent — 0.8.3 has no wasmJs/wasmWasi artifact.
                // commonMain codes against a thin internal tool abstraction; the real MCP server is jvm.
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // Intermediate set holding every Ktor impl. jvmMain + wasmJsMain depend on it; wasmWasiMain does
        // NOT, so wasmWasi never sees Ktor.
        val ktorMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
        // The Ktor MockEngine tests (foyer client, gpsoauth, foyer auth) live here — not commonTest —
        // so they never compile for the Ktor-free wasmWasi target.
        val ktorTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.ktor.client.mock)
            }
        }

        // Default hierarchy template is disabled (see gradle.properties), so every target's main/test
        // set explicitly declares its parent here.
        val jvmMain by getting {
            dependsOn(ktorMain)
            dependencies {
                implementation(libs.ktor.client.okhttp)
                // ktor webserver (SSE transport for MCP over HTTP)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.sse)
                // MCP SDK — JVM-only (no wasmJs artifact in 0.8.3).
                implementation(libs.mcp.kotlin.sdk.core)
                implementation(libs.mcp.kotlin.sdk.server)
                // Master-token bootstrap: drives a real Chrome to scrape the EmbeddedSetup oauth_token.
                implementation(libs.playwright)
            }
        }
        val jvmTest by getting { dependsOn(ktorTest) }

        val wasmJsMain by getting {
            dependsOn(ktorMain)
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        val wasmJsTest by getting { dependsOn(ktorTest) }

        // wasmWasiMain depends on commonMain ONLY (never ktorMain) — Ktor-free by construction.
        val wasmWasiMain by getting { dependsOn(commonMain) }
        val wasmWasiTest by getting { dependsOn(commonTest) }
    }
}

// -------------------------------------------------------------------------------------------------
// CoreApp wasm-MCP plugin bundle
// -------------------------------------------------------------------------------------------------

/**
 * Assembles `build/bundle/{manifest.json, module.wasm, glue.js}` — the bundle format CoreApp's
 * wasm-MCP runtime loads (see its `docs/wasm-mcp/ARCHITECTURE.md`, decision D3).
 *
 * ## Why this is not a plain copy
 *
 * Kotlin/Wasm emits `<name>.wasm` plus two ES modules: `<name>.uninstantiated.mjs` (the real glue —
 * `js_code` imports + a loader) and `<name>.mjs` (a thin entry that awaits `instantiate()` and
 * re-exports). That loader sniffs its environment and loads the wasm via Node `fs`, `Deno`, or
 * browser `fetch(import.meta.url)`.
 *
 * The plugin host is **none of those**. Per decision D1 the module runs in a bare in-process
 * `JSContext` (iOS) or a `WebView` (Android), which by design has no `fetch`, no fs, and no ambient
 * capability at all — that is the sandbox. It also evaluates a classic script, not an ES module, so
 * the `import.meta` tokens are a *parse* error even on branches that never run.
 *
 * So the task rewrites the generated glue into a self-contained classic script that takes the wasm
 * bytes from the host instead of fetching them:
 *
 * 1. `export async function instantiate` -> a local function (no ESM export).
 * 2. The environment sniff -> pinned to the "standalone JS VM" branch, which is the only one that
 *    builds the module **synchronously from bytes** (`new WebAssembly.Module(buf)`) with no I/O.
 * 3. That branch's `read(wasmFilePath, 'binary')` -> the bytes the host hands us.
 * 4. `import.meta` -> an inert stand-in, so the dead Node/Deno/browser branches merely parse.
 *
 * Each rewrite is asserted: if a Kotlin upgrade changes the generated glue, this task **fails loudly**
 * rather than silently shipping a bundle that cannot instantiate on a device.
 *
 * The host's contract with the result is the plugin ABI: evaluate `glue.js`, then call
 * `globalThis.mcpPluginInit(<module.wasm bytes>)`. That installs `globalThis.mcpPlugin`.
 */
val packageWasmBundle by tasks.registering {
    group = "build"
    description = "Assemble the CoreApp wasm-MCP plugin bundle (manifest.json + module.wasm + glue.js) in build/bundle."

    val optimizeTask = tasks.named("compileProductionExecutableKotlinWasmJsOptimize")
    dependsOn(optimizeTask)

    val emittedDir = layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/optimized")
    val manifestFile = layout.projectDirectory.file("bundle/manifest.json")
    val preludeFile = layout.projectDirectory.file("bundle/prelude.js")
    val outDir = layout.buildDirectory.dir("bundle")

    inputs.dir(emittedDir)
    inputs.file(manifestFile)
    inputs.file(preludeFile)
    outputs.dir(outDir)

    doLast {
        val src = emittedDir.get().asFile
        val wasm = src.listFiles()?.singleOrNull { it.name.endsWith(".wasm") }
            ?: error("Expected exactly one .wasm in $src, found: ${src.list()?.joinToString()}")
        val glueSrc = src.listFiles()?.singleOrNull { it.name.endsWith(".uninstantiated.mjs") }
            ?: error("Expected exactly one .uninstantiated.mjs in $src, found: ${src.list()?.joinToString()}")

        val out = outDir.get().asFile
        out.mkdirs()

        wasm.copyTo(out.resolve("module.wasm"), overwrite = true)
        manifestFile.asFile.copyTo(out.resolve("manifest.json"), overwrite = true)

        var glue = glueSrc.readText()

        /** Applies one required rewrite, failing the build if the generated glue no longer matches. */
        fun rewrite(what: String, find: Regex, replaceWith: String) {
            val hits = find.findAll(glue).count()
            if (hits == 0) {
                error(
                    "packageWasmBundle: could not apply rewrite '$what' — pattern <${find.pattern}> not found in " +
                        "${glueSrc.name}. The Kotlin/Wasm glue changed shape; update this task (build.gradle.kts) " +
                        "before shipping a bundle.",
                )
            }
            glue = find.replace(glue, replaceWith)
        }

        rewrite(
            "un-export instantiate",
            Regex("""export\s+async\s+function\s+instantiate\("""),
            "async function instantiate(",
        )
        rewrite(
            "pin the env sniff to the bytes-in path",
            Regex(
                """const isNodeJs = .*?\n\s*const isDeno = .*?\n\s*const isStandaloneJsVM =[\s\S]*?\n\s*const isBrowser = .*?;""",
            ),
            "const isNodeJs = false, isDeno = false, isStandaloneJsVM = true, isBrowser = false;",
        )
        rewrite(
            "take wasm bytes from the host",
            Regex("""const wasmBuffer = read\(wasmFilePath, 'binary'\);"""),
            "const wasmBuffer = globalThis.__googlehomeWasmBytes;",
        )
        rewrite(
            "neutralise import.meta for classic-script parsing",
            Regex("""import\.meta"""),
            "globalThis.__googlehomeImportMeta",
        )

        val prelude = """
            |// GENERATED — do not edit. Assembled by :packageWasmBundle from ${glueSrc.name}.
            |// Bare JSC / WebView sandbox: no fetch, no fs, no ES modules. The host evaluates this
            |// script, then calls the plugin ABI's entry point:
            |//
            |//   globalThis.mcpPluginInit(wasmBytes) -> Promise, resolves once globalThis.mcpPlugin
            |//                                          is live. See CoreApp's
            |//                                          docs/wasm-mcp/ARCHITECTURE.md "Plugin ABI".
            |//
            |// REQUIRED OF THE HOST: setTimeout/clearTimeout must already exist in the engine —
            |// kotlinx-coroutines' Dispatchers.Default needs them and a sandbox cannot invent a
            |// timer. See bundle/prelude.js, and libpebble3's JSTimeout.kt for the pattern.
            |
        """.trimMargin() + preludeFile.asFile.readText() + """
            |
            |(function (global) {
            |'use strict';
            |global.__googlehomeImportMeta = { url: '', resolve: function (p) { return p; } };
            |
        """.trimMargin()

        val epilogue = """
            |
            |/**
            | * The plugin ABI entry point. The host evaluates this file, then calls this with the
            | * bundle's module.wasm; we instantiate it and install globalThis.mcpPlugin.
            | *
            | * @param wasmBytes {ArrayBuffer|Uint8Array} the bundle's module.wasm.
            | * @returns {Promise<void>} resolves once globalThis.mcpPlugin is live.
            | */
            |global.mcpPluginInit = async function (wasmBytes) {
            |    if (!wasmBytes) throw new Error('mcpPluginInit: module.wasm bytes are required.');
            |    global.__googlehomeWasmBytes = wasmBytes;
            |    // runInitializer=true runs Kotlin's main(), which installs globalThis.mcpPlugin.
            |    await instantiate({}, true);
            |    delete global.__googlehomeWasmBytes;
            |    if (!global.mcpPlugin) throw new Error('mcpPluginInit: mcpPlugin was not installed.');
            |};
            |})(globalThis);
            |
        """.trimMargin()

        out.resolve("glue.js").writeText(prelude + glue + epilogue)

        logger.lifecycle("wasm-MCP bundle -> ${out.absolutePath}")
        out.listFiles()?.sortedBy { it.name }?.forEach {
            logger.lifecycle("  ${it.name} (${it.length()} bytes)")
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Chasm wasm-MCP plugin bundle (JS-free)
// -------------------------------------------------------------------------------------------------

/**
 * Assembles `build/chasm-bundle/{manifest.json, module.wasm}` — the bundle the `chasm` host loads.
 *
 * Unlike [packageWasmBundle], there is **no glue.js**: chasm instantiates the `wasmWasi` reactor
 * module directly and binds the `env::host_*` imports to Kotlin lambdas (see scratchpad/chasm-abi.md),
 * so the only artifacts are the optimized `module.wasm` and the manifest. The `entry` field (which
 * points at glue.js for the JS runtime) is stripped, since chasm has no JS entry point.
 */
val packageChasmBundle by tasks.registering {
    group = "build"
    description = "Assemble the chasm wasm-MCP plugin bundle (manifest.json + module.wasm) in build/chasm-bundle."

    val optimizeTask = tasks.named("compileProductionExecutableKotlinWasmWasiOptimize")
    dependsOn(optimizeTask)

    val emittedDir = layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/optimized")
    val manifestFile = layout.projectDirectory.file("bundle/manifest.json")
    val outDir = layout.buildDirectory.dir("chasm-bundle")

    inputs.dir(emittedDir)
    inputs.file(manifestFile)
    outputs.dir(outDir)

    doLast {
        val src = emittedDir.get().asFile
        val wasm = src.listFiles()?.singleOrNull { it.name.endsWith(".wasm") }
            ?: error("Expected exactly one .wasm in $src, found: ${src.list()?.joinToString()}")

        val out = outDir.get().asFile
        out.mkdirs()

        wasm.copyTo(out.resolve("module.wasm"), overwrite = true)

        // Same manifest as the JS bundle, minus the `entry` (glue.js) line — chasm has no JS entry.
        val manifest = manifestFile.asFile.readText()
            .replace(Regex(""" *"entry"\s*:\s*"[^"]*",?\r?\n"""), "")
        out.resolve("manifest.json").writeText(manifest)

        logger.lifecycle("chasm wasm-MCP bundle -> ${out.absolutePath}")
        out.listFiles()?.sortedBy { it.name }?.forEach {
            logger.lifecycle("  ${it.name} (${it.length()} bytes)")
        }
    }
}
