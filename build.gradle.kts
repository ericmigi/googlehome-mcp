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

    sourceSets {
        commonMain.dependencies {
            // Kept minimal so the wasmJs target actually compiles.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // NOTE: the MCP SDK is intentionally absent here — 0.8.3 has no wasmJs
            // artifact. commonMain codes against a thin internal tool abstraction
            // (server agent) and the real MCP server is wired in jvmMain.
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // MockEngine, for asserting request shape without real network calls.
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
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
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}
