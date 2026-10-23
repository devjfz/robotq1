const int = (v, def) => {
  if (v === undefined || v === '') return def;
  const n = Number.parseInt(v, 10);
  if (!Number.isFinite(n) || n <= 0) throw new Error(`invalid integer config: ${v}`);
  return n;
};

/** 从环境变量读取配置并校验；env 参数便于测试注入 */
export function loadConfig(env = process.env) {
  const cfg = {
    host: env.HOST || '127.0.0.1',
    port: int(env.PORT, 8787),
    tokenSecret: env.TOKEN_SECRET || '',
    accessTtlSec: int(env.ACCESS_TOKEN_TTL_SEC, 900),
    refreshTtlSec: int(env.REFRESH_TOKEN_TTL_SEC, 30 * 24 * 3600),
    devicesFile: env.DEVICES_FILE || './data/devices.json',
    provider: env.LLM_PROVIDER || 'ernie',
    ernie: {
      apiKey: env.ERNIE_API_KEY || '',
      model: env.ERNIE_MODEL || 'ernie-4.5-turbo-32k',
      baseUrl: env.ERNIE_BASE_URL || 'https://qianfan.baidubce.com/v2',
      appId: env.ERNIE_APP_ID || '',
    },
    openaiCompatible: {
      apiKey: env.OPENAI_COMPAT_API_KEY || '',
      model: env.OPENAI_COMPAT_MODEL || '',
      baseUrl: env.OPENAI_COMPAT_BASE_URL || '',
    },
    maxTokensCap: int(env.MAX_TOKENS_CAP, 512),
    ratePerMin: int(env.RATE_LIMIT_PER_MIN, 20),
    dailyLimit: int(env.DAILY_REQUEST_LIMIT, 500),
    upstreamTimeoutMs: int(env.UPSTREAM_TIMEOUT_MS, 30000),
  };
  if (cfg.tokenSecret.length < 32) {
    throw new Error('TOKEN_SECRET must be at least 32 characters (openssl rand -base64 48)');
  }
  return cfg;
}
