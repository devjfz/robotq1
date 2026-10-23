import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, describe, it } from 'node:test';
import { loadConfig } from '../src/config.js';
import { DeviceRegistry } from '../src/devices.js';
import { RateLimiter } from '../src/ratelimit.js';
import { createGateway } from '../src/server.js';
import { TokenService } from '../src/token.js';

const listen = (server) =>
  new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`)));

describe('gateway', () => {
  let upstream, gateway, base, dir, registry, lastUpstreamReq;

  before(async () => {
    // 模拟 OpenAI 兼容上游
    upstream = createServer(async (req, res) => {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      lastUpstreamReq = { headers: req.headers, body: JSON.parse(Buffer.concat(chunks).toString()) };
      if (lastUpstreamReq.body.messages.at(-1).content === 'fail') {
        res.writeHead(500).end('boom');
        return;
      }
      if (lastUpstreamReq.body.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write('data: {"choices":[{"delta":{"content":"你"}}]}\n\n');
        res.write('data: {"choices":[{"delta":{"content":"好"}}]}\n\n');
        res.end('data: [DONE]\n\n');
      } else {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: '你好' } }] }));
      }
    });
    const upstreamUrl = await listen(upstream);

    dir = mkdtempSync(join(tmpdir(), 'gw-'));
    const cfg = loadConfig({
      TOKEN_SECRET: 'x'.repeat(40),
      DEVICES_FILE: join(dir, 'devices.json'),
      LLM_PROVIDER: 'openai_compatible',
      OPENAI_COMPAT_BASE_URL: `${upstreamUrl}/v1`,
      OPENAI_COMPAT_API_KEY: 'sk-upstream-secret',
      OPENAI_COMPAT_MODEL: 'server-model',
      MAX_TOKENS_CAP: '256',
      RATE_LIMIT_PER_MIN: '5',
    });
    registry = new DeviceRegistry(cfg.devicesFile);
    registry.add({ id: 'dev-1', activationCode: 'code-1' });
    registry.add({ id: 'dev-2', activationCode: 'code-2' });
    gateway = createGateway(cfg, { log: () => {} });
    base = await listen(gateway);
  });

  after(() => {
    gateway.close();
    upstream.close();
    rmSync(dir, { recursive: true, force: true });
  });

  const post = (path, body, token) =>
    fetch(base + path, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    });
  const login = async (id, code) => (await post('/v1/auth/device', { deviceId: id, activationCode: code })).json();

  it('rejects wrong activation code', async () => {
    const r = await post('/v1/auth/device', { deviceId: 'dev-1', activationCode: 'nope' });
    assert.equal(r.status, 401);
  });

  it('rejects chat without token', async () => {
    const r = await post('/v1/chat/completions', { messages: [{ role: 'user', content: 'hi' }] });
    assert.equal(r.status, 401);
  });

  it('proxies non-stream chat, forces server model, caps max_tokens, hides upstream key', async () => {
    const t = await login('dev-1', 'code-1');
    assert.equal(t.tokenType, 'Bearer');
    const r = await post(
      '/v1/chat/completions',
      { model: 'expensive-model', max_tokens: 99999, messages: [{ role: 'user', content: 'hi', evil: 1 }] },
      t.accessToken,
    );
    assert.equal(r.status, 200);
    assert.equal((await r.json()).choices[0].message.content, '你好');
    assert.equal(lastUpstreamReq.body.model, 'server-model');
    assert.equal(lastUpstreamReq.body.max_tokens, 256);
    assert.equal(lastUpstreamReq.body.messages[0].evil, undefined);
    assert.equal(lastUpstreamReq.headers.authorization, 'Bearer sk-upstream-secret');
  });

  it('streams SSE through', async () => {
    const t = await login('dev-1', 'code-1');
    const r = await post('/v1/chat/completions', { stream: true, messages: [{ role: 'user', content: 'hi' }] }, t.accessToken);
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-type'), /text\/event-stream/);
    const text = await r.text();
    assert.match(text, /"你"/);
    assert.match(text, /\[DONE\]/);
  });

  it('maps upstream failure to 502 without leaking detail', async () => {
    const t = await login('dev-1', 'code-1');
    const r = await post('/v1/chat/completions', { messages: [{ role: 'user', content: 'fail' }] }, t.accessToken);
    assert.equal(r.status, 502);
    assert.doesNotMatch(await r.text(), /boom/);
  });

  it('refresh token issues new pair; access token cannot be used to refresh', async () => {
    const t = await login('dev-1', 'code-1');
    assert.equal((await post('/v1/auth/refresh', { refreshToken: t.accessToken })).status, 401);
    const r = await post('/v1/auth/refresh', { refreshToken: t.refreshToken });
    assert.equal(r.status, 200);
    assert.ok((await r.json()).accessToken);
  });

  it('disabled device is rejected immediately', async () => {
    const t = await login('dev-2', 'code-2');
    registry.setDisabled('dev-2', true);
    const r = await post('/v1/chat/completions', { messages: [{ role: 'user', content: 'hi' }] }, t.accessToken);
    assert.equal(r.status, 403);
    assert.equal((await post('/v1/auth/refresh', { refreshToken: t.refreshToken })).status, 403);
  });

  it('validates request body', async () => {
    const t = await login('dev-1', 'code-1');
    const r = await post('/v1/chat/completions', { messages: [{ role: 'hacker', content: 'x' }] }, t.accessToken);
    assert.equal(r.status, 400);
  });
});

describe('TokenService', () => {
  it('rejects tampered and expired tokens', () => {
    let now = 1_000_000;
    const ts = new TokenService('s'.repeat(40), { accessTtlSec: 60, refreshTtlSec: 120 }, () => now);
    const { accessToken } = ts.issuePair('dev');
    assert.equal(ts.verify(accessToken, 'access').sub, 'dev');
    const [body, sig] = accessToken.split('.');
    const forged = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(body, 'base64url')), sub: 'other' })).toString('base64url');
    assert.equal(ts.verify(`${forged}.${sig}`, 'access'), null);
    now += 61_000;
    assert.equal(ts.verify(accessToken, 'access'), null);
  });
});

describe('RateLimiter', () => {
  it('enforces per-minute and daily limits', () => {
    let now = Date.parse('2026-10-21T10:00:00Z');
    const rl = new RateLimiter({ perMinute: 2, perDay: 3 }, () => now);
    assert.ok(rl.take('d').ok);
    assert.ok(rl.take('d').ok);
    assert.equal(rl.take('d').reason, 'rate_limit');
    now += 61_000;
    assert.ok(rl.take('d').ok);
    assert.equal(rl.take('d').reason, 'daily_quota');
    now += 86_400_000;
    assert.ok(rl.take('d').ok);
  });
});
