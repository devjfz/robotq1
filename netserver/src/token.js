import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';

const b64 = (buf) => Buffer.from(buf).toString('base64url');

/**
 * 轻量签名令牌：base64url(payload).base64url(HMAC-SHA256)。
 * payload: { typ: 'access'|'refresh', sub: deviceId, iat, exp, jti }
 */
export class TokenService {
  constructor(secret, { accessTtlSec, refreshTtlSec }, now = () => Date.now()) {
    this.secret = secret;
    this.accessTtlSec = accessTtlSec;
    this.refreshTtlSec = refreshTtlSec;
    this.now = now;
  }

  issuePair(deviceId) {
    return {
      accessToken: this.#sign('access', deviceId, this.accessTtlSec),
      accessExpiresIn: this.accessTtlSec,
      refreshToken: this.#sign('refresh', deviceId, this.refreshTtlSec),
      refreshExpiresIn: this.refreshTtlSec,
      tokenType: 'Bearer',
    };
  }

  /** 校验成功返回 payload，否则返回 null */
  verify(token, expectedType) {
    if (typeof token !== 'string') return null;
    const parts = token.split('.');
    if (parts.length !== 2) return null;
    const [body, sig] = parts;
    const expected = Buffer.from(this.#mac(body));
    const actual = Buffer.from(sig);
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) return null;
    let payload;
    try {
      payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    } catch {
      return null;
    }
    if (payload.typ !== expectedType) return null;
    if (typeof payload.exp !== 'number' || payload.exp * 1000 <= this.now()) return null;
    return payload;
  }

  #sign(typ, sub, ttlSec) {
    const iat = Math.floor(this.now() / 1000);
    const body = b64(JSON.stringify({ typ, sub, iat, exp: iat + ttlSec, jti: randomUUID() }));
    return `${body}.${this.#mac(body)}`;
  }

  #mac(body) {
    return createHmac('sha256', this.secret).update(body).digest('base64url');
  }
}
