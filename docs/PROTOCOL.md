# Google Home Foyer API, reverse-engineered protocol

Captured live from `home.google.com` (Google Home for web) on 2026-07-15 against a test Google
account. This is the reference for the MCP server. (Raw captures live in `captures/`, which is
gitignored, they contain real personal data and are not committed.)

## 1. Transport

- **Base**: `https://googlehomefoyer-pa.clients6.google.com/$rpc/google.internal.home.foyer.v1.<Service>/<Method>`
- **Method**: `POST`
- **Encoding**: `Content-Type: application/json+protobuf`, request and response bodies are
  **positional JSON arrays** (protobuf field numbers → array index, 1-based with `null` holes).
  This is gRPC-web-text style ("Connect"/`$rpc`), NOT normal JSON objects.
- `X-User-Agent: grpc-web-javascript/0.1`

## 2. Auth (the bridge problem)

The **web app** authorizes each call with cookie-derived headers:

```
Authorization: SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + "https://home.google.com")> \
               SAPISID1PHASH <...__Secure-1PAPISID...> SAPISID3PHASH <...__Secure-3PAPISID...>
X-Goog-Api-Key: AIzaSyCMqap8NH88PrhvoBwY1W8ChRUJRjIOJXM   # public web key
X-Goog-AuthUser: 0
X-Server-Token: <rotating server token>                  # rotates; may be omittable
x-foyer-client-environment: <base64 client env + client UUID>  # b64 app info + a client UUID
```

**The MCP server does NOT have cookies, it has a master token.** So it must mint an OAuth
**Bearer** token from the master token (gpsoauth `getAuthToken`) and send
`Authorization: Bearer <token>` + `X-Goog-Api-Key` instead of SAPISIDHASH. The Android Google
Home app authorizes foyer-pa exactly this way.

### gpsoauth 

The gpsoauth flow: Endpoint
`https://android.clients.google.com/auth`, newline `Key=Value` responses, `User-Agent: GoogleAuth/1.4`.

- **Master token** starts with `aas_et/`. Obtained once by exchanging an `oauth_token`
  (`service=ac2dm`). The MCP server receives this master token as **input**, it does not do the
  oauth_token exchange.
- **Per-service auth token** (`getAuthToken`): POST `EncryptedPasswd=<masterToken>` (verbatim, not
  encrypted) + `service=oauth2:<scopes>` + `app=<pkg>` + `client_sig=<sig>` → response `Auth=<bearer>`.

**VERIFIED live (2026-07-16)**, the working `app` / `client_sig` / `scope` triple, confirmed by a
real `GetTraits` + `UpdateTraits` round trip on the test light:
- `app = com.google.android.apps.chromecast.app`
- `client_sig = 24bb24c05e47e0aefa68a58a766179d9b613a600`
- `scope = oauth2:https://www.googleapis.com/auth/homegraph` (the first candidate; works).

**Critical: with a Bearer, send NO `X-Goog-Api-Key`.** The web app pairs its api key with the
cookie SAPISIDHASH; our Bearer is minted for the Android Home app's Google project (`518630051060`),
so sending the *web* api key makes foyer reject the call with HTTP 400 *"The API Key and the
authentication credential are from different projects"* (`CONSUMER_INVALID`). Drop the api key
entirely, the Bearer alone identifies the project. Verified working header set for the MCP server:
`Authorization: Bearer <token>` + `Content-Type: application/json+protobuf` +
`X-User-Agent: grpc-web-javascript/0.1`.

Verification must use the **read-only** `GetTraits` first. Only after auth is proven do control calls run.

> **Verified live (2026-07-15):** a `GetTraits` call for the test light succeeds with ONLY
> `Authorization` + `X-Goog-Api-Key` + `Content-Type: application/json+protobuf` +
> `X-User-Agent: grpc-web-javascript/0.1`. `X-Server-Token` and `x-foyer-client-environment` are
> **optional** and can be omitted by the MCP server. Response was stable and matched the sample.

