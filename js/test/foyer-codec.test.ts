import { describe, expect, it } from 'vitest';
import * as codec from '../src/foyer-codec';

// Mirrors src/commonTest/.../FoyerCodecTest.kt — the protobuf positional-array encoding is the
// error-prone part, so every build* helper is asserted against the live-captured byte structure.

const OUTSIDE = '22222222-2222-4222-8222-222222222222';
const AGENT = 'acme-partner';
const PARTNER = 'AA11BB22CC33DD44EE55';
// Common prefix of every UpdateTraits command up to the trait block.
const P = `[[[["22222222-2222-4222-8222-222222222222",["acme-partner","AA11BB22CC33DD44EE55"]],`;

const s = (a: unknown): string => JSON.stringify(a);

describe('UpdateTraits builders (value at type-specific wrapper index)', () => {
  it('buildOnOff — bool at index 3', () => {
    expect(s(codec.buildOnOff(OUTSIDE, AGENT, PARTNER, true))).toBe(`${P}[["onOff",[["onOff",[null,null,null,1]]]]]]]]`);
    expect(s(codec.buildOnOff(OUTSIDE, AGENT, PARTNER, false))).toBe(`${P}[["onOff",[["onOff",[null,null,null,0]]]]]]]]`);
  });

  it('buildBrightness — int at index 1', () => {
    expect(s(codec.buildBrightness(OUTSIDE, AGENT, PARTNER, 42))).toBe(`${P}[["brightness",[["brightness",[null,42]]]]]]]]`);
  });

  it('buildColorTemperature — int at index 1', () => {
    expect(s(codec.buildColorTemperature(OUTSIDE, AGENT, PARTNER, 4000))).toBe(
      `${P}[["color",[["colorTemperature",[null,4000]]]]]]]]`,
    );
  });

  it('buildVolume — int at index 1', () => {
    expect(s(codec.buildVolume(OUTSIDE, AGENT, PARTNER, 55))).toBe(`${P}[["volume",[["currentVolume",[null,55]]]]]]]]`);
  });

  it('buildMuted — bool at index 3', () => {
    expect(s(codec.buildMuted(OUTSIDE, AGENT, PARTNER, true))).toBe(`${P}[["volume",[["isMuted",[null,null,null,1]]]]]]]]`);
  });

  it('buildLock — bool at index 3', () => {
    expect(s(codec.buildLock(OUTSIDE, AGENT, PARTNER, true))).toBe(`${P}[["lockUnlock",[["isLocked",[null,null,null,1]]]]]]]]`);
    expect(s(codec.buildLock(OUTSIDE, AGENT, PARTNER, false))).toBe(`${P}[["lockUnlock",[["isLocked",[null,null,null,0]]]]]]]]`);
  });

  it('buildLockWithPin — isLocked bool@3 + pin string@2', () => {
    expect(s(codec.buildLockWithPin(OUTSIDE, AGENT, PARTNER, false, '1234'))).toBe(
      `${P}[["lockUnlock",[["isLocked",[null,null,null,0]],["pin",[null,null,"1234"]]]]]]]]`,
    );
  });

  it('buildThermostatSetpoint — mode string@2 + whole/decimal setpoint@1', () => {
    expect(s(codec.buildThermostatSetpoint(OUTSIDE, AGENT, PARTNER, 66, 'heat'))).toBe(
      `${P}[["temperatureSetting",[["mode",[null,null,"heat"]],["thermostatTemperatureSetpoint",[null,66]]]]]]]]`,
    );
    expect(s(codec.buildThermostatSetpoint(OUTSIDE, AGENT, PARTNER, 19.5, 'eco'))).toBe(
      `${P}[["temperatureSetting",[["mode",[null,null,"eco"]],["thermostatTemperatureSetpoint",[null,19.5]]]]]]]]`,
    );
  });

  it('buildMediaCommand — playbackState string@2; next rejected', () => {
    expect(s(codec.buildMediaCommand(OUTSIDE, AGENT, PARTNER, 'pause'))).toBe(
      `${P}[["mediaState",[["playbackState",[null,null,"paused"]]]]]]]]`,
    );
    expect(s(codec.buildMediaCommand(OUTSIDE, AGENT, PARTNER, 'play'))).toBe(
      `${P}[["mediaState",[["playbackState",[null,null,"playing"]]]]]]]]`,
    );
    expect(() => codec.buildMediaCommand(OUTSIDE, AGENT, PARTNER, 'next')).toThrow();
  });

  it('buildGetTraitsRequest shape', () => {
    expect(s(codec.buildGetTraitsRequest([OUTSIDE]))).toBe(`[[["22222222-2222-4222-8222-222222222222"]]]`);
  });
});

