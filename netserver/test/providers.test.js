import assert from 'node:assert/strict';
import { afterEach, describe, it, mock } from 'node:test';
import { createProvider } from '../src/providers/index.js';

describe('ernie provider', () => {
  afterEach(() => mock.restoreAll());

  it('calls qianfan v2 with bearer key, appid and max_completion_tokens', async () => {
    const calls = [];
    mock.method(globalThis, 'fetch', async (url, init) => {
      calls.push({ url, init });
      return new Response('{}', { status: 200 });
    });
    const p = createProvider({
      provider: 'ernie',
      ernie: { apiKey: 'bce-v3/test', model: 'ernie-4.5-turbo-32k', baseUrl: 'https://qianfan.baidubce.com/v2', appId: 'app-1' },
    });
    await p.chat({ messages: [{ role: 'user', content: 'hi' }], max_tokens: 100, stream: false });

    assert.equal(calls[0].url, 'https://qianfan.baidubce.com/v2/chat/completions');
    assert.equal(calls[0].init.headers.authorization, 'Bearer bce-v3/test');
    assert.equal(calls[0].init.headers.appid, 'app-1');
    const body = JSON.parse(calls[0].init.body);
    assert.equal(body.model, 'ernie-4.5-turbo-32k');
    assert.equal(body.max_completion_tokens, 100);
    assert.equal(body.max_tokens, undefined);
  });

  it('rejects unknown provider and missing key', () => {
    assert.throws(() => createProvider({ provider: 'foo' }), /unknown LLM_PROVIDER/);
    assert.throws(() => createProvider({ provider: 'ernie', ernie: { model: 'm', baseUrl: 'u' } }), /required/);
  });
});
