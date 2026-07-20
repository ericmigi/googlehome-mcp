// The 16 Google Home MCP tools (+ set_passcode) and their handlers, plus the name/room/type device
// resolver. Ported from the Kotlin `GoogleHomeMcpServer` + `DeviceResolver` + `Passcode`.
//
// Read tools (list_homes/list_devices/get_device_state) enumerate and read everything. Control tools
// take a selector (name/room/type), apply to EVERY capable match, and return per-device
// {id,name,ok,state|error}. There is no add/delete surface. Handlers return a JSON string.

import * as codec from './foyer-codec';
import { effectiveType } from './foyer-codec';
import { FoyerClient } from './foyer-client';

export interface ToolProperty {
  type: string;
  description: string;
  items?: { type: string };
}

export interface ToolDef {
  name: string;
  description: string;
  properties: Record<string, ToolProperty>;
  required: string[];
}

/** JSON-Schema `object` for a tool's inputs. */
export function inputSchema(t: ToolDef): unknown {
  const properties: Record<string, unknown> = {};
  for (const [name, p] of Object.entries(t.properties)) {
    const prop: Record<string, unknown> = { type: p.type, description: p.description };
    if (p.items) prop['items'] = p.items;
    properties[name] = prop;
  }
  return { type: 'object', properties, required: t.required };
}

// -------------------------------------------------------------------------------------------------
// Static tool registry (name/description/schema only — zero host I/O to read)
// -------------------------------------------------------------------------------------------------

function selectorProps(...extra: [string, ToolProperty][]): Record<string, ToolProperty> {
  const props: Record<string, ToolProperty> = {
    name: { type: 'string', description: 'Device name to match (case-insensitive substring).' },
    room: { type: 'string', description: 'Room name to match (case-insensitive substring), e.g. "kitchen".' },
    type: {
      type: 'string',
      description: 'Device type suffix: light, lock, speaker, thermostat, outlet, switch.',
    },
  };
  for (const [k, v] of extra) props[k] = v;
  return props;
}

