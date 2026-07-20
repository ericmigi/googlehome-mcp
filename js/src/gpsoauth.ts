// gpsoauth protocol against `https://android.clients.google.com/auth`.
//
// The endpoint speaks a picky, non-JSON protocol:
//   - Request body is `application/x-www-form-urlencoded`.
//   - Response is newline-delimited `Key=Value` text (parsed by parseAuthResponse).
// `EncryptedPasswd` carries the master token verbatim (nothing is encrypted).
// Ported from the Kotlin `GpsOAuthCodec` + `GpsOAuthClient`.

export const AUTH_URL = 'https://android.clients.google.com/auth';
export const DEFAULT_ANDROID_ID = '0123456789abcdef';
// The master token identifies the account, so any syntactically-valid Email works.
export const PLACEHOLDER_EMAIL = 'owner@example.com';
export const PLAY_SERVICES_VERSION = '240913000';
export const AC2DM_SERVICE = 'ac2dm';
// GMS core signing cert used for the ac2dm master-token exchange (not the per-app chromecast sig).
export const GMS_CLIENT_SIG = '38918a453d07199354f8b19af05ec6562ced5788';

export interface AuthToken {
  auth: string;
  expiryEpochSeconds: number | null;
}

export interface MasterTokenResult {
  masterToken: string;
  email: string | null;
}

/** ac2dm oauth_token -> master token exchange form. androidId must match the later getAuthToken. */
export function buildExchangeForm(
  oauthToken: string,
  androidId: string,
  email: string = PLACEHOLDER_EMAIL,
): Record<string, string> {
  return {
    accountType: 'HOSTED_OR_GOOGLE',
    Email: email,
    has_permission: '1',
    add_account: '1',
    ACCESS_TOKEN: '1',
    Token: oauthToken,
    service: AC2DM_SERVICE,
    source: 'android',
    androidId,
    device_country: 'us',
    operatorCountry: 'us',
    lang: 'en',
    sdk_version: '17',
    google_play_services_version: PLAY_SERVICES_VERSION,
    client_sig: GMS_CLIENT_SIG,
    callerSig: GMS_CLIENT_SIG,
    droidguard_results: 'dummy123',
  };
}

/** getAuthToken form: masterToken -> a short-lived bearer scoped to `service`. */
export function buildAuthTokenForm(
  masterToken: string,
  androidId: string,
  service: string,
  app: string,
  clientSig: string,
  email: string = PLACEHOLDER_EMAIL,
): Record<string, string> {
  return {
    accountType: 'HOSTED_OR_GOOGLE',
    Email: email,
    has_permission: '1',
    // Despite the name, this carries the master token verbatim (no encryption).
    EncryptedPasswd: masterToken,
    service,
    source: 'android',
    androidId,
    app,
    client_sig: clientSig,
    device_country: 'us',
    operatorCountry: 'us',
    lang: 'en',
    sdk_version: '17',
    google_play_services_version: PLAY_SERVICES_VERSION,
  };
}

/** Parses the `Key=Value` newline-delimited auth response. Values may themselves contain `=`. */
export function parseAuthResponse(body: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const rawLine of body.split('\n')) {
    const line = rawLine.trim();
    if (line === '') continue;
    const idx = line.indexOf('=');
    if (idx < 0) continue;
    out[line.substring(0, idx)] = line.substring(idx + 1);
  }
  return out;
}

export function authTokenError(response: Record<string, string>): string {
  const error = response['Error'] ?? 'unknown';
  const detail = response['ErrorDetail'] ? ` (${response['ErrorDetail']})` : '';
  return `gpsoauth getAuthToken failed: ${error}${detail}`;
}

export function exchangeError(response: Record<string, string>): string {
  return `gpsoauth oauth_token exchange failed: ${response['Error'] ?? 'no Token in response'}`;
}

/** `application/x-www-form-urlencoded` body. */
export function formEncode(form: Record<string, string>): string {
  return Object.entries(form)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&');
}

/** The two gpsoauth exchanges over the host-provided `fetch`. */
export class GpsOAuth {
  async exchangeOAuthToken(oauthToken: string, androidId: string = DEFAULT_ANDROID_ID): Promise<MasterTokenResult> {
    const parsed = await this.post(buildExchangeForm(oauthToken, androidId));
    const token = parsed['Token'];
    if (token == null) throw new Error(exchangeError(parsed));
    return { masterToken: token, email: parsed['Email'] && parsed['Email'] !== '' ? parsed['Email'] : null };
  }

  async getAuthToken(
    masterToken: string,
    androidId: string,
    service: string,
    app: string,
    clientSig: string,
    email: string = PLACEHOLDER_EMAIL,
  ): Promise<AuthToken> {
    const parsed = await this.post(buildAuthTokenForm(masterToken, androidId, service, app, clientSig, email));
    const auth = parsed['Auth'];
    if (auth == null) throw new Error(authTokenError(parsed));
    const expiry = parsed['Expiry'] != null ? Number.parseInt(parsed['Expiry'], 10) : NaN;
    return { auth, expiryEpochSeconds: Number.isFinite(expiry) ? expiry : null };
  }

  private async post(form: Record<string, string>): Promise<Record<string, string>> {
    const resp = await fetch(AUTH_URL, {
      method: 'POST',
      headers: {
        'User-Agent': 'GoogleAuth/1.4',
        'Accept-Encoding': 'identity',
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: formEncode(form),
    });
    return parseAuthResponse(await resp.text());
  }
}
