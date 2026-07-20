// Ambient globals the host bootstrap provides BEFORE index.js is evaled (see js-mcp-spec.md "JS
// plugin ABI"). These are the plugin's ONLY capabilities: no ambient network, fs, or DOM.

interface HostFetchInit {
  method?: string;
  headers?: Record<string, string>;
  body?: string;
}

interface HostFetchResponse {
  status: number;
  ok: boolean;
  text(): Promise<string>;
  json(): Promise<unknown>;
}

interface CoreHost {
  /** Namespaced per install. Resolves to null when this MCP has never stored `key`. */
  secretGet(key: string): Promise<string | null>;
  secretSet(key: string, value: string): Promise<void>;
  /** Runs the manifest-pinned auth strategy; resolves to the captured value, or null if cancelled. */
  browserAuth(): Promise<string | null>;
  getLocation?(): Promise<{ lat: number; lon: number; accuracy_m: number } | null>;
}

declare global {
  // The ONLY network path; the manifest allow-list + auth injection are enforced host-side.
  function fetch(url: string, init?: HostFetchInit): Promise<HostFetchResponse>;
  const coreHost: CoreHost;
  const console: {
    log(...args: unknown[]): void;
    info(...args: unknown[]): void;
    warn(...args: unknown[]): void;
    error(...args: unknown[]): void;
  };
  // eslint-disable-next-line no-var
  var mcp: import('./index').Mcp;
}

export {};
