// 设备管理：
//   npm run add-device -- <deviceId> [备注]     生成激活码（只显示一次）
//   npm run add-device -- --disable <deviceId>  禁用设备（已签发令牌随即失效）
//   npm run add-device -- --enable <deviceId>
import { randomBytes } from 'node:crypto';
import { DeviceRegistry } from '../src/devices.js';

const file = process.env.DEVICES_FILE || './data/devices.json';
const registry = new DeviceRegistry(file);
const [first, second, ...rest] = process.argv.slice(2);

if (first === '--disable' || first === '--enable') {
  registry.setDisabled(second, first === '--disable');
  console.log(`device ${second} ${first === '--disable' ? 'disabled' : 'enabled'}`);
} else if (first && /^[A-Za-z0-9_-]{3,64}$/.test(first)) {
  const activationCode = randomBytes(18).toString('base64url');
  registry.add({ id: first, activationCode, note: [second, ...rest].filter(Boolean).join(' ') });
  console.log(`deviceId:       ${first}`);
  console.log(`activationCode: ${activationCode}`);
  console.log('激活码只显示这一次，请填入 App 设置页。');
} else {
  console.error('usage: npm run add-device -- <deviceId 3-64 位字母数字_-> [note] | --disable <id> | --enable <id>');
  process.exit(1);
}
