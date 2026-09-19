/**
 * Strips secrets and personal data from a URL before it enters an event.
 *
 * `context.url` and `context.referrer` are captured on every event, and query
 * strings routinely carry things that must never reach a provider: password
 * reset tokens, OAuth codes, magic-link tokens, email addresses, session ids.
 * Rather than trying to recognise every sensitive value, we drop parameters by
 * name — a denylist over names is predictable and cheap — and drop URL
 * credentials (`https://user:pass@host`) and anything that looks like an email.
 *
 * Mirrored server-side by `UrlSanitizer`, because a client-side scrub is a
 * courtesy and anyone can POST to the API directly.
 */
const SENSITIVE_PARAMS = [
  "token",
  "access_token",
  "id_token",
  "refresh_token",
  "auth",
  "authorization",
  "code",
  "state",
  "password",
  "pass",
  "pwd",
  "secret",
  "key",
  "api_key",
  "apikey",
  "signature",
  "sig",
  "session",
  "sessionid",
  "sid",
  "jwt",
  "otp",
  "email",
  "e-mail",
  "mail",
  "phone",
  "reset",
  "reset_token",
];

const EMAIL = /[^\s@/?#&=]+@[^\s@/?#&=]+\.[^\s@/?#&=]+/;

export function sanitizeUrl(raw: string | undefined): string | undefined {
  if (!raw) return raw;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    // Not a parseable absolute URL: keep only the part before any query,
    // which is where secrets live.
    return raw.split(/[?#]/)[0];
  }

  url.username = "";
  url.password = "";

  for (const name of [...url.searchParams.keys()]) {
    const normalised = name.toLowerCase();
    const value = url.searchParams.get(name) ?? "";
    if (SENSITIVE_PARAMS.includes(normalised) || normalised.endsWith("token") || EMAIL.test(value)) {
      url.searchParams.delete(name);
    }
  }

  // Fragments are client-only state and a common home for OAuth implicit-flow
  // tokens (#access_token=...). They are never needed for commerce context.
  url.hash = "";
  return url.toString();
}

export const SENSITIVE_URL_PARAMS: readonly string[] = SENSITIVE_PARAMS;
