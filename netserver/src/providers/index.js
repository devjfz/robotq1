import { createErnieProvider } from './ernie.js';
import { createOpenAiCompatibleProvider } from './openaiCompatible.js';

/**
 * 供应商注册表。新增供应商：实现 { name, model, chat(body, {signal}) => Response }，
 * 对外保持 OpenAI chat/completions 协议（非兼容厂商在适配器内部做请求/响应转换），然后在此注册。
 */
const factories = {
  ernie: (cfg) => createErnieProvider(cfg.ernie),
  openai_compatible: (cfg) => createOpenAiCompatibleProvider(cfg.openaiCompatible),
};

export function createProvider(cfg) {
  const factory = factories[cfg.provider];
  if (!factory) {
    throw new Error(`unknown LLM_PROVIDER "${cfg.provider}", expected one of: ${Object.keys(factories).join(', ')}`);
  }
  return factory(cfg);
}
