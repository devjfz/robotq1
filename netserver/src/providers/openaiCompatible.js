/**
 * 通用 OpenAI 兼容供应商（DeepSeek、通义 compatible-mode、vLLM 等）。
 * 所有供应商对网关暴露同一接口：
 *   name: string
 *   model: string
 *   chat(body, { signal }) => Promise<Response>   // 返回 OpenAI 协议的 fetch Response（流式或非流式）
 */
export function createOpenAiCompatibleProvider({ name = 'openai_compatible', baseUrl, apiKey, model, headers = {} }) {
  if (!baseUrl || !apiKey || !model) {
    throw new Error(`${name}: baseUrl, apiKey and model are required`);
  }
  const url = `${baseUrl.replace(/\/+$/, '')}/chat/completions`;
  return {
    name,
    model,
    chat(body, { signal } = {}) {
      return fetch(url, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          authorization: `Bearer ${apiKey}`,
          ...headers,
        },
        body: JSON.stringify({ ...body, model }),
        signal,
      });
    },
  };
}
