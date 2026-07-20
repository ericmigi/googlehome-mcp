// Builds and parses the foyer-pa `application/json+protobuf` wire format.
//
// The service does NOT use JSON objects — every request/response body is a *positional* JSON array
// where protobuf field numbers map to array indices (1-based, with `null` holes for absent fields).
// Ported from the Kotlin `FoyerCodec` (src/commonMain/.../foyer/FoyerCodec.kt); the field indices
// were derived from live captures.
//
// Value encoding (the scalar wrapper `[i0,i1,i2,i3,…]`): a value sits at a type-specific index:
//   - int / float  -> index 1: [null, <num>]
//   - string / enum-> index 2: [null, null, "<str>"]
//   - bool         -> index 3: [null, null, null, <0|1>]

export type JsonArr = unknown[];

export interface Home {
  id: string;
  name: string;
}

export interface Room {
  id: string;
  name: string;
  type: string;
  deviceIds: string[];
}

export interface Automation {
  id: string;
  name: string;
  manuallyRunnable: boolean;
  starters: string | null;
  actions: string | null;
  triggerInput: string | null;
}

export type Capability =
  | 'on_off'
  | 'brightness'
  | 'color_temperature'
  | 'color'
  | 'volume'
  | 'lock'
  | 'thermostat'
  | 'media_transport';

export interface Device {
  id: string;
  name: string;
  type: string;
  assignedType: string | null;
  traits: string[];
  roomName: string | null;
  agentId: string | null;
  partnerDeviceId: string | null;
  capabilities: Set<Capability>;
}

export interface DeviceState {
  id: string;
  online: boolean;
  onOff: boolean | null;
  brightnessPct: number | null;
  colorTemperatureK: number | null;
  volumePct: number | null;
  muted: boolean | null;
  locked: boolean | null;
  jammed: boolean | null;
  thermostatMode: string | null;
  setpointC: number | null;
  setpointF: number | null;
  ambientC: number | null;
  temperatureUnit: string | null;
}

export interface HomeGraph {
  homes: Home[];
  devices: Device[];
  rooms: Room[];
}

/** The type used for matching: user-assigned type when set, else hardware type. */
export function effectiveType(d: Device): string {
  return d.assignedType && d.assignedType.trim() !== '' ? d.assignedType : d.type;
}

// -------------------------------------------------------------------------------------------------
// Null-safe positional-array accessors (out-of-range / wrong-typed slot -> null)
// -------------------------------------------------------------------------------------------------

function arr(x: unknown): JsonArr | null {
  return Array.isArray(x) ? (x as JsonArr) : null;
}
function at(a: JsonArr | null, i: number): unknown {
  return a != null && i >= 0 && i < a.length ? a[i] : null;
}
function str(x: unknown): string | null {
  return typeof x === 'string' ? x : null;
}
function boolAt(a: JsonArr | null, i: number): boolean {
  const e = at(a, i);
  if (typeof e === 'boolean') return e;
  return e === 1;
}
function numAt(a: JsonArr | null, i: number): number | null {
  const e = at(a, i);
  return typeof e === 'number' ? e : null;
}
function strAt(a: JsonArr | null, i: number): string | null {
  return str(at(a, i));
}
function arrAt(a: JsonArr | null, i: number): JsonArr | null {
  return arr(at(a, i));
}

// -------------------------------------------------------------------------------------------------
// Scalar wrappers. In JS there is no int/float distinction, so numberWrapper is just [null, n].
// -------------------------------------------------------------------------------------------------

function intWrapper(n: number): JsonArr {
  return [null, n];
}
function numberWrapper(n: number): JsonArr {
  return [null, n];
}
function stringWrapper(s: string): JsonArr {
  return [null, null, s];
}
function boolWrapper(b: boolean): JsonArr {
  return [null, null, null, b ? 1 : 0];
}

/** Flattens a trait body `[[fieldName,<wrapper>],…]` into a `fieldName -> wrapper` map. */
function fieldWrappers(body: JsonArr | null): Map<string, JsonArr | null> {
  const out = new Map<string, JsonArr | null>();
  if (body == null) return out;
  for (const fieldEl of body) {
    const field = arr(fieldEl);
    const name = strAt(field, 0);
    if (name == null) continue;
    out.set(name, arrAt(field, 1));
  }
  return out;
}

// -------------------------------------------------------------------------------------------------
// GetTraits
// -------------------------------------------------------------------------------------------------

