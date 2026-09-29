import {HttpsError} from "firebase-functions/v2/https";

const valid = value => typeof value === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(value);

export function requestedDeviceId(req) {
  const id = req.data?.deviceId;
  if (id == null) return null; // v4.1: devices/{uid}
  if (!valid(id)) throw new HttpsError("invalid-argument", "Aparelho inválido.");
  return id;
}

export async function requireActiveDevice(db, tx, req, uid) {
  const requestedId = requestedDeviceId(req);
  if (requestedId == null) {
    const user = await tx.get(db.doc("users/" + uid));
    if (user.exists && user.data().activeDeviceId)
      throw new HttpsError("failed-precondition", "Este aparelho não é mais o dispositivo ativo. Atualize ou entre novamente.", "DEVICE_REPLACED");
  }
  const id = requestedId || uid;
  const snap = await tx.get(db.doc("devices/" + id));
  if (!snap.exists || snap.data().enabled !== true)
    throw new HttpsError("permission-denied", "Este aparelho não está ativo.");
  const data = snap.data();
  if (id !== uid && data.uid !== uid)
    throw new HttpsError("permission-denied", "Aparelho não pertence à conta.");
  return {id, ref: snap.ref, data, legacy: id === uid};
}
