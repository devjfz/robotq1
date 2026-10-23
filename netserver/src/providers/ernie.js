import { createOpenAiCompatibleProvider } from './openaiCompatible.js';

/**
 * 文心大模型：千帆 ModelBuilder v2 接口，兼容 OpenAI 协议。
 * POST {baseUrl}/chat/completions，Authorization: Bearer bce-v3/...，可选 appid 头。
 * 文心特有差异在这里转换：输出长度字段为 max_completion_tokens。
 */
export function createErnieProvider({ apiKey, model, baseUrl, appId }) {
  const base = createOpenAiCompatibleProvider({
    name: 'ernie',
    baseUrl,
    apiKey,
    model,
    headers: appId ? { appid: appId } : {},
  });
  return {
    ...base,
    chat(body, opts) {
      const { max_tokens, ...rest } = body;
      return base.chat(max_tokens ? { ...rest, max_completion_tokens: max_tokens } : rest, opts);
    },
  };
}