/** Request body for `HomeControlService/GetTraits`: `[[["id1"],["id2"],…]]`. */
export function buildGetTraitsRequest(ids: string[]): JsonArr {
  return [ids.map((id) => [id])];
}

export function parseGetTraits(json: unknown): DeviceState[] {
  const results = arrAt(arr(json), 0);
  if (results == null) return [];
  const out: DeviceState[] = [];
  for (const result of results) {
    const r = arr(result);
    const id = strAt(arrAt(r, 0), 0);
    if (id == null) continue;
    const traits = arrAt(r, 1);

    const s: DeviceState = {
      id,
      online: false,
      onOff: null,
      brightnessPct: null,
      colorTemperatureK: null,
      volumePct: null,
      muted: null,
      locked: null,
      jammed: null,
      thermostatMode: null,
      setpointC: null,
      setpointF: null,
      ambientC: null,
      temperatureUnit: null,
    };

    if (traits != null) {
      for (const entry of traits) {
        const e = arr(entry);
        const fields = fieldWrappers(arrAt(e, 1));
        switch (strAt(e, 0)) {
          case 'deviceStatus':
            if (fields.has('online')) s.online = boolAt(fields.get('online') ?? null, 3);
            break;
          case 'onOff':
            if (fields.has('onOff')) s.onOff = boolAt(fields.get('onOff') ?? null, 3);
            break;
          case 'brightness':
            s.brightnessPct = numAt(fields.get('brightness') ?? null, 1);
            break;
          case 'color':
            s.colorTemperatureK = numAt(fields.get('colorTemperature') ?? null, 1);
            break;
          case 'volume':
            s.volumePct = numAt(fields.get('currentVolume') ?? null, 1);
            if (fields.has('isMuted')) s.muted = boolAt(fields.get('isMuted') ?? null, 3);
            break;
          case 'lockUnlock':
            if (fields.has('isLocked')) s.locked = boolAt(fields.get('isLocked') ?? null, 3);
            if (fields.has('isJammed')) s.jammed = boolAt(fields.get('isJammed') ?? null, 3);
            break;
          case 'temperatureSetting':
            s.thermostatMode = strAt(fields.get('mode') ?? null, 2);
            s.setpointC = numAt(fields.get('thermostatTemperatureSetpointC') ?? null, 1);
            s.setpointF = numAt(fields.get('thermostatTemperatureSetpointF') ?? null, 1);
            s.ambientC = numAt(fields.get('ambientAirTemperatureC') ?? null, 1);
            // temperatureUnit lives in the trait's params block (index 2), one array deeper.
            s.temperatureUnit = strAt(
              fieldWrappers(arrAt(arrAt(e, 2), 0)).get('temperatureUnit') ?? null,
              2,
            );
            break;
        }
      }
    }
    out.push(s);
  }
  return out;
}

/** Scans a response for a `deviceStatus.challenge` token (e.g. `"pinNeeded"`), or null. */
export function parseChallenge(json: unknown): string | null {
  const results = arrAt(arr(json), 0);
  if (results == null) return null;
  for (const result of results) {
    const traits = arrAt(arr(result), 1);
    if (traits == null) continue;
    for (const entry of traits) {
      const e = arr(entry);
      if (strAt(e, 0) === 'deviceStatus') {
        const challenge = fieldWrappers(arrAt(e, 1)).get('challenge') ?? null;
        const v = strAt(challenge, 2);
        if (v != null) return v;
      }
    }
  }
  return null;
}

// -------------------------------------------------------------------------------------------------
// UpdateTraits (control) — generic builder + typed helpers
// -------------------------------------------------------------------------------------------------

export function buildUpdateFields(
  deviceId: string,
  agentId: string,
  partnerDeviceId: string,
  traitName: string,
  fields: [string, JsonArr][],
): JsonArr {
  const fieldArr = fields.map(([name, wrapper]) => [name, wrapper]);
  const trait = [traitName, fieldArr];
  const deviceKey = [deviceId, [agentId, partnerDeviceId]];
  const command = [deviceKey, [trait]];
  return [[command]];
}

export function buildUpdate(
  deviceId: string,
  agentId: string,
  partnerDeviceId: string,
  traitName: string,
  fieldName: string,
  wrapper: JsonArr,
): JsonArr {
  return buildUpdateFields(deviceId, agentId, partnerDeviceId, traitName, [[fieldName, wrapper]]);
}