export const TOOLS: ToolDef[] = [
  {
    name: 'list_homes',
    description: 'List all Google Home homes/structures the account can see. Read-only.',
    properties: {},
    required: [],
  },
  {
    name: 'list_devices',
    description:
      'List every device across all homes with its room, type, supported traits, derived ' +
      'capabilities (on_off, brightness, color_temperature, color, volume, lock, thermostat, ' +
      'media_transport), and live online/onOff state. Read-only. Use this to see what ' +
      "names/rooms/types the control tools' selectors can target.",
    properties: {},
    required: [],
  },
  {
    name: 'get_device_state',
    description:
      'Get the live state (online, on_off, brightness, color temperature, volume, mute, lock, ' +
      'thermostat) for one or more devices by id. Read-only.',
    properties: {
      device_ids: {
        type: 'array',
        description: 'Device ids to read state for.',
        items: { type: 'string' },
      },
    },
    required: ['device_ids'],
  },
  {
    name: 'turn_on',
    description:
      'Turn on devices. Scope with name/room/type, e.g. name="Corner Lamp" for one device, ' +
      'type="light" for all lights, or room="kitchen" + type="light" for the kitchen lights. ' +
      'Applies to every matching on/off device.',
    properties: selectorProps(),
    required: [],
  },
  {
    name: 'turn_off',
    description:
      'Turn off devices. Scope with name/room/type, e.g. type="light" for all lights, or ' +
      'room="kitchen" + type="light" for the kitchen lights. Applies to every matching on/off device.',
    properties: selectorProps(),
    required: [],
  },
  {
    name: 'set_brightness',
    description:
      'Set brightness (0-100%) on lights. Scope with name/room/type, e.g. room="bedroom" + ' +
      'type="light" brightness_pct=40. Applies to every matching dimmable device.',
    properties: selectorProps(['brightness_pct', { type: 'integer', description: 'Brightness 0-100 (clamped).' }]),
    required: ['brightness_pct'],
  },
  {
    name: 'set_color_temperature',
    description:
      'Set white color temperature in Kelvin (warm ~2700, cool ~6500) on color/CCT lights. Scope ' +
      'with name/room/type, e.g. name="Desk Light" kelvin=4000.',
    properties: selectorProps([
      'kelvin',
      { type: 'integer', description: 'Color temperature in Kelvin, e.g. 2700-6500.' },
    ]),
    required: ['kelvin'],
  },
  {
    name: 'set_volume',
    description:
      'Set volume (0-100%) on speakers. Scope with name/room/type, e.g. name="Living Room ' +
      'Speaker" volume_pct=30, or type="speaker" for all speakers.',
    properties: selectorProps(['volume_pct', { type: 'integer', description: 'Volume 0-100 (clamped).' }]),
    required: ['volume_pct'],
  },
  {
    name: 'set_muted',
    description:
      'Mute or unmute speakers. Scope with name/room/type, e.g. name="Bedroom speaker" muted=true, ' +
      'or type="speaker" muted=true for all speakers.',
    properties: selectorProps(['muted', { type: 'boolean', description: 'true = muted, false = unmuted.' }]),
    required: ['muted'],
  },
  {
    name: 'lock',
    description:
      'Lock smart locks. Scope with name/room/type, e.g. name="Front Door", or type="lock" for ' +
      'all locks. Locking needs no PIN; unlocking uses the separate unlock tool.',
    properties: selectorProps(),
    required: [],
  },
  {
    name: 'unlock',
    description:
      'Unlock smart locks (PIN-gated). Uses your saved passcode if pin is omitted — save one first ' +
      'with set_passcode ("my Google Home passcode is 1122"). Or pass pin directly. Scope with ' +
      'name/room/type, e.g. name="Front Door".',
    properties: selectorProps([
      'pin',
      { type: 'string', description: "The lock's unlock PIN. Optional if a passcode was saved via set_passcode." },
    ]),
    required: [],
  },
  {
    name: 'set_passcode',
    description:
      'Save the smart-lock unlock passcode (PIN) so unlock can be used without repeating it, e.g. ' +
      '"my Google Home passcode is 1122" or "save my passcode 1122". Stored securely on-device; ' +
      'only the unlock tool reads it. Never returns the PIN.',
    properties: {
      passcode: { type: 'string', description: 'The unlock PIN to save (digits, e.g. 1122).' },
    },
    required: ['passcode'],
  },
  {
    name: 'set_thermostat',
    description:
      'Set a thermostat\'s target temperature and/or mode. Provide temperature_c OR temperature_f, ' +
      'and/or mode (heat/cool/eco/off). Scope with name/room/type, e.g. name="Hallway" ' +
      'temperature_f=70 mode="heat".',
    properties: selectorProps(
      ['temperature_c', { type: 'number', description: 'Target setpoint in Celsius.' }],
      ['temperature_f', { type: 'number', description: 'Target setpoint in Fahrenheit (converted to C).' }],
      ['mode', { type: 'string', description: 'Thermostat mode: heat, cool, eco, or off.' }],
    ),
    required: [],
  },
  {
    name: 'media_control',
    description:
      'Control media playback on speakers/players. command is one of: play, pause, stop. Scope ' +
      'with name/room/type, e.g. name="Hub" command="pause". (next/previous are not supported by ' +
      'this playback field.)',
    properties: selectorProps(['command', { type: 'string', description: 'Playback command: play, pause, or stop.' }]),
    required: ['command'],
  },
  {
    name: 'list_automations',
    description:
      "List the home's automations/routines: name, whether it can be started on demand " +
      '(manually_runnable), and its starter/action summary. Read-only. Use before run_automation to ' +
      'find a name.',
    properties: {},
    required: [],
  },
  {
    name: 'run_automation',
    description:
      'Start/run an automation by name, e.g. name="Movie Night". Only manually-runnable routines ' +
      'can be started (schedule/condition-only ones cannot). This executes the automation\'s ' +
      'EXISTING actions; it never creates or deletes anything.',
    properties: {
      name: { type: 'string', description: "The automation's name (case-insensitive; exact preferred)." },
    },
    required: ['name'],
  },
];

export const TOOL_NAMES = new Set(TOOLS.map((t) => t.name));

// -------------------------------------------------------------------------------------------------
// Passcode normalization
// -------------------------------------------------------------------------------------------------

