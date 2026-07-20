import { describe, expect, it } from 'vitest';
import * as gps from '../src/gpsoauth';

// Mirrors the Kotlin GpsOAuthCodec: form field names + Key=Value parsing must be exact, or Google's
// picky /auth endpoint rejects the request.

describe('gpsoauth form building', () => {
  it('ac2dm exchange form has the master-token exchange fields + GMS client sig', () => {
    const form = gps.buildExchangeForm('OAUTH_TOKEN', 'abcd1234abcd1234');
    expect(form['service']).toBe('ac2dm');
    expect(form['Token']).toBe('OAUTH_TOKEN');
    expect(form['androidId']).toBe('abcd1234abcd1234');
    expect(form['ACCESS_TOKEN']).toBe('1');
    expect(form['add_account']).toBe('1');
    expect(form['client_sig']).toBe(gps.GMS_CLIENT_SIG);
    expect(form['callerSig']).toBe(gps.GMS_CLIENT_SIG);
    expect(form['accountType']).toBe('HOSTED_OR_GOOGLE');
  });

  it('getAuthToken form carries the master token verbatim in EncryptedPasswd', () => {
    const form = gps.buildAuthTokenForm(
      'aas_et/MASTER',
      'abcd1234abcd1234',
      'oauth2:https://www.googleapis.com/auth/homegraph',
      'com.google.android.apps.chromecast.app',
      '24bb24c05e47e0aefa68a58a766179d9b613a600',
    );
    expect(form['EncryptedPasswd']).toBe('aas_et/MASTER');
    expect(form['service']).toBe('oauth2:https://www.googleapis.com/auth/homegraph');
    expect(form['app']).toBe('com.google.android.apps.chromecast.app');
    expect(form['client_sig']).toBe('24bb24c05e47e0aefa68a58a766179d9b613a600');
    expect(form['has_permission']).toBe('1');
  });

  it('formEncode percent-encodes the scope colon/slashes', () => {
    const enc = gps.formEncode({ service: 'oauth2:https://x/y' });
    expect(enc).toBe('service=oauth2%3Ahttps%3A%2F%2Fx%2Fy');
  });

  it('parseAuthResponse handles values containing =', () => {
    const parsed = gps.parseAuthResponse('Auth=ya29.abc=def\nExpiry=1700000000\nEmail=owner@example.com\n');
    expect(parsed['Auth']).toBe('ya29.abc=def');
    expect(parsed['Expiry']).toBe('1700000000');
    expect(parsed['Email']).toBe('owner@example.com');
  });

  it('error messages surface the server Error field', () => {
    expect(gps.exchangeError({ Error: 'BadAuthentication' })).toContain('BadAuthentication');
    expect(gps.authTokenError({ Error: 'NeedsBrowser', ErrorDetail: 'foo' })).toContain('NeedsBrowser');
    expect(gps.authTokenError({ Error: 'NeedsBrowser', ErrorDetail: 'foo' })).toContain('(foo)');
  });
});

describe('GpsOAuth over a stubbed fetch', () => {
  it('exchangeOAuthToken returns the Token and Email', async () => {
    const original = globalThis.fetch;
    (globalThis as unknown as { fetch: unknown }).fetch = async () => ({
      status: 200,
      ok: true,
      async text() {
        return 'Token=aas_et/NEWMASTER\nEmail=me@example.com\n';
      },
      async json() {
        return {};
      },
    });
    try {
      const result = await new gps.GpsOAuth().exchangeOAuthToken('OAUTH', 'abcd1234abcd1234');
      expect(result.masterToken).toBe('aas_et/NEWMASTER');
      expect(result.email).toBe('me@example.com');
    } finally {
      (globalThis as unknown as { fetch: unknown }).fetch = original;
    }
  });

  it('getAuthToken parses Auth + Expiry', async () => {
    const original = globalThis.fetch;
    (globalThis as unknown as { fetch: unknown }).fetch = async () => ({
      status: 200,
      ok: true,
      async text() {
        return 'Auth=ya29.TOKEN\nExpiry=1800000000\n';
      },
      async json() {
        return {};
      },
    });
    try {
      const tok = await new gps.GpsOAuth().getAuthToken('aas_et/M', 'id', 'scope', 'app', 'sig');
      expect(tok.auth).toBe('ya29.TOKEN');
      expect(tok.expiryEpochSeconds).toBe(1800000000);
    } finally {
      (globalThis as unknown as { fetch: unknown }).fetch = original;
    }
  });
});
