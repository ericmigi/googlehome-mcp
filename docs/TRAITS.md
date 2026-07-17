# Foyer trait control reference (live-verified 2026-07-16)

All control goes through `HomeControlService/UpdateTraits`. The request shape is always:

```
[[[ [deviceId,[agentId,partnerDeviceId]], [[ traitName, [[ fieldName, <wrapper> ]] ]] ]]]
```

and `GetTraits` (`[[["<id>"],…]]`) reads the same positional structure back.

## Value encoding (the wrapper `[i0,i1,i2,i3,…]`)

The value sits at a **type-specific index** (protobuf oneof slot):
- **int / float** → index 1: `[null, <num>]`
- **string / enum** → index 2: `[null, null, "<str>"]`
- **bool** → index 3: `[null, null, null, <0|1>]`

## Verified control payloads

| Capability | Trait | Field | Wrapper | Example device | Status |
|---|---|---|---|---|---|
| On/off | `onOff` | `onOff` | `[null,null,null,0\|1]` | any OnOff device | ✅ verified |
| Brightness | `brightness` | `brightness` | `[null,0..100]` | a color bulb | ✅ verified (42 echoed, restored) |
| Color temperature | `color` | `colorTemperature` | `[null,<kelvin>]` (range in `colorTemperatureRange`, e.g. 2700-6500) | a color bulb | ✅ verified (4000 echoed) |
| Volume | `volume` | `currentVolume` | `[null,0..100]` (`volumeMaxLevel`) | a speaker | ✅ verified |
| Mute | `volume` | `isMuted` | `[null,null,null,0\|1]` | speakers | ⚠️ structure-inferred |
| Lock | `lockUnlock` | `isLocked` | `[null,null,null,1]` | a smart lock | ✅ verified (locked; slow, ~10-30s to settle) |
| Unlock | `lockUnlock` | `isLocked` | `[null,null,null,0]` | a smart lock | ✅ **PIN-gated, verified**, a bare unlock returns `["challenge",[null,null,"pinNeeded"]]`; resend with a `pin` string field on the same trait: `["lockUnlock",[["isLocked",[null,null,null,0]],["pin",[null,null,"<PIN>"]]]]`. Verified live via the MCP. |

## Color (spectrum)

`ColorSetting` devices expose `supportedColors` (named presets w/ `displayHexValue` int) and, on full-color
bulbs, an HSV/`spectrumHsv` or `spectrumRgb` field. Only `colorTemperature` is verified so far; capture
`spectrumHsv`/`spectrumRgb` write shape from the web app before shipping full RGB.

## Thermostat (Nest), ✅ SOLVED (live-verified 2026-07-16)

`temperatureSetting` reads: `mode` (`[null,null,"heat"|"eco"|"off"]`), `thermostatTemperatureSetpointC`
(`[null,18.5]`), `thermostatTemperatureSetpointF` (`[null,65]`), `ambientAirTemperatureC/F`,
`availableModes` (heat/eco/off), and (in the params block) `temperatureUnit` (`"F"`).

**Write** (verified against a real web-app setpoint change + a live MCP round trip):

```
[["temperatureSetting",[["mode",[null,null,"heat"]],["thermostatTemperatureSetpoint",[null,66]]]]]
```

Two things the naive attempt got wrong:
1. The field is **`thermostatTemperatureSetpoint`** (no `C`/`F` suffix), carrying the value in the
   device's **display unit** (°F here → `66`, a whole number serialized as an int). The `…SetpointC`
   field is rejected even with a mode.
2. The **`mode` must be sent alongside** the setpoint. A bare setpoint → `[9,"Precondition check failed."]`.

The client reads the device's `temperatureUnit` + current `mode`, converts the Celsius target to the
display unit, and sends `mode` + `thermostatTemperatureSetpoint` together.

## Unlock PIN challenge, ✅ SOLVED (live-verified 2026-07-16)

A bare unlock on a locked, PIN-protected lock returns `deviceStatus.challenge = "pinNeeded"` (a
single-step challenge, no nonce). Answer it by **re-sending the same UpdateTraits with a `pin` string
field** appended to the `lockUnlock` trait:

```
[["lockUnlock",[["isLocked",[null,null,null,0]],["pin",[null,null,"<PIN>"]]]]]
```

The client (`setLocked`) does exactly this: bare unlock → on `pinNeeded`, if a PIN was supplied,
resend with the `pin` field; else throw `ChallengeRequiredException`. Verified end-to-end through the
MCP (lock → unlock with PIN → unlocked). The PIN is a secret: pass it per-call, never log/store it.

## Media playback, ✅ SOLVED (live-verified 2026-07-16)

Play/pause is NOT the `transportControl` trait, the web app drives it via **`mediaState.playbackState`**
(a string). Captured from the hub:

```
pause: [["mediaState",[["playbackState",[null,null,"paused"]]]]]
play:  [["mediaState",[["playbackState",[null,null,"playing"]]]]]
```

`media_control` maps `pause`→`paused`, `play`/`resume`→`playing`, `stop`→`stopped` (stop inferred).
Verified pause+play through the MCP on the hub. `next`/`previous` are not exposed by this field (they'd
be the `transportControl` trait, whose write shape the web tile doesn't surface) and are rejected.

## Automations / routines, ✅ SOLVED (live-verified 2026-07-16)

A separate foyer service, `AutomationService` (same base URL/auth as HomeControlService).

- **Enumerate**, `AutomationService/ListAutomations`, body `[<structureId>]`. Each record:
  `[0]`=id, `[2]`=flag (`1`=manually-runnable "household routine", `3`=schedule/condition-only),
  `[3]`=name, `[4]`=starter summary, `[5]`=action summary, `[10]`=base64 `WorkflowTriggerInput` blob.
- **Run**, `AutomationService/ExecuteAutomation`, body `[<structureId>, <automationId>, null, 2]`,
  response `[1]` on success. Verified via the MCP (ran a sample routine). Only flag-`1` automations can
  be started on demand. Running executes the automation's EXISTING actions, it never adds/deletes.

## Error shapes seen
- `[3,"<msg>"]`, INVALID_ARGUMENT (e.g. the api-key/project mismatch).
- `[9,"Precondition check failed."]`, FAILED_PRECONDITION (thermostat).
- `deviceStatus` may carry `["error",[null,null,"alreadyLocked"]]` / `["challenge",[null,null,"pinNeeded"]]`.
- Physical devices (locks) are **async**: the command returns fast but `GetTraits` state lags 10-30s.
