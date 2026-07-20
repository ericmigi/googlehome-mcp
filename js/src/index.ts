// The JS-on-PKJS MCP plugin entrypoint. Defines `globalThis.mcp` per the ABI (js-mcp-spec.md):
//   listTools/getContext are STATIC (zero host calls — defer-auth);
//   callTool lazily signs in on the first networked tool;
//   signIn/signOut/authStatus manage the stored master token.
//
// Secrets (master_token, android_id, lock_pin) are read/written via coreHost.secretGet/secretSet,
// namespaced per install by the host. Network goes only through the host-provided fetch().

import { FoyerAuth, FoyerClient } from './foyer-client';
import { GpsOAuth } from './gpsoauth';
import {
  callServerTool,
  inputSchema,
  normalizePasscode,
  PasscodeStore,
  TOOLS,
  TOOL_NAMES,
} from './server';

const KEY_MASTER_TOKEN = 'master_token';
const KEY_ANDROID_ID = 'android_id';
const KEY_LOCK_PIN = 'lock_pin';
// Captured by the settings page (mcpSettings.signIn("google")) into the plugin's secret store.
const KEY_OAUTH_TOKEN = 'oauth_token';

export interface Mcp {
  listTools(): Promise<{ name: string; description: string; inputSchema: unknown }[]>;
  callTool(name: string, argsJson: string): Promise<string>;
  getContext(): Promise<string | null>;
  signIn(): Promise<boolean>;
  signOut(): Promise<boolean>;
  authStatus(): Promise<boolean>;
}

// -------------------------------------------------------------------------------------------------
// Secret-backed passcode store (lock_pin)
// -------------------------------------------------------------------------------------------------

const passcodeStore: PasscodeStore = {
  async get() {
    const v = await coreHost.secretGet(KEY_LOCK_PIN);
    return v != null && v !== '' ? v : null;
  },
  async set(pin: string) {
    await coreHost.secretSet(KEY_LOCK_PIN, pin);
  },
};

// -------------------------------------------------------------------------------------------------
// Lazy authenticated client
// -------------------------------------------------------------------------------------------------

let cachedFoyer: FoyerClient | null = null;

function newAndroidId(): string {
  let s = '';
  for (let i = 0; i < 16; i++) s += Math.floor(Math.random() * 16).toString(16);
  return s;
}

function clientFor(masterToken: string, androidId: string): FoyerClient {
  const oauth = new GpsOAuth();
  const auth = new FoyerAuth(masterToken, androidId, oauth);
  return new FoyerClient(auth);
}

/**
 * Resolve credentials. Every run after the first is two secretGets and no network. Sign-in itself
 * happens in the settings page (pkjs-2): the user taps "Sign in to Google", the host runs the pinned
 * cookie capture and stores the oauth_token. Here we consume that token once to mint the master token
 * (android_id persisted BEFORE minting). The plugin never opens a browser itself.
 */
async function bootstrap(): Promise<FoyerClient> {
  const storedMaster = notBlank(await coreHost.secretGet(KEY_MASTER_TOKEN));
  const storedAndroidId = notBlank(await coreHost.secretGet(KEY_ANDROID_ID));

  if (storedMaster != null) {
    return clientFor(storedMaster, storedAndroidId ?? '0123456789abcdef');
  }

  const oauthToken = notBlank(await coreHost.secretGet(KEY_OAUTH_TOKEN));
  if (oauthToken == null) {
    throw new Error('Not signed in. Open this plugin’s Settings and tap "Sign in to Google".');
  }

  // Persist the android id BEFORE minting, so a crash mid-exchange can't orphan the master token.
  let androidId = storedAndroidId;
  if (androidId == null) {
    androidId = newAndroidId();
    await coreHost.secretSet(KEY_ANDROID_ID, androidId);
  }

  const result = await new GpsOAuth().exchangeOAuthToken(oauthToken, androidId);
  await coreHost.secretSet(KEY_MASTER_TOKEN, result.masterToken);
  console.info('Google master token obtained and stored.');

  return clientFor(result.masterToken, androidId);
}

async function foyer(): Promise<FoyerClient> {
  if (cachedFoyer == null) cachedFoyer = await bootstrap();
  return cachedFoyer;
}

function notBlank(s: string | null): string | null {
  return s != null && s.trim() !== '' ? s : null;
}

/** Mask credential-shaped substrings so a tool error can't leak a token back to the model. */
function redactSecrets(s: string): string {
  return s
    .replace(/aas_et\/[\w./+=-]+/g, '<redacted>')
    .replace(/ya29\.[\w./+=-]+/g, '<redacted>')
    .replace(/bearer\s+[\w./+=-]+/gi, 'Bearer <redacted>')
    .replace(/(EncryptedPasswd|oauth_token|Token|ACCESS_TOKEN|Authorization|Auth)=[^&\s"]+/gi, '$1=<redacted>');
}

// -------------------------------------------------------------------------------------------------
// The MCP ABI object
// -------------------------------------------------------------------------------------------------

const mcp: Mcp = {
  // STATIC: built from the registry, zero host calls (defer-auth).
  async listTools() {
    return TOOLS.map((t) => ({ name: t.name, description: t.description, inputSchema: inputSchema(t) }));
  },

  async callTool(name: string, argsJson: string): Promise<string> {
    try {
      const args = argsJson == null || argsJson.trim() === '' ? {} : JSON.parse(argsJson);

      // Saving the passcode is pure secret storage — handle BEFORE bootstrap so it never triggers a
      // Google sign-in (a user may save a PIN before ever signing in).
      if (name === 'set_passcode') {
        const pin = normalizePasscode(String((args as Record<string, unknown>)['passcode'] ?? ''));
        await passcodeStore.set(pin);
        return JSON.stringify({ ok: true, saved: true, length: pin.length });
      }

      // Reject an unknown tool BEFORE bootstrap so a typo can't trigger sign-in.
      if (!TOOL_NAMES.has(name)) throw new Error(`Unknown tool: ${name}`);

      const result = await callServerTool(await foyer(), passcodeStore, name, args as Record<string, unknown>);
      return JSON.stringify(result);
    } catch (e) {
      return JSON.stringify({ ok: false, error: redactSecrets(e instanceof Error ? e.message : String(e)) });
    }
  },

  // STATIC: no device enumeration (that would force a sign-in every session).
  async getContext() {
    return (
      'Google Home device control. Call list_devices first to discover the names, rooms, and types ' +
      "the control tools' selectors can target. Reads are unrestricted; control acts on every " +
      'matching capable device. There is no add or delete surface.'
    );
  },

  async signIn() {
    cachedFoyer = await bootstrap();
    return true;
  },

  async signOut() {
    await coreHost.secretSet(KEY_MASTER_TOKEN, '');
    await coreHost.secretSet(KEY_ANDROID_ID, '');
    await coreHost.secretSet(KEY_LOCK_PIN, '');
    await coreHost.secretSet(KEY_OAUTH_TOKEN, '');
    cachedFoyer = null;
    return true;
  },

  async authStatus() {
    return notBlank(await coreHost.secretGet(KEY_MASTER_TOKEN)) != null;
  },
};

globalThis.mcp = mcp;
