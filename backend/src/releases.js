import {HttpsError} from "firebase-functions/v2/https";
import {requireUid} from "./teams.js";

// Central release policy, editable at config/release without shipping a new APK.
// The endpoint publishes a fixed set of fields to clients; cache validity is a client concern.
// status: PUBLISHED (o único que oferece download), DRAFT e ANNOUNCED (não oferecem).
export const RELEASE_POLICY_DOC = "config/release";

const SHIPPED_POLICY = Object.freeze({
  latestVersionCode: 15,
  latestVersionName: "4.0",
  minSupportedVersionCode: 9,
  status: "DRAFT",
  downloadUrl: null,
  releaseNotes: Object.freeze([
    "Sons oficiais de PTT no Rádio (início, encerramento e erro)",
    "Círculo central restaurado no símbolo OPERIS (ícone e tela inicial)",
    "Notificações de novas Ocorrências e Rondas da equipe",
    "Contadores de não lidos em Ocorrências e Rondas",
    "Badge de registros não lidos na tela inicial e no menu",
    "Atalho pelos contadores para abrir os registros diretamente",
  ]),
  sha256: null,
  apkSize: null,
});

const PUBLISHED_STATUS = "PUBLISHED";

function safePositiveInt(value, fallback) {
  const n = typeof value === "number" ? value : NaN;
  return Number.isInteger(n) && n >= 1 ? n : fallback;
}
function safeSize(value) {
  const n = typeof value === "number" ? value : NaN;
  return Number.isInteger(n) && n >= 1 ? n : null;
}
function safeStatus(value, fallback) {
  if (value !== "PUBLISHED" && value !== "DRAFT" && value !== "ANNOUNCED") return fallback;
  return value;
}
function safeHttpsUrl(value) {
  if (typeof value !== "string" || value.trim().length === 0) return null;
  try {
    const u = new URL(value.trim());
    if (u.protocol !== "https:" || u.username || u.password || u.search || u.hash) return null;
    return u.toString();
  } catch {
    return null;
  }
}
function safeSha256(value) {
  if (typeof value !== "string") return null;
  const clean = value.trim().toLowerCase();
  return /^[0-9a-f]{64}$/.test(clean) ? clean : null;
}
function safeNotes(value, fallback) {
  if (!Array.isArray(value) || value.length === 0) return fallback;
  const notes = value
    .filter(v => typeof v === "string")
    .map(v => v.trim())
    .filter(v => v.length > 0 && v.length <= 300);
  return notes.length > 0 ? notes.slice(0, 20) : fallback;
}

export function releaseOperations(db) {
  const policyRef = db.doc(RELEASE_POLICY_DOC);
  function normalize(data) {
    const hasDoc = data && typeof data === "object";
    const src = hasDoc ? data : SHIPPED_POLICY;
    const latest = safePositiveInt(src.latestVersionCode, SHIPPED_POLICY.latestVersionCode);
    let min = safePositiveInt(src.minSupportedVersionCode, latest);
    if (min > latest) min = latest;
    let latestName = typeof src.latestVersionName === "string" ? src.latestVersionName.trim() : "";
    if (latestName.length === 0) latestName = SHIPPED_POLICY.latestVersionName;
    return {
      latestVersionCode: latest,
      latestVersionName: latestName.slice(0, 40),
      minSupportedVersionCode: min,
      status: safeStatus(src.status, hasDoc ? PUBLISHED_STATUS : SHIPPED_POLICY.status),
      downloadUrl: safeHttpsUrl(src.downloadUrl),
      releaseNotes: safeNotes(src.releaseNotes, SHIPPED_POLICY.releaseNotes),
      sha256: safeSha256(src.sha256),
      apkSize: safeSize(src.apkSize),
    };
  }
  return {
    normalize,
    async getRelease(req) {
      requireUid(req);
      try {
        const snap = await policyRef.get();
        if (!snap.exists) {
          throw new HttpsError("not-found", "Release não cadastrada.", {reason: "RELEASE_NOT_FOUND"});
        }
        const data = snap.data();
        if (!Number.isInteger(data.latestVersionCode) || data.latestVersionCode < 1 ||
            typeof data.latestVersionName !== "string" || !data.latestVersionName.trim()) {
          throw new HttpsError("failed-precondition", "Release inválida.", {reason: "RELEASE_INVALID"});
        }
        const policy = normalize(data);
        if (policy.status === PUBLISHED_STATUS && (!policy.downloadUrl || !policy.sha256 || !policy.apkSize ||
            !Array.isArray(data.releaseNotes) || !safeNotes(data.releaseNotes, []).length)) {
          throw new HttpsError("failed-precondition", "Pacote da release incompleto.", {reason: "RELEASE_INVALID"});
        }
        return policy;
      } catch (e) {
        if (e instanceof HttpsError) throw e;
        throw new HttpsError("internal", "Não foi possível consultar a política de atualização.");
      }
    },
  };
}
