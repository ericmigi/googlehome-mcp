# googlehome-mcp

An MCP plugin for controlling Google Home devices: lights, plugs, switches, locks, thermostats,
speakers, and routines. It talks to Google's undocumented Home "foyer" API (the one
[home.google.com](https://home.google.com) uses), authenticating with a gpsoauth master token — so
it needs no Google Cloud project, Device Access, or partner API.

It runs as a **JavaScript plugin** on the CoreApp / Pebble Index MCP runtime
([coredevices/CoreApp#174](https://github.com/coredevices/CoreApp/pull/174)): a single esbuilt
`index.js`, sandboxed on-device (WebView on Android, JavaScriptCore on iOS), reaching Google only
through the host's allow-listed `fetch`.

## What it can do

Every tool below is verified live against real devices.

Read (all homes/devices, unrestricted):
- `list_homes`, `list_devices` (name, room, type, capabilities, online/on state), `get_device_state`

Control (any device; selectors are `name` and/or `room` and/or `type`):
- `turn_on` / `turn_off` — single device, a room, or a group ("all lights" = `type=light`)
- `set_brightness`, `set_color_temperature`
- `set_volume`, `set_muted`
- `lock`, `unlock` (PIN-gated: `unlock` takes a `pin`; `set_passcode` saves it)
- `set_thermostat` (temperature in C or F, and/or mode)
- `media_control` (play / pause / stop)
- `list_automations`, `run_automation` (start a household routine by name)

There is no add/create or remove/delete surface — control only.

## Build

```
cd js
npm install
npm run build     # -> js/dist/{index.js, manifest.json, settings.html}
npm test          # vitest
```

The `dist/` output is packaged into a `.pbw` (`appinfo.json` + `mcp/`) by the CoreApp tooling and
installed from the Pebble app's **MCP settings → Add → Plugin**.

## Sign-in

Auth happens on the plugin's **settings page** (`js/settings.html`): "Sign in to Google" opens the
real Google page — a host-pinned cookie capture, so the plugin never sees your password — captures
the `oauth_token`, and the plugin mints a gpsoauth master token from it. All secrets stay on the
phone in the plugin's namespaced store.

## Reference

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — the foyer API protocol (JSON + protobuf).
- [`docs/TRAITS.md`](docs/TRAITS.md) — device traits and the control mapping.
- `js/src/` — the plugin source (TypeScript): foyer client/codec, gpsoauth, the 16 tools.
