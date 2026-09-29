export const TTL_MS = 60_000;
export function text(value, min, max, label) {
  if (typeof value !== "string" || value.trim().length < min || value.trim().length > max) {
    throw new Error("INVALID:" + label);
  }
  return value.trim();
}
export function uuid(value) {
  if (typeof value !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) throw new Error("INVALID:alertId");
  return value;
}
export function recipients(devices, sender) {
  return devices.filter(d => d.id !== sender && d.enabled === true && typeof d.fcmToken === "string" && d.fcmToken.length > 0);
}
export function cooling(last, now) { return typeof last === "number" && now - last < 5000; }
export function expired(created, now) { return now - created >= TTL_MS; }

export const ONLINE_MS = 180_000;
export const INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const MAX_TEAMS = 20;
export function normalizeInvite(value) {
  if (typeof value !== "string" || value.length > 32) throw new Error("INVALID:invite");
  const code = value.replace(/[\s-]/g, "").toUpperCase();
  if (code.length !== 8 || [...code].some(c => !INVITE_ALPHABET.includes(c))) throw new Error("INVALID:invite");
  return code;
}
export function cleanName(value, max = 40) {
  return text(value, 1, max, "name").replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim() || (() => { throw new Error("INVALID:name"); })();
}
export function teamIdentifier(value) {
  if (typeof value !== "string" || !/^[a-zA-Z0-9_-]{1,64}$/.test(value)) throw new Error("INVALID:team");
  return value;
}
export function availability(value, reason) {
  if (value === "available" && (reason == null || reason === "")) return {availability:value,pauseReason:null};
  if (value === "paused" && ["lunch","dinner","break","other"].includes(reason)) return {availability:value,pauseReason:reason};
  throw new Error("INVALID:status");
}
export function operationalStatus(lastSeen, status, now) {
  if (typeof lastSeen !== "number" || now - lastSeen > ONLINE_MS || lastSeen > now + 5000) return "offline";
  return status === "paused" ? "paused" : "online";
}
export function deviceReady(device) {
  return device?.enabled === true && typeof device.fcmToken === "string" && device.fcmToken.length >= 20 &&
    device.notificationsEnabled === true && device.channelReady === true && device.audioReady === true &&
    device.fullScreenIntentAllowed !== false;
}