export function buildOnOff(deviceId: string, agentId: string, partnerDeviceId: string, on: boolean): JsonArr {
  return buildUpdate(deviceId, agentId, partnerDeviceId, 'onOff', 'onOff', boolWrapper(on));
}
export function buildBrightness(deviceId: string, agentId: string, partnerDeviceId: string, pct: number): JsonArr {
  return buildUpdate(deviceId, agentId, partnerDeviceId, 'brightness', 'brightness', intWrapper(pct));
}
export function buildColorTemperature(
  deviceId: string,
  agentId: string,
  partnerDeviceId: string,
  kelvin: number,
): JsonArr {
  return buildUpdate(deviceId, agentId, partnerDeviceId, 'color', 'colorTemperature', intWrapper(kelvin));
}
export function buildVolume(deviceId: string, agentId: string, partnerDeviceId: string, pct: number): JsonArr {
  return buildUpdate(deviceId, agentId, partnerDeviceId, 'volume', 'currentVolume', intWrapper(pct));
}
export function buildMuted(deviceId: string, agentId: string, partnerDeviceId: string, muted: boolean): JsonArr {
  return buildUpdate(deviceId, agentId, partnerDeviceId, 'volume', 'isMuted', boolWrapper(muted));
}
export function buildLock(deviceId: string, agentId: string, partnerDeviceId: string, locked: boolean): JsonArr {
  return buildUpdate(deviceId, agentId, partnerDeviceId, 'lockUnlock', 'isLocked', boolWrapper(locked));
}

/** PIN-gated unlock: `["lockUnlock",[["isLocked",[null,null,null,0]],["pin",[null,null,"<pin>"]]]]`. */
export function buildLockWithPin(
  deviceId: string,
  agentId: string,
  partnerDeviceId: string,
  locked: boolean,
  pin: string,
): JsonArr {
  return buildUpdateFields(deviceId, agentId, partnerDeviceId, 'lockUnlock', [
    ['isLocked', boolWrapper(locked)],
    ['pin', stringWrapper(pin)],
  ]);
}

/** Thermostat: field `thermostatTemperatureSetpoint` in the device DISPLAY unit, `mode` sent alongside. */
export function buildThermostatSetpoint(
  deviceId: string,
  agentId: string,
  partnerDeviceId: string,
  setpointDisplayUnit: number | null,
  mode: string | null,
): JsonArr {
  const fields: [string, JsonArr][] = [];
  if (mode != null) fields.push(['mode', stringWrapper(mode)]);
  if (setpointDisplayUnit != null) {
    fields.push(['thermostatTemperatureSetpoint', numberWrapper(setpointDisplayUnit)]);
  }
  return buildUpdateFields(deviceId, agentId, partnerDeviceId, 'temperatureSetting', fields);
}

export const MEDIA_COMMANDS = ['play', 'pause', 'stop'];

/** Media play/pause/stop via `mediaState.playbackState` (playing/paused/stopped). */
export function buildMediaCommand(
  deviceId: string,
  agentId: string,
  partnerDeviceId: string,
  command: string,
): JsonArr {
  let playbackState: string;
  switch (command.trim().toLowerCase()) {
    case 'play':
    case 'resume':
    case 'start':
    case 'playing':
      playbackState = 'playing';
      break;
    case 'pause':
      playbackState = 'paused';
      break;
    case 'stop':
      playbackState = 'stopped';
      break;
    default:
      throw new Error(`Unsupported media command '${command}'. Supported: play, pause, stop.`);
  }
  return buildUpdate(deviceId, agentId, partnerDeviceId, 'mediaState', 'playbackState', stringWrapper(playbackState));
}

// -------------------------------------------------------------------------------------------------
// AutomationService (routines)
// -------------------------------------------------------------------------------------------------

/** `ListAutomations` request body: `[<structureId>]`. */
export function buildListAutomationsRequest(structureId: string): JsonArr {
  return [structureId];
}

/** `ExecuteAutomation` request body: `[<structureId>, <automationId>, null, 2]`. */
export function buildExecuteAutomationRequest(structureId: string, automationId: string): JsonArr {
  return [structureId, automationId, null, 2];
}

