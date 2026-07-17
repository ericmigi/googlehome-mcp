// Prelude prepended to the generated Kotlin/Wasm glue by :packageWasmBundle. See build.gradle.kts.
//
// The plugin runs in the host's sandboxed engine: a bare in-process JSContext (iOS) or a WebView
// (Android). Bare JSC is *only* ECMAScript — it has no DOM and no WHATWG APIs. Two things the
// generated glue and its Kotlin dependencies reach for are therefore missing, and they are NOT the
// same kind of problem:
//
//   * TextDecoder  — pure computation (bytes -> string). Ktor's wasmJs charset path calls
//                    `new TextDecoder(enc, {fatal})` + `.decode(buf)`; without it every
//                    `bodyAsText()` fails with a bare NullPointerException (the glue's
//                    `tryCreateTextDecoder` swallows the ReferenceError and returns null into a
//                    non-null Kotlin type). Shimmed HERE: it grants no capability, and it is a quirk
//                    of *this plugin's toolchain*, not something the host ABI should have to know.
//
//   * setTimeout / clearTimeout — NOT shimmed here, and deliberately so. A timer needs a real event
//                    loop, which is a host capability, not something a sandboxed script can conjure.
//                    kotlinx-coroutines' `Dispatchers.Default` requires it, so **the host must inject
//                    timers into the engine before evaluating this bundle**. libpebble3 already does
//                    exactly this for PKJS — see `JSTimeout.js` / `JSTimeout.kt` in
//                    `libpebble3/src/iosMain/.../js/`, which back setTimeout with a coroutine scope.
//
// Everything below is guarded on `typeof X === 'undefined'`, so on a WebView host (which has real
// implementations) this is inert.

(function (global) {
    'use strict';

    if (typeof global.TextDecoder !== 'undefined') return;

    var UTF8_NAMES = { 'utf-8': 1, 'utf8': 1, 'unicode-1-1-utf-8': 1 };
    var REPLACEMENT = 0xFFFD;

    /**
     * Minimal WHATWG-compatible UTF-8 TextDecoder: exactly the surface Ktor uses.
     *
     * Only UTF-8 is supported — that is all this plugin speaks (foyer is JSON/UTF-8, gpsoauth is
     * ASCII). Any other label throws, which the glue's `tryCreateTextDecoder` turns into the same
     * null it would produce on a browser that rejected the label. Failing there beats silently
     * mis-decoding a device name.
     */
    function TextDecoderShim(encoding, options) {
        var label = String(encoding === undefined ? 'utf-8' : encoding).toLowerCase().trim();
        if (UTF8_NAMES[label] !== 1) {
            throw new RangeError('TextDecoder shim: only UTF-8 is supported, got "' + encoding + '"');
        }
        this.encoding = 'utf-8';
        this.fatal = !!(options && options.fatal);
        this.ignoreBOM = !!(options && options.ignoreBOM);
    }

    TextDecoderShim.prototype.decode = function (input) {
        if (input === undefined || input === null) return '';

        var bytes;
        if (input instanceof Uint8Array) {
            bytes = input;
        } else if (ArrayBuffer.isView(input)) {
            bytes = new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
        } else if (input instanceof ArrayBuffer) {
            bytes = new Uint8Array(input);
        } else {
            throw new TypeError('TextDecoder shim: decode() expects a BufferSource');
        }

        var fatal = this.fatal;
        var out = [];
        var str = '';
        var i = 0;
        var n = bytes.length;

        // Skip a leading BOM, per the spec's default (ignoreBOM === false).
        if (!this.ignoreBOM && n >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) {
            i = 3;
        }

        function fail() {
            if (fatal) throw new TypeError('TextDecoder shim: invalid UTF-8 (fatal)');
            return REPLACEMENT;
        }

        function push(cp) {
            if (cp <= 0xFFFF) {
                out.push(cp);
            } else {
                var x = cp - 0x10000;
                out.push(0xD800 + (x >> 10), 0xDC00 + (x & 0x3FF));
            }
            // Flush in chunks: String.fromCharCode.apply blows the argument limit on big bodies,
            // and a homegraph response is comfortably big enough to hit it.
            if (out.length >= 4096) {
                str += String.fromCharCode.apply(String, out);
                out.length = 0;
            }
        }

        // The WHATWG "UTF-8 decoder" state machine, verbatim
        // (https://encoding.spec.whatwg.org/#utf-8-decoder).
        //
        // Hand-rolling the bit-twiddling instead looks simpler but gets error *counts* wrong: the
        // spec emits one U+FFFD per maximal subpart, so `C0 80` is TWO replacements, not one — on a
        // bad continuation byte the machine resets and REPROCESSES that byte from the start state
        // (the `continue` without advancing `i` below). Differential-fuzzing this against a real
        // TextDecoder is what surfaced that; don't "simplify" it back.
        //
        // Overlongs, surrogates, and >U+10FFFF need no explicit checks: they fall out of the 0xC2
        // floor on 2-byte leads and the per-lead `lower`/`upper` continuation bounds.
        var codePoint = 0, bytesSeen = 0, bytesNeeded = 0, lower = 0x80, upper = 0xBF;

        for (;;) {
            if (i >= n) {
                if (bytesNeeded !== 0) push(fail());   // truncated sequence at end of stream
                break;
            }
            var b = bytes[i];

            if (bytesNeeded === 0) {
                i += 1;
                if (b <= 0x7F) push(b);
                else if (b >= 0xC2 && b <= 0xDF) { bytesNeeded = 1; codePoint = b & 0x1F; }
                else if (b >= 0xE0 && b <= 0xEF) {
                    if (b === 0xE0) lower = 0xA0;      // exclude overlong 3-byte
                    if (b === 0xED) upper = 0x9F;      // exclude surrogates
                    bytesNeeded = 2; codePoint = b & 0x0F;
                } else if (b >= 0xF0 && b <= 0xF4) {
                    if (b === 0xF0) lower = 0x90;      // exclude overlong 4-byte
                    if (b === 0xF4) upper = 0x8F;      // exclude > U+10FFFF
                    bytesNeeded = 3; codePoint = b & 0x07;
                } else push(fail());                   // 0x80-0xC1, 0xF5-0xFF
                continue;
            }

            if (b < lower || b > upper) {
                codePoint = 0; bytesNeeded = 0; bytesSeen = 0; lower = 0x80; upper = 0xBF;
                push(fail());                          // NB: `i` not advanced — reprocess `b`
                continue;
            }

            lower = 0x80; upper = 0xBF;
            i += 1;
            codePoint = (codePoint << 6) | (b & 0x3F);
            bytesSeen += 1;
            if (bytesSeen !== bytesNeeded) continue;

            var done = codePoint;
            codePoint = 0; bytesNeeded = 0; bytesSeen = 0;
            push(done);
        }

        if (out.length > 0) str += String.fromCharCode.apply(String, out);
        return str;
    };

    global.TextDecoder = TextDecoderShim;
})(globalThis);