describe('parse*', () => {
  // Verbatim from captures/UpdateTraits.sample.json (GetTraits readback, onOff back ON).
  const READBACK =
    `[[[["22222222-2222-4222-8222-222222222222"],[["deviceStatus",[["online",[null,null,null,1]],["onlineStateDetails",[null,null,"stateOnline"]]]],["onOff",[["onOff",[null,null,null,1]]],[[["commandOnlyOnOff",[null,null,null,0]]]]]]]]]`;

  it('parseGetTraits reads online + onOff, leaves absent traits null', () => {
    const states = codec.parseGetTraits(JSON.parse(READBACK));
    expect(states.length).toBe(1);
    const st = states[0];
    expect(st.id).toBe(OUTSIDE);
    expect(st.online).toBe(true);
    expect(st.onOff).toBe(true);
    expect(st.brightnessPct).toBeNull();
    expect(st.muted).toBeNull();
    expect(st.locked).toBeNull();
  });

  it('parseGetTraits reads rich state', () => {
    const payload =
      `[[[["dev"],[` +
      `["deviceStatus",[["online",[null,null,null,1]]]],` +
      `["onOff",[["onOff",[null,null,null,1]]]],` +
      `["brightness",[["brightness",[null,42]]]],` +
      `["color",[["colorTemperature",[null,4000]]]],` +
      `["volume",[["currentVolume",[null,55]],["isMuted",[null,null,null,1]]]],` +
      `["lockUnlock",[["isLocked",[null,null,null,1]],["isJammed",[null,null,null,0]]]],` +
      `["temperatureSetting",[["mode",[null,null,"heat"]],` +
      `["thermostatTemperatureSetpointC",[null,19.5]],["ambientAirTemperatureC",[null,21.0]]]]` +
      `]]]]`;
    const st = codec.parseGetTraits(JSON.parse(payload))[0];
    expect(st.id).toBe('dev');
    expect(st.onOff).toBe(true);
    expect(st.brightnessPct).toBe(42);
    expect(st.colorTemperatureK).toBe(4000);
    expect(st.volumePct).toBe(55);
    expect(st.muted).toBe(true);
    expect(st.locked).toBe(true);
    expect(st.jammed).toBe(false);
    expect(st.thermostatMode).toBe('heat');
    expect(st.setpointC).toBe(19.5);
    expect(st.ambientC).toBe(21.0);
  });

  it('parseChallenge detects pinNeeded', () => {
    const payload = `[[[["dev"],[["deviceStatus",[["challenge",[null,null,"pinNeeded"]]]]]]]]`;
    expect(codec.parseChallenge(JSON.parse(payload))).toBe('pinNeeded');
    expect(codec.parseChallenge(JSON.parse(READBACK))).toBeNull();
  });

  it('automation request bodies + parse (flag 1 => runnable, flag 3 => not)', () => {
    expect(s(codec.buildListAutomationsRequest('S'))).toBe(`["S"]`);
    expect(s(codec.buildExecuteAutomationRequest('S', 'A'))).toBe(`["S","A",null,2]`);
    const resp =
      `[[` +
      `["id1",null,1,"Runnable","1 starter","2 actions","edit",null,2,"icon","BLOB1","0 errors"],` +
      `["id2",null,3,"AutoOnly","1 starter","1 action","edit",null,2,"icon","BLOB2","0 errors"]` +
      `],[],[]]`;
    const autos = codec.parseListAutomations(JSON.parse(resp));
    expect(autos.length).toBe(2);
    expect(autos[0].name).toBe('Runnable');
    expect(autos[0].manuallyRunnable).toBe(true);
    expect(autos[0].actions).toBe('2 actions');
    expect(autos[0].triggerInput).toBe('BLOB1');
    expect(autos[1].manuallyRunnable).toBe(false);
  });

  it('deriveCapabilities maps trait suffixes', () => {
    const caps = codec.deriveCapabilities([
      'action.devices.traits.OnOff',
      'action.devices.traits.Brightness',
      'action.devices.traits.ColorSetting',
      'action.devices.traits.Volume',
      'action.devices.traits.LockUnlock',
      'action.devices.traits.TemperatureSetting',
      'action.devices.traits.TransportControl',
    ]);
    expect([...caps].sort()).toEqual(
      ['brightness', 'color', 'color_temperature', 'lock', 'media_transport', 'on_off', 'thermostat', 'volume'].sort(),
    );
  });
});

describe('parseGetHomeGraph — effectiveType (assigned type[20] overrides hardware[5])', () => {
  // Minimal home graph: one home with one room and one OUTLET device assigned to LIGHT.
  const graph = {
    homes: [
      [
        'home1',
        'Home',
        null,
        null,
        null,
        // rooms[5]
        [['home1.roomA', null, 'Outside', ['OUTDOOR'], [[[OUTSIDE, [AGENT, PARTNER]]]]]],
        // devices[6]
        [
          buildDeviceRec(),
        ],
      ],
    ],
  };

  function buildDeviceRec(): unknown[] {
    const rec = new Array(21).fill(null);
    rec[0] = [OUTSIDE, [AGENT, PARTNER]];
    rec[3] = 'Patio Light';
    rec[5] = 'action.devices.types.OUTLET';
    rec[6] = ['action.devices.traits.OnOff'];
    rec[20] = ['action.devices.types.LIGHT'];
    return rec;
  }

  it('parses device with assigned LIGHT type, room, agent/partner', () => {
    const parsed = codec.parseGetHomeGraph([null, graph.homes]);
    const d = parsed.devices.find((x) => x.id === OUTSIDE);
    expect(d).toBeDefined();
    expect(d!.name).toBe('Patio Light');
    expect(d!.roomName).toBe('Outside');
    expect(d!.agentId).toBe(AGENT);
    expect(d!.partnerDeviceId).toBe(PARTNER);
    expect(d!.type).toBe('action.devices.types.OUTLET');
    expect(d!.assignedType).toBe('action.devices.types.LIGHT');
    expect(codec.effectiveType(d!)).toBe('action.devices.types.LIGHT');
    expect(d!.capabilities.has('on_off')).toBe(true);
    const room = parsed.rooms.find((r) => r.name === 'Outside');
    expect(room?.deviceIds).toContain(OUTSIDE);
  });
});