/** Strip spaces/dashes and require 4+ digits. Google Home lock PINs are numeric. */
export function normalizePasscode(raw: string): string {
  const digits = raw.trim().replace(/ /g, '').replace(/-/g, '');
  if (digits.length < 4 || !/^\d+$/.test(digits)) {
    throw new Error('Passcode must be at least 4 digits (numbers only).');
  }
  return digits;
}

export interface PasscodeStore {
  get(): Promise<string | null>;
  set(pin: string): Promise<void>;
}

// -------------------------------------------------------------------------------------------------
// Device resolver
// -------------------------------------------------------------------------------------------------

export interface Selector {
  name?: string;
  room?: string;
  type?: string;
}

function typeMatches(deviceType: string, want: string): boolean {
  const suffix = deviceType.split('.').pop()?.toLowerCase() ?? '';
  return suffix === want || deviceType.toLowerCase() === want;
}

function describeSelector(sel: Selector): string {
  const parts = [
    sel.name && sel.name.trim() !== '' ? `name="${sel.name}"` : null,
    sel.room && sel.room.trim() !== '' ? `room="${sel.room}"` : null,
    sel.type && sel.type.trim() !== '' ? `type="${sel.type}"` : null,
  ].filter((p): p is string => p != null);
  return parts.length === 0 ? '(no filters)' : parts.join(', ');
}

function nearNames(sel: Selector, devices: codec.Device[], limit = 12): string[] {
  const wantRoom = norm(sel.room);
  const wantType = norm(sel.type);
  const wantNameTokens = (norm(sel.name) ?? '').split(/\s+/).filter((t) => t !== '');
  const relaxed = devices.filter(
    (d) =>
      (wantRoom != null && d.roomName?.trim().toLowerCase().includes(wantRoom) === true) ||
      (wantType != null && typeMatches(effectiveType(d), wantType)) ||
      (wantNameTokens.length > 0 && wantNameTokens.some((t) => d.name.toLowerCase().includes(t))),
  );
  const pool = relaxed.length > 0 ? relaxed : devices;
  return [...new Set(pool.map((d) => d.name))].slice(0, limit);
}

function norm(s: string | undefined): string | null {
  const t = s?.trim().toLowerCase();
  return t && t !== '' ? t : null;
}

async function resolve(foyer: FoyerClient, sel: Selector): Promise<codec.Device[]> {
  if (norm(sel.name) == null && norm(sel.room) == null && norm(sel.type) == null) {
    throw new Error(
      'Provide at least one selector: name, room, and/or type (e.g. type="light" for all lights, ' +
        'or room="kitchen" + type="light" for the kitchen lights).',
    );
  }
  const { devices } = await foyer.getHomeGraph();
  const wantName = norm(sel.name);
  const wantRoom = norm(sel.room);
  const wantType = norm(sel.type);

  // Room matches EXACTLY; type matches the type suffix. AND-combined.
  const scoped = devices.filter(
    (d) =>
      (wantRoom == null || d.roomName?.trim().toLowerCase() === wantRoom) &&
      (wantType == null || typeMatches(effectiveType(d), wantType)),
  );

  let matches: codec.Device[];
  if (wantName == null) {
    matches = scoped;
  } else {
    const exact = scoped.filter((d) => d.name.trim().toLowerCase() === wantName);
    matches = exact.length > 0 ? exact : scoped.filter((d) => d.name.trim().toLowerCase().includes(wantName));
  }

  if (matches.length === 0) {
    const near = nearNames(sel, devices);
    const known =
      near.length === 0
        ? 'No devices are known.'
        : `Closest known devices: ${near.map((n) => `"${n}"`).join(', ')}.`;
    throw new Error(
      `No devices matched selector ${describeSelector(sel)}. ${known} ` +
        'Use list_devices to see every device with its room, type and capabilities.',
    );
  }
  return matches;
}

// -------------------------------------------------------------------------------------------------
// JSON projection
// -------------------------------------------------------------------------------------------------

function stateToJson(s: codec.DeviceState): Record<string, unknown> {
  return {
    id: s.id,
    online: s.online,
    on_off: s.onOff,
    brightness_pct: s.brightnessPct,
    color_temperature_k: s.colorTemperatureK,
    volume_pct: s.volumePct,
    muted: s.muted,
    locked: s.locked,
    jammed: s.jammed,
    thermostat_mode: s.thermostatMode,
    setpoint_c: s.setpointC,
    ambient_c: s.ambientC,
  };
}

