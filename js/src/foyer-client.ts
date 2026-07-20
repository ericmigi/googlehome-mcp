// The foyer-pa RPC client: builds positional `application/json+protobuf` arrays (via foyer-codec),
// POSTs them over the host `fetch`, mints the bearer from the master token (gpsoauth), and retries
// once on 401/403 while advancing scope negotiation. Ported from the Kotlin `FoyerRpcClient` +
// `FoyerAuthImpl`.

import * as codec from './foyer-codec';
import { GpsOAuth } from './gpsoauth';

const BASE = 'https://googlehomefoyer-pa.clients6.google.com/$rpc/google.internal.home.foyer.v1.';
const HOME_CONTROL_SERVICE = 'HomeControlService';
const STRUCTURES_SERVICE = 'StructuresService';
const AUTOMATION_SERVICE = 'AutomationService';
const CONTENT_TYPE = 'application/json+protobuf';

// The (app, client_sig, scope) triple that yields a bearer foyer-pa accepts.
const APP = 'com.google.android.apps.chromecast.app';
const CLIENT_SIG = '24bb24c05e47e0aefa68a58a766179d9b613a600';
const SCOPE_CANDIDATES = [
  'oauth2:https://www.googleapis.com/auth/homegraph',
  'oauth2:https://www.google.com/accounts/OAuthLogin',
];

export class ChallengeRequiredError extends Error {
  constructor(public deviceId: string, public challenge: string) {
    super(`Device ${deviceId} requires a challenge: ${challenge}`);
  }
}

export class FoyerHttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** Strips a leading XSSI guard line (`)]}'`) some clients6 endpoints prepend. */
export function stripXssiPrefix(body: string): string {
  const trimmed = body.replace(/^\s+/, '');
  if (trimmed.startsWith(")]}'")) {
    return trimmed.substring(4).replace(/^[\r\n]+/, '');
  }
  return body;
}

/** Mints + caches short-lived foyer bearers from the master token, with scope negotiation. */
export class FoyerAuth {
  private cachedToken: string | null = null;
  private expiresAtEpoch = 0;
  private scopeIndex = 0;

  constructor(
    private masterToken: string,
    private androidId: string,
    private oauth: GpsOAuth,
    private scopes: string[] = SCOPE_CANDIDATES,
    private app: string = APP,
    private clientSig: string = CLIENT_SIG,
    private fallbackTtlSeconds = 55 * 60,
    private expirySkewSeconds = 60,
    private nowEpochSeconds: () => number = () => Math.floor(Date.now() / 1000),
  ) {}

  async bearer(): Promise<string> {
    const now = this.nowEpochSeconds();
    if (this.cachedToken != null && now < this.expiresAtEpoch) return this.cachedToken;
    return this.mint(now);
  }

  invalidate(): void {
    this.cachedToken = null;
    this.expiresAtEpoch = 0;
    this.scopeIndex = this.scopes.length === 0 ? 0 : (this.scopeIndex + 1) % this.scopes.length;
  }

  private async mint(now: number): Promise<string> {
    if (this.scopes.length === 0) throw new Error('No foyer scope candidates configured; cannot mint bearer');
    let lastError: unknown = null;
    for (let offset = 0; offset < this.scopes.length; offset++) {
      const i = (this.scopeIndex + offset) % this.scopes.length;
      try {
        const token = await this.oauth.getAuthToken(
          this.masterToken,
          this.androidId,
          this.scopes[i],
          this.app,
          this.clientSig,
        );
        this.scopeIndex = i;
        this.cachedToken = token.auth;
        this.expiresAtEpoch = this.deadlineFor(token.expiryEpochSeconds, now);
        return token.auth;
      } catch (e) {
        lastError = e;
      }
    }
    throw lastError instanceof Error ? lastError : new Error('No foyer scope candidates could be minted');
  }

  private deadlineFor(expiryEpochSeconds: number | null, now: number): number {
    const cap = now + this.fallbackTtlSeconds;
    const base =
      expiryEpochSeconds != null && expiryEpochSeconds > now ? Math.min(expiryEpochSeconds, cap) : cap;
    return base - this.expirySkewSeconds;
  }
}

