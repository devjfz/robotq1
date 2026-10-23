import { createHash, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export const sha256 = (s) => createHash('sha256').update(s, 'utf8').digest('hex');

/**
 * 设备注册表，JSON 文件格式：
 * { "devices": [ { "id": "dev-001", "codeSha256": "...", "disabled": false, "note": "", "createdAt": "..." } ] }
 * 只保存激活码的 SHA-256，不保存明文。文件被修改（例如禁用设备）后自动重新加载。
 */
export class DeviceRegistry {
  constructor(file) {
    this.file = file;
    this.mtimeMs = -1;
    this.devices = new Map();
  }

  #reload() {
    if (!existsSync(this.file)) {
      this.devices = new Map();
      this.mtimeMs = -1;
      return;
    }
    const mtimeMs = statSync(this.file).mtimeMs;
    if (mtimeMs === this.mtimeMs) return;
    const data = JSON.parse(readFileSync(this.file, 'utf8'));
    this.devices = new Map((data.devices || []).map((d) => [d.id, d]));
    this.mtimeMs = mtimeMs;
  }

  /** 校验设备 ID + 激活码 */
  verifyActivation(deviceId, activationCode) {
    if (typeof deviceId !== 'string' || typeof activationCode !== 'string') return false;
    this.#reload();
    const d = this.devices.get(deviceId);
    if (!d || d.disabled) return false;
    const a = Buffer.from(sha256(activationCode));
    const b = Buffer.from(String(d.codeSha256));
    return a.length === b.length && timingSafeEqual(a, b);
  }

  isActive(deviceId) {
    this.#reload();
    const d = this.devices.get(deviceId);
    return Boolean(d && !d.disabled);
  }

  add({ id, activationCode, note = '' }) {
    this.#reload();
    if (this.devices.has(id)) throw new Error(`device already exists: ${id}`);
    this.devices.set(id, {
      id,
      codeSha256: sha256(activationCode),
      disabled: false,
      note,
      createdAt: new Date().toISOString(),
    });
    this.#save();
  }

  setDisabled(id, disabled) {
    this.#reload();
    const d = this.devices.get(id);
    if (!d) throw new Error(`device not found: ${id}`);
    d.disabled = disabled;
    this.#save();
  }

  #save() {
    mkdirSync(dirname(this.file), { recursive: true });
    writeFileSync(this.file, JSON.stringify({ devices: [...this.devices.values()] }, null, 2), { mode: 0o600 });
    this.mtimeMs = -1;
  }
}