export function parseListAutomations(json: unknown): Automation[] {
  const list = arrAt(arr(json), 0);
  if (list == null) return [];
  const out: Automation[] = [];
  for (const recEl of list) {
    const rec = arr(recEl);
    const id = strAt(rec, 0);
    const name = strAt(rec, 3);
    if (id == null || name == null) continue;
    out.push({
      id,
      name,
      manuallyRunnable: numAt(rec, 2) === 1,
      starters: strAt(rec, 4),
      actions: strAt(rec, 5),
      triggerInput: strAt(rec, 10),
    });
  }
  return out;
}

// -------------------------------------------------------------------------------------------------
// Capability derivation
// -------------------------------------------------------------------------------------------------

export function deriveCapabilities(traits: string[]): Set<Capability> {
  const caps = new Set<Capability>();
  for (const raw of traits) {
    const suffix = raw.split('.').pop()?.toLowerCase() ?? '';
    switch (suffix) {
      case 'onoff':
        caps.add('on_off');
        break;
      case 'brightness':
        caps.add('brightness');
        break;
      case 'colorsetting':
        caps.add('color_temperature');
        caps.add('color');
        break;
      case 'volume':
        caps.add('volume');
        break;
      case 'lockunlock':
        caps.add('lock');
        break;
      case 'temperaturesetting':
      case 'temperaturecontrol':
        caps.add('thermostat');
        break;
      case 'transportcontrol':
      case 'mediastate':
        caps.add('media_transport');
        break;
    }
  }
  return caps;
}

// -------------------------------------------------------------------------------------------------
// GetHomeGraph (enumeration)
// -------------------------------------------------------------------------------------------------

export function parseGetHomeGraph(json: unknown): HomeGraph {
  const homesArr = arrAt(arr(json), 1);
  if (homesArr == null) return { homes: [], devices: [], rooms: [] };

  const homes: Home[] = [];
  const rooms: Room[] = [];
  const devices: Device[] = [];
  const deviceRoom = new Map<string, string>();

  for (const homeEl of homesArr) {
    const home = arr(homeEl);
    const homeId = strAt(home, 0);
    if (homeId == null) continue;
    homes.push({ id: homeId, name: strAt(home, 1) ?? '' });

    // Rooms: [5]
    const roomsArr = arrAt(home, 5);
    if (roomsArr != null) {
      for (const roomEl of roomsArr) {
        const room = arr(roomEl);
        const roomId = strAt(room, 0);
        if (roomId == null) continue;
        const roomName = strAt(room, 2) ?? '';
        const roomType = strAt(arrAt(room, 3), 0) ?? '';
        const memberIds: string[] = [];
        const members = arrAt(room, 4);
        if (members != null) {
          for (const memberEl of members) {
            // member = [[deviceId,[agentId,partnerId]]]
            const devId = strAt(arrAt(arr(memberEl), 0), 0);
            if (devId == null) continue;
            memberIds.push(devId);
            deviceRoom.set(devId, roomName);
          }
        }
        rooms.push({ id: roomId, name: roomName, type: roomType, deviceIds: memberIds });
      }
    }

    // Devices: [6]
    const devsArr = arrAt(home, 6);
    if (devsArr != null) {
      for (const devEl of devsArr) {
        const rec = arr(devEl);
        const key = arrAt(rec, 0); // [deviceId,[agentId,partnerId]]
        if (key == null) continue;
        const devId = strAt(key, 0);
        if (devId == null) continue;
        const agentPair = arrAt(key, 1);
        const agentId = strAt(agentPair, 0);
        const partnerDeviceId = strAt(agentPair, 1);
        const name = strAt(rec, 3) ?? '';
        const type = strAt(rec, 5) ?? '';
        // Field [20] is the USER-ASSIGNED device type; it overrides hardware type [5] for intent.
        const assignedType = strAt(arrAt(rec, 20), 0);
        const traitsArr = arrAt(rec, 6);
        const traits = traitsArr == null ? [] : traitsArr.map((t) => str(t)).filter((t): t is string => t != null);
        devices.push({
          id: devId,
          name,
          type,
          assignedType,
          traits,
          roomName: deviceRoom.get(devId) ?? null,
          agentId,
          partnerDeviceId,
          capabilities: deriveCapabilities(traits),
        });
      }
    }
  }

  // Room membership can appear after a device record within the same home; stamp again.
  const stamped = devices.map((d) =>
    d.roomName == null ? { ...d, roomName: deviceRoom.get(d.id) ?? null } : d,
  );
  return { homes, devices: stamped, rooms };
}