export class FoyerClient {
  constructor(private auth: FoyerAuth) {}

  // Reads ---------------------------------------------------------------------------------------

  private async fetchHomeGraph(): Promise<codec.HomeGraph> {
    const resp = await this.rpc(STRUCTURES_SERVICE, 'GetHomeGraph', []);
    return codec.parseGetHomeGraph(resp);
  }

  async getHomeGraph(): Promise<{ homes: codec.Home[]; devices: codec.Device[] }> {
    const g = await this.fetchHomeGraph();
    return { homes: g.homes, devices: g.devices };
  }

  async listRooms(): Promise<codec.Room[]> {
    return (await this.fetchHomeGraph()).rooms;
  }

  async getTraits(deviceIds: string[]): Promise<codec.DeviceState[]> {
    if (deviceIds.length === 0) return [];
    const resp = await this.rpc(HOME_CONTROL_SERVICE, 'GetTraits', codec.buildGetTraitsRequest(deviceIds));
    return codec.parseGetTraits(resp);
  }

  // Control (existing devices only; no add/delete) ----------------------------------------------

  async setOnOff(deviceId: string, agentId: string, partnerDeviceId: string, on: boolean): Promise<codec.DeviceState> {
    return this.update(deviceId, codec.buildOnOff(deviceId, agentId, partnerDeviceId, on), on);
  }
  async setBrightness(deviceId: string, agentId: string, partnerDeviceId: string, pct: number): Promise<codec.DeviceState> {
    return this.update(deviceId, codec.buildBrightness(deviceId, agentId, partnerDeviceId, clamp(pct, 0, 100)));
  }
  async setColorTemperature(deviceId: string, agentId: string, partnerDeviceId: string, kelvin: number): Promise<codec.DeviceState> {
    return this.update(deviceId, codec.buildColorTemperature(deviceId, agentId, partnerDeviceId, kelvin));
  }
  async setVolume(deviceId: string, agentId: string, partnerDeviceId: string, pct: number): Promise<codec.DeviceState> {
    return this.update(deviceId, codec.buildVolume(deviceId, agentId, partnerDeviceId, clamp(pct, 0, 100)));
  }
  async setMuted(deviceId: string, agentId: string, partnerDeviceId: string, muted: boolean): Promise<codec.DeviceState> {
    return this.update(deviceId, codec.buildMuted(deviceId, agentId, partnerDeviceId, muted));
  }

  async setLocked(
    deviceId: string,
    agentId: string,
    partnerDeviceId: string,
    locked: boolean,
    pin: string | null,
  ): Promise<codec.DeviceState> {
    const resp = await this.rpc(HOME_CONTROL_SERVICE, 'UpdateTraits', codec.buildLock(deviceId, agentId, partnerDeviceId, locked));
    const challenge = codec.parseChallenge(resp);
    if (challenge != null) {
      if (pin == null) throw new ChallengeRequiredError(deviceId, challenge);
      const resp2 = await this.rpc(
        HOME_CONTROL_SERVICE,
        'UpdateTraits',
        codec.buildLockWithPin(deviceId, agentId, partnerDeviceId, locked, pin),
      );
      return this.stateFrom(resp2, deviceId, undefined, locked);
    }
    return this.stateFrom(resp, deviceId, undefined, locked);
  }

  async setThermostat(
    deviceId: string,
    agentId: string,
    partnerDeviceId: string,
    setpointC: number | null,
    mode: string | null,
  ): Promise<codec.DeviceState> {
    // Nest requires `mode` with the setpoint, and the value must be in the device's DISPLAY unit.
    const states = await this.getTraits([deviceId]);
    const current = states.find((s) => s.id === deviceId) ?? null;
    const unit = current?.temperatureUnit ?? 'F';
    const effectiveMode = mode ?? current?.thermostatMode ?? 'heat';
    let setpointDisplay: number | null = null;
    if (setpointC != null) {
      setpointDisplay = unit.toUpperCase() === 'F' ? Math.round(setpointC * 9 / 5 + 32) : Math.round(setpointC * 2) / 2;
    }
    return this.update(
      deviceId,
      codec.buildThermostatSetpoint(deviceId, agentId, partnerDeviceId, setpointDisplay, effectiveMode),
    );
  }

