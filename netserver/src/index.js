import { loadConfig } from './config.js';
import { createGateway } from './server.js';

const cfg = loadConfig();
const server = createGateway(cfg);
server.listen(cfg.port, cfg.host, () => {
  console.log(`deskrobot gateway listening on http://${cfg.host}:${cfg.port} provider=${cfg.provider}`);
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => server.close(() => process.exit(0)));
}
