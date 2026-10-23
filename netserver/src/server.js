import { createServer } from 'node:http';
import { DeviceRegistry } from './devices.js';
import { createProvider } from './providers/index.js';
import { RateLimiter } from './ratelimit.js';
import { TokenService } from './token.js';

const MAX_BODY_BYTES = 256 * 1024;
const ROLES = new Set(['system', 'user', 'assistant', 'tool']);

class HttpError extends Error {
  constructor(status, code, message, headers = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.headers = headers;
  }
}

function sendJson(res, status, body, headers = {}) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers });
  res.end(JSON.stringify(body));
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new HttpError(413, 'body_too_large', 'request body too large');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
  } catch {
    throw new HttpError(400, 'invalid_json', 'request body is not valid JSON');
  }
}

/** 只放行白名单字段，模型由服务端决定，输出长度受上限约束 */
export function sanitizeChatBody(body, maxTokensCap) {
  const bad = (msg) => new HttpError(400, 'invalid_request', msg);
  if (!Array.isArray(body.messages) || body.messages.length === 0 || body.messages.length > 50) {
    throw bad('messages must be a non-empty array with at most 50 items');
  }
  const messages = body.messages.map((m) => {
    if (!m || !ROLES.has(m.role)) throw bad('invalid message role');
    if (m.content != null && typeof m.content !== 'string') throw bad('message content must be a string');
    if (m.content && m.content.length > 8000) throw bad('message content too long');
    const out = { role: m.role, content: m.content ?? '' };
    if (m.role === 'assistant' && Array.isArray(m.tool_calls)) out.tool_calls = m.tool_calls;
    if (m.role === 'tool') out.tool_call_id = String(m.tool_call_id ?? '');
    if (typeof m.name === 'string') out.name = m.name;
    return out;
  });

  const out = { messages, stream: body.stream === true };
  if (Array.isArray(body.tools)) {
    if (body.tools.length > 16) throw bad('at most 16 tools');
    out.tools = body.tools.map((t) => {
      if (t?.type !== 'function' || typeof t.function?.name !== 'string') throw bad('invalid tool');
      return { type: 'function', function: { name: t.function.name, description: t.function.description, parameters: t.function.parameters } };
    });
    if (body.tool_choice !== undefined) out.tool_choice = body.tool_choice;
  }
  if (typeof body.temperature === 'number') out.temperature = Math.min(Math.max(body.temperature, 0.01), 1.5);
  if (typeof body.top_p === 'number') out.top_p = Math.min(Math.max(body.top_p, 0.01), 1);
  const requested = Number.isInteger(body.max_tokens) && body.max_tokens > 0 ? body.max_tokens : maxTokensCap;
  out.max_tokens = Math.min(requested, maxTokensCap);
  return out;
}

export function createGateway(cfg, deps = {}) {
  const tokens = deps.tokens ?? new TokenService(cfg.tokenSecret, cfg);
  const devices = deps.devices ?? new DeviceRegistry(cfg.devicesFile);
  const limiter = deps.limiter ?? new RateLimiter({ perMinute: cfg.ratePerMin, perDay: cfg.dailyLimit });
  const provider = deps.provider ?? createProvider(cfg);
  const log = deps.log ?? ((o) => console.log(JSON.stringify({ t: new Date().toISOString(), ...o })));

  function authenticate(req) {
    const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
    const payload = m && tokens.verify(m[1], 'access');
    if (!payload) throw new HttpError(401, 'invalid_token', 'missing, invalid or expired access token');
    if (!devices.isActive(payload.sub)) throw new HttpError(403, 'device_disabled', 'device is disabled');
    return payload.sub;
  }

  async function handleDeviceAuth(req, res) {
    const { deviceId, activationCode } = await readJson(req);
    if (!devices.verifyActivation(deviceId, activationCode)) {
      throw new HttpError(401, 'invalid_credentials', 'invalid device id or activation code');
    }
    sendJson(res, 200, tokens.issuePair(deviceId));
    return deviceId;
  }

  async function handleRefresh(req, res) {
    const { refreshToken } = await readJson(req);
    const payload = tokens.verify(refreshToken, 'refresh');
    if (!payload) throw new HttpError(401, 'invalid_token', 'invalid or expired refresh token');
    if (!devices.isActive(payload.sub)) throw new HttpError(403, 'device_disabled', 'device is disabled');
    sendJson(res, 200, tokens.issuePair(payload.sub));
    return payload.sub;
  }

  async function handleChat(req, res) {
    const deviceId = authenticate(req);
    const quota = limiter.take(deviceId);
    if (!quota.ok) {
      throw new HttpError(429, quota.reason, 'too many requests', { 'retry-after': String(quota.retryAfterSec) });
    }
    const body = sanitizeChatBody(await readJson(req), cfg.maxTokensCap);

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), cfg.upstreamTimeoutMs);
    res.on('close', () => controller.abort());

    let upstream;
    try {
      upstream = await provider.chat(body, { signal: controller.signal });
    } catch (e) {
      clearTimeout(timer);
      throw new HttpError(502, 'upstream_unavailable', e.name === 'AbortError' ? 'upstream timeout' : 'upstream request failed');
    }
    clearTimeout(timer);

    if (!upstream.ok) {
      const detail = await upstream.text().catch(() => '');
      log({ level: 'warn', msg: 'upstream error', provider: provider.name, status: upstream.status, detail: detail.slice(0, 300) });
      const status = upstream.status === 429 ? 429 : 502;
      throw new HttpError(status, 'upstream_error', `upstream returned ${upstream.status}`);
    }

    if (body.stream) {
      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
        connection: 'keep-alive',
        'x-accel-buffering': 'no',
      });
      try {
        for await (const chunk of upstream.body) res.write(chunk);
      } catch {
        // 客户端断开或上游中断：直接结束
      }
      res.end();
    } else {
      sendJson(res, 200, await upstream.json());
    }
    return deviceId;
  }

  const routes = {
    'POST /v1/auth/device': handleDeviceAuth,
    'POST /v1/auth/refresh': handleRefresh,
    'POST /v1/chat/completions': handleChat,
  };

  return createServer(async (req, res) => {
    const started = Date.now();
    const path = (req.url || '/').split('?')[0];
    let deviceId;
    try {
      if (req.method === 'GET' && path === '/healthz') {
        sendJson(res, 200, { ok: true, provider: provider.name, model: provider.model });
        return;
      }
      const handler = routes[`${req.method} ${path}`];
      if (!handler) throw new HttpError(404, 'not_found', 'not found');
      deviceId = await handler(req, res);
    } catch (e) {
      const err = e instanceof HttpError ? e : new HttpError(500, 'internal_error', 'internal error');
      if (!(e instanceof HttpError)) log({ level: 'error', msg: e.message, stack: e.stack });
      if (!res.headersSent) sendJson(res, err.status, { error: { code: err.code, message: err.message } }, err.headers);
      else res.end();
    } finally {
      log({ level: 'info', method: req.method, path, status: res.statusCode, ms: Date.now() - started, deviceId });
    }
  });
}