  async mediaCommand(deviceId: string, agentId: string, partnerDeviceId: string, command: string): Promise<codec.DeviceState> {
    return this.update(deviceId, codec.buildMediaCommand(deviceId, agentId, partnerDeviceId, command));
  }

  async listAutomations(): Promise<codec.Automation[]> {
    const structureId = (await this.fetchHomeGraph()).homes[0]?.id;
    if (structureId == null) throw new Error('No structure/home found; cannot list automations.');
    const resp = await this.rpc(AUTOMATION_SERVICE, 'ListAutomations', codec.buildListAutomationsRequest(structureId));
    return codec.parseListAutomations(resp);
  }

  async runAutomation(automation: codec.Automation): Promise<boolean> {
    if (!automation.manuallyRunnable) {
      throw new Error(
        `Automation '${automation.name}' is condition/schedule-triggered and can't be started on demand.`,
      );
    }
    const structureId = (await this.fetchHomeGraph()).homes[0]?.id;
    if (structureId == null) throw new Error('No structure/home found; cannot run automation.');
    const resp = await this.rpc(
      AUTOMATION_SERVICE,
      'ExecuteAutomation',
      codec.buildExecuteAutomationRequest(structureId, automation.id),
    );
    return Array.isArray(resp) && resp.length > 0 && String(resp[0]) === '1';
  }

  // Internals -----------------------------------------------------------------------------------

  private async update(deviceId: string, body: codec.JsonArr, fallbackOnOff?: boolean): Promise<codec.DeviceState> {
    const resp = await this.rpc(HOME_CONTROL_SERVICE, 'UpdateTraits', body);
    return this.stateFrom(resp, deviceId, fallbackOnOff);
  }

  private async stateFrom(
    resp: unknown,
    deviceId: string,
    fallbackOnOff?: boolean,
    fallbackLocked?: boolean,
  ): Promise<codec.DeviceState> {
    const echoed = codec.parseGetTraits(resp).find((s) => s.id === deviceId);
    if (echoed) return echoed;
    const readback = (await this.getTraits([deviceId])).find((s) => s.id === deviceId);
    if (readback) return readback;
    return {
      id: deviceId,
      online: true,
      onOff: fallbackOnOff ?? null,
      brightnessPct: null,
      colorTemperatureK: null,
      volumePct: null,
      muted: null,
      locked: fallbackLocked ?? null,
      jammed: null,
      thermostatMode: null,
      setpointC: null,
      setpointF: null,
      ambientC: null,
      temperatureUnit: null,
    };
  }

  /** POSTs body to `<BASE><service>/<method>`; on 401/403 invalidates + retries once. */
  private async rpc(service: string, method: string, body: codec.JsonArr): Promise<unknown> {
    const url = `${BASE}${service}/${method}`;
    const bodyJson = JSON.stringify(body);

    let response = await this.post(url, bodyJson, await this.auth.bearer());
    if (response.status === 401 || response.status === 403) {
      this.auth.invalidate();
      response = await this.post(url, bodyJson, await this.auth.bearer());
    }
    if (response.status < 200 || response.status > 299) {
      throw new FoyerHttpError(
        response.status,
        `foyer ${service}/${method} -> HTTP ${response.status}: ${response.body.substring(0, 300)}`,
      );
    }
    return JSON.parse(stripXssiPrefix(response.body));
  }

  private async post(url: string, bodyJson: string, bearer: string): Promise<{ status: number; body: string }> {
    const resp = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${bearer}`,
        'X-User-Agent': 'grpc-web-javascript/0.1',
        'Content-Type': CONTENT_TYPE,
      },
      body: bodyJson,
    });
    return { status: resp.status, body: await resp.text() };
  }
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, n));
}