## 3. Services & methods (captured)

| Service / Method | Purpose | Req body (json+protobuf) |
|---|---|---|
| `StructuresService/GetHomeGraph` | Enumerate homes/structures/rooms/devices | `[]` |
| `HomeControlService/GetTraits` | Read device state (online, onOff, brightness…) | `[[["<id>"],["<id2>"],…]]` |
| `HomeControlService/UpdateTraits` | **Control** a device | see §5 |
| `EntitlementService/ListEntitlementRecordsForStructure` | Structure entitlements | `["<structureId>"]` |
| `UsersService/GetUserPreferences` | User prefs | `[]` |
| `UsersService/GetNestAccountLinkState` | Nest link state | `[]` |

Live IDs for this account:
- **structure id**: `11111111-1111-4111-8111-111111111111`
- **a device id**: `22222222-2222-4222-8222-222222222222`
  agent `acme-partner`, partner device id `AA11BB22CC33DD44EE55`

## 4. Enumeration, `GetHomeGraph` (read-only)

Full 102 KB sample: `captures/GetHomeGraph.sample.json`. Top level is a 14-slot positional array.
- `[1]` (≈90 KB) holds device/room data as 4 parallel lists of 23:
  - one list = **rooms**: `[roomId, null, roomName, [ROOM_TYPE], [[[deviceId,[agentId,partnerId]]],…]]`
    (room→device membership; `ROOM_TYPE` ∈ KITCHEN, BEDROOM, BATHROOM, OTHER, …)
  - another = **full device records**: `[[deviceId,[agentId,partnerId]], null, null, "Name", null,
    "action.devices.types.LIGHT", ["action.devices.traits.OnOff","…Brightness","…ColorSetting"],
    …manufacturer/model…, [["owner@example.com"]], …]`
- `[6]` (≈7 KB) and others are enum/label dictionaries (device-type and room-type name tables).

The test account has ~71 unique device UUIDs spread across a dozen rooms (Outside among them). Parse
against the saved sample; the protocol agent should also diff a fresh live capture to confirm indices.

## 5. Control, `UpdateTraits` (captured live)

Turning the test light **off** (`22222222…`):

```
POST …/HomeControlService/UpdateTraits
[[[["22222222-2222-4222-8222-222222222222",["acme-partner","AA11BB22CC33DD44EE55"]],
   [["onOff",[["onOff",[null,null,null,0]]]]]]]]
```

Shape: `[[[ [deviceId,[agentId,partnerId]], [[traitName,[[fieldName,[null,null,null,<value>]]]]] ]]]`.
- `onOff` value: `0` = off, `1` = on. The `[null,null,null,<v>]` is a protobuf-json scalar wrapper
  (bool value lands at field index 4).
- Response echoes device id + `partnerDeviceId` + `deviceStatus` + resulting `onOff`.

Read-back confirmed with `GetTraits` `[[["22222222-…"]]]` →
`[[[["22222222-…"],[["deviceStatus",[["online",[null,null,null,1]],…]],["onOff",[["onOff",[null,null,null,1]]],…]]]]]`.

### Brightness / color (from device traits, to confirm live)
Devices expose `action.devices.traits.Brightness` and `…ColorSetting`. The `UpdateTraits` trait
block for brightness is expected to mirror onOff shape with a `brightness` field carrying an int
0-100. We should confirm the exact field index by a single live captured toggle **only on
the test light** (never other devices).

## 6. Safety model

Enumeration/read is unrestricted (all homes, rooms, devices). Control is general (any device), but
strictly *control of existing devices*: there is **no add/create or remove/delete** tool and the
server never calls a create/delete RPC. Only `GetHomeGraph` / `GetTraits` / `UpdateTraits` /
`AutomationService.{ListAutomations,ExecuteAutomation}` are used.
