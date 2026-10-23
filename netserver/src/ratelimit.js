/**
 * 每设备限流：滑动 1 分钟窗口 + 自然日配额（内存实现，单实例部署足够；多实例需换 Redis）。
 */
export class RateLimiter {
  constructor({ perMinute, perDay }, now = () => Date.now()) {
    this.perMinute = perMinute;
    this.perDay = perDay;
    this.now = now;
    this.minute = new Map(); // deviceId -> number[] 时间戳
    this.daily = new Map(); // deviceId -> { day, count }
  }

  /** 允许则计数并返回 { ok: true }，否则返回 { ok: false, retryAfterSec } */
  take(deviceId) {
    const now = this.now();
    const day = new Date(now).toISOString().slice(0, 10);

    const d = this.daily.get(deviceId);
    const dayCount = d && d.day === day ? d.count : 0;
    if (dayCount >= this.perDay) {
      const tomorrow = Date.parse(`${day}T00:00:00Z`) + 86_400_000;
      return { ok: false, retryAfterSec: Math.ceil((tomorrow - now) / 1000), reason: 'daily_quota' };
    }

    const stamps = (this.minute.get(deviceId) || []).filter((t) => now - t < 60_000);
    if (stamps.length >= this.perMinute) {
      this.minute.set(deviceId, stamps);
      return { ok: false, retryAfterSec: Math.ceil((60_000 - (now - stamps[0])) / 1000), reason: 'rate_limit' };
    }

    stamps.push(now);
    this.minute.set(deviceId, stamps);
    this.daily.set(deviceId, { day, count: dayCount + 1 });
    return { ok: true };
  }
}
