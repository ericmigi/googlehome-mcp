import { describe, expect, it, vi } from 'vitest';
import type { Device, DeviceState, Capability } from '../src/foyer-codec';
import type { FoyerClient } from '../src/foyer-client';
import { callServerTool, type PasscodeStore } from '../src/server';

const passcodes: PasscodeStore = {
  async get() {
    return null;
  },
  async set() {},
};

function device(name: string, capabilities: Capability[], id = name.toLowerCase().replace(/ /g, '-')): Device {
  return {
    id,
    name,
    type: 'action.devices.types.LIGHT',
    assignedType: null,
    traits: [],
    roomName: 'Outside',
    agentId: 'agent',
    partnerDeviceId: 'partner',
    capabilities: new Set(capabilities),
  };
}

function state(id: string): DeviceState {
  return {
    id,
    online: true,
    onOff: true,
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
}

function fakeFoyer(devices: Device[]) {
  const setOnOff = vi.fn(async (id: string) => state(id));
  const setBrightness = vi.fn(async (id: string) => state(id));
  const foyer = {
    getHomeGraph: vi.fn(async () => ({ homes: [], devices })),
    setOnOff,
    setBrightness,
  } as unknown as FoyerClient;
  return { foyer, setOnOff, setBrightness };
}

describe('capability-aware device-name aliases', () => {
  it('turns on a light-capable device named driveway for "the driveway lights"', async () => {
    const target = device('driveway', ['on_off']);
    const { foyer, setOnOff } = fakeFoyer([target]);

    const result = await callServerTool(foyer, passcodes, 'turn_on', { name: 'the driveway lights' });

    expect(setOnOff).toHaveBeenCalledOnce();
    expect(setOnOff).toHaveBeenCalledWith(target.id, 'agent', 'partner', true);
    expect(result).toMatchObject([{ id: target.id, name: 'driveway', ok: true }]);
  });

  it('keeps an exact full-name match ahead of the shorter alias', async () => {
    const full = device('Driveway Lights', ['on_off'], 'full');
    const short = device('Driveway', ['on_off'], 'short');
    const { foyer, setOnOff } = fakeFoyer([short, full]);

    await callServerTool(foyer, passcodes, 'turn_on', { name: 'driveway lights' });

    expect(setOnOff).toHaveBeenCalledOnce();
    expect(setOnOff).toHaveBeenCalledWith('full', 'agent', 'partner', true);
  });

  it('preserves a literal leading article when stripping a trailing kind', async () => {
    const exact = device('The Driveway', ['on_off'], 'exact');
    const broader = device('The Driveway East', ['on_off'], 'broader');
    const { foyer, setOnOff } = fakeFoyer([broader, exact]);

    await callServerTool(foyer, passcodes, 'turn_on', { name: 'the driveway lights' });

    expect(setOnOff).toHaveBeenCalledOnce();
    expect(setOnOff).toHaveBeenCalledWith('exact', 'agent', 'partner', true);
  });

  it('accepts a speaker kind for on/off-capable speakers', async () => {
    const target = device('Den', ['on_off'], 'den');
    const { foyer, setOnOff } = fakeFoyer([target]);

    await callServerTool(foyer, passcodes, 'turn_on', { name: 'den speakers' });

    expect(setOnOff).toHaveBeenCalledOnce();
    expect(setOnOff).toHaveBeenCalledWith('den', 'agent', 'partner', true);
  });

  it('applies the same narrow fallback to other capability-specific kinds', async () => {
    const target = device('Patio', ['brightness']);
    const { foyer, setBrightness } = fakeFoyer([target]);

    await callServerTool(foyer, passcodes, 'set_brightness', {
      name: 'patio lamps',
      brightness_pct: 40,
    });

    expect(setBrightness).toHaveBeenCalledOnce();
    expect(setBrightness).toHaveBeenCalledWith(target.id, 'agent', 'partner', 40);
  });

  it('does not drop an unrelated trailing word', async () => {
    const { foyer, setOnOff } = fakeFoyer([device('driveway', ['on_off'])]);

    await expect(
      callServerTool(foyer, passcodes, 'turn_on', { name: 'driveway camera' }),
    ).rejects.toThrow('No devices matched selector');
    expect(setOnOff).not.toHaveBeenCalled();
  });

  it('does not use an alias for a device lacking the action capability', async () => {
    const { foyer, setOnOff } = fakeFoyer([device('driveway', ['brightness'])]);

    const result = await callServerTool(foyer, passcodes, 'turn_on', { name: 'driveway lights' });

    expect(setOnOff).not.toHaveBeenCalled();
    expect(result).toMatchObject([
      { id: 'driveway', name: 'driveway', ok: false, error: 'skipped: does not support on/off' },
    ]);
  });

  it('does not bypass an unsupported exact alias for a different capable substring', async () => {
    const exact = device('Porch', ['on_off'], 'exact');
    const other = device('Porch Accent', ['brightness'], 'other');
    const { foyer, setBrightness } = fakeFoyer([exact, other]);

    const result = await callServerTool(foyer, passcodes, 'set_brightness', {
      name: 'porch lights',
      brightness_pct: 40,
    });

    expect(setBrightness).not.toHaveBeenCalled();
    expect(result).toMatchObject([
      { id: 'exact', name: 'Porch', ok: false, error: 'skipped: does not support brightness' },
    ]);
  });

  it('does not turn a bare kind word into an empty catch-all selector', async () => {
    const { foyer, setOnOff } = fakeFoyer([device('driveway', ['on_off'])]);

    await expect(callServerTool(foyer, passcodes, 'turn_on', { name: 'lights' })).rejects.toThrow(
      'No devices matched selector',
    );
    expect(setOnOff).not.toHaveBeenCalled();
  });

  it('does not broaden an article plus kind into an article-only substring', async () => {
    const { foyer, setOnOff } = fakeFoyer([device('My Room', ['on_off'])]);

    await expect(callServerTool(foyer, passcodes, 'turn_on', { name: 'my lights' })).rejects.toThrow(
      'No devices matched selector',
    );
    expect(setOnOff).not.toHaveBeenCalled();
  });
});