function deviceToJson(d: codec.Device, state: codec.DeviceState | undefined): Record<string, unknown> {
  const et = effectiveType(d);
  const out: Record<string, unknown> = {
    id: d.id,
    name: d.name,
    type: et,
  };
  if (d.assignedType != null && d.assignedType !== d.type) out['hardware_type'] = d.type;
  out['traits'] = d.traits;
  out['capabilities'] = [...d.capabilities];
  out['room_name'] = d.roomName;
  out['agent_id'] = d.agentId;
  out['partner_device_id'] = d.partnerDeviceId;
  out['online'] = state?.online ?? null;
  out['on_off'] = state?.onOff ?? null;
  return out;
}

function resultRow(
  id: string,
  name: string,
  ok: boolean,
  state?: codec.DeviceState,
  error?: string,
): Record<string, unknown> {
  const row: Record<string, unknown> = { id, name, ok };
  if (state) row['state'] = stateToJson(state);
  if (error) row['error'] = error;
  return row;
}

// -------------------------------------------------------------------------------------------------
// Argument decoding
// -------------------------------------------------------------------------------------------------

type Args = Record<string, unknown>;

function optString(args: Args, key: string): string | null {
  const v = args[key];
  if (typeof v !== 'string') return null;
  return v.trim() !== '' ? v : null;
}
function requireString(args: Args, key: string): string {
  const v = optString(args, key);
  if (v == null) throw new Error(`Missing required string argument \`${key}\`.`);
  return v;
}
function optInt(args: Args, key: string): number | null {
  const v = args[key];
  if (typeof v === 'number') return Math.trunc(v);
  if (typeof v === 'string') {
    const n = Number.parseInt(v.trim(), 10);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
function requireInt(args: Args, key: string): number {
  const v = optInt(args, key);
  if (v == null) throw new Error(`Missing/invalid integer argument \`${key}\`.`);
  return v;
}
function requireBool(args: Args, key: string): boolean {
  const v = args[key];
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') return v.toLowerCase() === 'true' || v === '1';
  throw new Error(`Missing boolean argument \`${key}\`.`);
}
function optDouble(args: Args, key: string): number | null {
  const v = args[key];
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n = Number.parseFloat(v.trim());
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
function thermostatSetpointC(args: Args): number | null {
  const c = optDouble(args, 'temperature_c');
  if (c != null) return c;
  const f = optDouble(args, 'temperature_f');
  if (f != null) return ((f - 32) * 5) / 9;
  return null;
}
function selectorFrom(args: Args): Selector {
  return {
    name: optString(args, 'name') ?? undefined,
    room: optString(args, 'room') ?? undefined,
    type: optString(args, 'type') ?? undefined,
  };
}

// -------------------------------------------------------------------------------------------------
// Control plumbing
// -------------------------------------------------------------------------------------------------

async function applyControl(
  foyer: FoyerClient,
  args: Args,
  requiredCap: codec.Capability,
  capLabel: string,
  action: (d: codec.Device) => Promise<codec.DeviceState>,
): Promise<unknown> {
  const devices = await resolve(foyer, selectorFrom(args));
  const rows: Record<string, unknown>[] = [];
  for (const d of devices) {
    if (!d.capabilities.has(requiredCap)) {
      rows.push(resultRow(d.id, d.name, false, undefined, `skipped: does not support ${capLabel}`));
      continue;
    }
    try {
      rows.push(resultRow(d.id, d.name, true, await action(d)));
    } catch (e) {
      rows.push(resultRow(d.id, d.name, false, undefined, e instanceof Error ? e.message : String(e)));
    }
  }
  return rows;
}

const agent = (d: codec.Device): string => d.agentId ?? '';
const partner = (d: codec.Device): string => d.partnerDeviceId ?? '';

// -------------------------------------------------------------------------------------------------
// Dispatch — returns the JSON result string for a tool call
// -------------------------------------------------------------------------------------------------

export async function callServerTool(
  foyer: FoyerClient,
  passcodes: PasscodeStore,
  name: string,
  args: Args,
): Promise<unknown> {
  switch (name) {
    case 'list_homes': {
      const { homes } = await foyer.getHomeGraph();
      return homes.map((h) => ({ id: h.id, name: h.name }));
    }
    case 'list_devices': {
      const { devices } = await foyer.getHomeGraph();
      let stateById = new Map<string, codec.DeviceState>();
      if (devices.length > 0) {
        try {
          stateById = new Map((await foyer.getTraits(devices.map((d) => d.id))).map((s) => [s.id, s]));
        } catch {
          // best-effort live state; enumeration still returns without it
        }
      }
      return devices.map((d) => deviceToJson(d, stateById.get(d.id)));
    }
    case 'get_device_state': {
      const ids = Array.isArray(args['device_ids'])
        ? (args['device_ids'] as unknown[]).map((x) => String(x))
        : [];
      return (await foyer.getTraits(ids)).map(stateToJson);
    }
    case 'turn_on':
      return applyControl(foyer, args, 'on_off', 'on/off', (d) => foyer.setOnOff(d.id, agent(d), partner(d), true));
    case 'turn_off':
      return applyControl(foyer, args, 'on_off', 'on/off', (d) => foyer.setOnOff(d.id, agent(d), partner(d), false));
    case 'set_brightness': {
      const pct = requireInt(args, 'brightness_pct');
      return applyControl(foyer, args, 'brightness', 'brightness', (d) =>
        foyer.setBrightness(d.id, agent(d), partner(d), pct),
      );
    }
    case 'set_color_temperature': {
      const kelvin = requireInt(args, 'kelvin');
      return applyControl(foyer, args, 'color_temperature', 'color temperature', (d) =>
        foyer.setColorTemperature(d.id, agent(d), partner(d), kelvin),
      );
    }
    case 'set_volume': {
      const pct = requireInt(args, 'volume_pct');
      return applyControl(foyer, args, 'volume', 'volume', (d) => foyer.setVolume(d.id, agent(d), partner(d), pct));
    }
    case 'set_muted': {
      const muted = requireBool(args, 'muted');
      return applyControl(foyer, args, 'volume', 'volume/mute', (d) => foyer.setMuted(d.id, agent(d), partner(d), muted));
    }
    case 'lock':
      return applyControl(foyer, args, 'lock', 'lock', (d) => foyer.setLocked(d.id, agent(d), partner(d), true, null));
    case 'unlock': {
      const explicit = optString(args, 'pin');
      const pin = explicit != null ? normalizePasscode(explicit) : await passcodes.get();
      if (pin == null) {
        throw new Error(
          'No unlock PIN. Save one with set_passcode (e.g. "my Google Home passcode is 1122") or pass pin.',
        );
      }
      return applyControl(foyer, args, 'lock', 'lock', (d) => foyer.setLocked(d.id, agent(d), partner(d), false, pin));
    }
    case 'set_thermostat': {
      const setpointC = thermostatSetpointC(args);
      const mode = optString(args, 'mode');
      if (setpointC == null && mode == null) {
        throw new Error('set_thermostat needs at least one of temperature_c, temperature_f, or mode.');
      }
      return applyControl(foyer, args, 'thermostat', 'thermostat', (d) =>
        foyer.setThermostat(d.id, agent(d), partner(d), setpointC, mode),
      );
    }
    case 'media_control': {
      const command = requireString(args, 'command');
      return applyControl(foyer, args, 'media_transport', 'media transport', (d) =>
        foyer.mediaCommand(d.id, agent(d), partner(d), command),
      );
    }
    case 'list_automations': {
      const autos = await foyer.listAutomations();
      return autos.map((a) => ({
        id: a.id,
        name: a.name,
        manually_runnable: a.manuallyRunnable,
        starters: a.starters,
        actions: a.actions,
      }));
    }
    case 'run_automation': {
      const wanted = requireString(args, 'name').trim();
      const autos = await foyer.listAutomations();
      const lower = wanted.toLowerCase();
      const match =
        autos.find((a) => a.name.trim().toLowerCase() === lower) ??
        autos.find((a) => a.name.trim().toLowerCase().includes(lower));
      if (match == null) {
        throw new Error(
          `No automation named '${wanted}'. Available: ${autos.map((a) => `"${a.name}"`).join(', ')}`,
        );
      }
      const ok = await foyer.runAutomation(match);
      return { name: match.name, id: match.id, ok };
    }
    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}
