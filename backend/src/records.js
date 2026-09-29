import {FieldValue, Timestamp} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {HttpsError} from "firebase-functions/v2/https";
import {requireUid, requireEnabled, requireActiveMembership, validate} from "./teams.js";
import {teamIdentifier} from "./policy.js";
import {sectorForFunction, canView} from "./incidents.js";

// Registros (Ocorrências e Rondas): aviso FCM na criação e contadores de não lidos.
// Estado de leitura canônico fica no Firestore em users/{uid}/recordReadState/{teamId}
// (lastSeenIncidentAt / lastSeenRoundAt / updatedAt). O Android mantém SharedPreferences
// apenas como cache; o servidor é a fonte de verdade e restaura o estado após reinstalação.
//
// Idempotência dos triggers: retry:true redelivers o mesmo evento (at-least-once). Um
// ledger transacional em notificationEvents/{eventId} impede processamento concorrente e
// reenvio após conclusão. O lease concede tempo de processamento; expira automaticamente
// em falha/crash para permitir retry sem perder notificações. Janela residual de duplicata:
// processo morre após o envio FCM e antes de gravar completed (o cliente deduplica por recordId).
export const LEASE_MS = 90_000;
const ID_KEYS = Object.freeze({occurrence: "incidentId", round: "reportId"});
const DISABLED_TOKENS = ["messaging/registration-token-not-registered", "messaging/invalid-registration-token"];
const STATE_FIELD = Object.freeze({occurrence: "lastSeenIncidentAt", round: "lastSeenRoundAt"});
const COLLECTION_OF = Object.freeze({occurrence: "incidents", round: "roundReports"});

export function recordPayload(record, recordType, createdAtMs) {
  const idKey = ID_KEYS[recordType] ?? "recordId";
  return {
    type: "record_created",
    recordType: recordType === "round" ? "round" : "occurrence",
    recordId: String(record[idKey] ?? record.recordId ?? ""),
    teamId: String(record.teamId ?? ""),
    createdBy: String(record.authorUid ?? ""),
    title: String(record.title ?? "").slice(0, 80),
    createdAt: String(createdAtMs),
  };
}

const valueOf = v => (v && typeof v.toMillis === "function") ? v.toMillis() : (v ?? null);

export function recordOperations(db, options = {}) {
  const now = options.now ?? (() => Date.now());
  const firebaseMessaging = options.messaging ?? getMessaging;

  async function sectorTargets(teamId, senderUid, sector) {
    const members = await db.collection("teams/"+teamId+"/members").where("status","==","active").limit(100).get();
    const candidates = members.docs.filter(m => m.id !== senderUid && m.data().enabled === true);
    const rows = await Promise.all(candidates.map(async m => {
      const user = await db.doc("users/"+m.id).get();
      if (sector && sectorForFunction(String(m.data().operationalFunction||user.data()?.operationalFunction || "").toUpperCase()) !== sector) return null;
      const active = await db.collection("devices").where("uid","==",m.id).get();
      const d = active.docs.find(x => x.data().enabled === true && x.data().fcmToken) || await db.doc("devices/"+m.id).get();
      const data = d.data();
      return data?.enabled && data.fcmToken ? {id:d.id,fcmToken:data.fcmToken,ref:d.ref} : null;
    }));
    const tokens = new Set();
    return rows.filter(Boolean).filter(r => !tokens.has(r.fcmToken) && tokens.add(r.fcmToken));
  }

  function recordKey(event, snap) {
    return event?.id || "f:" + snap.ref.path;
  }

  async function claimLedger(eventId, meta) {
    const ref = db.doc("notificationEvents/" + eventId);
    const serverNow = now();
    return db.runTransaction(async tx => {
      const current = await tx.get(ref);
      if (current.exists) {
        const d = current.data();
        if (d.status === "completed") return {acquired: false, claimedAt: 0};
        if (d.status === "processing" && valueOf(d.leaseUntil) > serverNow) return {acquired: false, claimedAt: 0};
      }
      const base = current.exists ? current.data() : {};
      tx.set(ref, {
        eventId: eventId,
        recordType: meta.recordType,
        recordId: meta.recordId,
        teamId: meta.teamId,
        status: "processing",
        attempts: (base.attempts ?? 0) + 1,
        claimedAt: Timestamp.fromMillis(serverNow),
        leaseUntil: Timestamp.fromMillis(serverNow + LEASE_MS),
        createdAt: base.createdAt ?? Timestamp.fromMillis(serverNow),
        updatedAt: Timestamp.fromMillis(serverNow),
      });
      return {acquired: true, claimedAt: serverNow};
    });
  }

  async function completeLedger(eventId, claimedAt) {
    await db.runTransaction(async tx => {
      const ref = db.doc("notificationEvents/" + eventId);
      const current = await tx.get(ref);
      if (!current.exists) return;
      const d = current.data();
      if (d.status === "processing" && valueOf(d.claimedAt) === claimedAt) {
        tx.update(ref, {status: "completed", completedAt: Timestamp.now(), updatedAt: Timestamp.now()});
      }
    });
  }

  async function releaseLedger(eventId) {
    const ref = db.doc("notificationEvents/" + eventId);
    try {
      await ref.update({status: "retrying", leaseUntil: Timestamp.fromMillis(now() - 1000), updatedAt: Timestamp.now()});
    } catch { /* o retry decide o próximo claim */ }
  }

  async function resolveNoTargets(eventId, meta) {
    const ref = db.doc("notificationEvents/" + eventId);
    try {
      await ref.set({
        eventId,
        recordType: meta.recordType,
        recordId: meta.recordId,
        teamId: meta.teamId,
        status: "completed",
        attempts: 0,
        claimedAt: null,
        leaseUntil: null,
        completedAt: Timestamp.now(),
        updatedAt: Timestamp.now(),
      }, {merge: true});
    } catch { /* melhor esforço */ }
  }

  function notifyCreated(recordType) {
    return async event => {
      const snap = event.data;
      if (!snap) return;
      const record = snap.data();
      const teamId = record?.teamId, authorUid = record?.authorUid, title = record?.title;
      if (!teamId || !authorUid || typeof title !== "string" || title.trim().length === 0) return;
      const team = await db.doc("teams/" + teamId).get();
      if (!team.exists || team.data().enabled !== true || team.data().active === false) return;
      const eventId = recordKey(event, snap);
      const meta = {
        recordType: recordType === "round" ? "round" : "occurrence",
        recordId: String(record[ID_KEYS[recordType]] ?? record.recordId ?? snap.id ?? ""),
        teamId,
      };
      const sector = record.ownerSector || sectorForFunction(record.authorOperationalFunction);
      const targets = recordType === "round"
        ? await sectorTargets(teamId, authorUid, null)
        : (sector ? await sectorTargets(teamId, authorUid, sector) : []);
      if (!targets.length) {
        await resolveNoTargets(eventId, meta);
        return;
      }
      const createdAtMs = valueOf(record.createdAt) ?? now();
      const {acquired, claimedAt} = await claimLedger(eventId, meta);
      if (!acquired) return;
      const data = recordPayload(record, recordType, createdAtMs);
      try {
        const result = await firebaseMessaging().sendEachForMulticast({
          tokens: targets.map(d => d.fcmToken),
          android: {priority: "high"},
          data,
        });
        let transient = false;
        await Promise.all(result.responses.map(async (r, i) => {
          if (r.success) return;
          const code = r.error?.code;
          if (DISABLED_TOKENS.includes(code)) {
            await db.runTransaction(async tx => {
              const current = await tx.get(targets[i].ref);
              if (current.exists && current.data().fcmToken === targets[i].fcmToken)
                tx.update(targets[i].ref, {fcmToken: FieldValue.delete()});
            });
          } else {
            transient = true;
          }
        }));
        if (transient) throw new Error("Erro transitório no envio FCM; liberar lease para retry.");
        await completeLedger(eventId, claimedAt);
      } catch (e) {
        await releaseLedger(eventId);
        throw e;
      }
    };
  }

  async function requireRecordDevice(uid, legacyDevice, knownUser = null) {
    if (legacyDevice?.exists) return requireEnabled(legacyDevice);
    const user = knownUser ?? await db.doc("users/" + uid).get();
    const activeId = user.data()?.activeDeviceId;
    if (!activeId) return requireEnabled(legacyDevice);
    const active = await db.doc("devices/" + activeId).get();
    const data = requireEnabled(active);
    if (activeId !== uid && data.uid !== uid)
      throw new HttpsError("permission-denied", "Aparelho nao pertence a conta.");
    return data;
  }

  async function unreadCount(uid, teamId, collection, sinceMs) {
    const [team, member, device, user] = await db.getAll(
      db.doc("teams/" + teamId),
      db.doc("teams/" + teamId + "/members/" + uid),
      db.doc("devices/" + uid),
      db.doc("users/" + uid),
    );
    requireEnabled(team);
    requireActiveMembership(member);
    await requireRecordDevice(uid, device, user);
    const since = Timestamp.fromMillis(sinceMs);
    const role = member.data().role;
    if (role !== "owner" && role !== "admin") {
      const active = await db.collection(collection)
        .where("teamId", "==", teamId)
        .where("deleted", "==", false)
        .where("createdAt", ">", since)
        .get();
      return active.docs.filter(s => s.data().authorUid !== uid && canView(s.data(),uid,
        String(member.data().operationalFunction||user.data()?.operationalFunction||"").toUpperCase(),role,collection === "roundReports")).length;
    }
    const active = await db.collection(collection)
      .where("teamId", "==", teamId)
      .where("deleted", "==", false)
      .where("createdAt", ">", since)
      .count().get();
    const ownActive = await db.collection(collection)
      .where("teamId", "==", teamId)
      .where("authorUid", "==", uid)
      .where("deleted", "==", false)
      .where("createdAt", ">", since)
      .count().get();
    return Math.max(0, active.data().count - ownActive.data().count);
  }

  // Estado canônico agregado: conta a partir do lastSeen persistido no servidor para o
  // uid/teamId, sem depender de "since" vindo do cliente. Registros do próprio uid e
  // tombstones (deleted == true) nunca contam.
  async function unreadState(req) {
    const uid = requireUid(req);
    const teamId = validate(() => teamIdentifier(req.data?.teamId));
    const [team, member, device, user] = await db.getAll(
      db.doc("teams/" + teamId),
      db.doc("teams/" + teamId + "/members/" + uid),
      db.doc("devices/" + uid),
      db.doc("users/" + uid),
    );
    requireEnabled(team);
    const membership = requireActiveMembership(member);
    await requireRecordDevice(uid, device, user);
    const viewerSector = sectorForFunction(String(membership.operationalFunction||user.data()?.operationalFunction || "").toUpperCase());
    const stateDoc = await db.doc(`users/${uid}/recordReadState/${teamId}`).get();
    const state = stateDoc.exists ? stateDoc.data() : {};
    const lastSeenIncidentAt = valueOf(state.lastSeenIncidentAt);
    const lastSeenRoundAt = valueOf(state.lastSeenRoundAt);
    const [unreadIncidents, unreadRounds] = await Promise.all([
      unreadCount(uid, teamId, "incidents", lastSeenIncidentAt ?? 0),
      unreadCount(uid, teamId, "roundReports", lastSeenRoundAt ?? 0),
    ]);
    return {
      teamId,
      unreadIncidents,
      unreadRounds,
      totalUnread: unreadIncidents + unreadRounds,
      lastSeenIncidentAt,
      lastSeenRoundAt,
      serverNow: now(),
    };
  }

  // Marca como visto um módulo (Ocorrências ou Rondas) para uid/teamId.
  // seenThrough é o createdAt do registro mais recente efetivamente carregado; o servidor
  // limita ao tempo de servidor (nunca aceita futuro), mantém a monotonicidade (nunca
  // retrocede) e escreve SOMENTE o lastSeen do módulo informado. seenThrough <= 0 não
  // avança nada (lista vazia: nada é marcado indevidamente).
  async function markSeen(req) {
    const uid = requireUid(req);
    const teamId = validate(() => teamIdentifier(req.data?.teamId));
    const recordType = req.data?.recordType;
    if (recordType !== "occurrence" && recordType !== "round") {
      throw new HttpsError("invalid-argument", "Tipo de registro indisponível.");
    }
    const seenThrough = req.data?.seenThrough;
    const serverNow = now();
    let want = 0;
    if (seenThrough !== null && seenThrough !== undefined) {
      if (typeof seenThrough !== "number" || !Number.isFinite(seenThrough)) {
        throw new HttpsError("invalid-argument", "Confira os dados informados.");
      }
      want = Math.min(Math.max(0, seenThrough), serverNow);
    }
    const [team, member, device] = await db.getAll(
      db.doc("teams/" + teamId),
      db.doc("teams/" + teamId + "/members/" + uid),
      db.doc("devices/" + uid),
    );
    requireEnabled(team);
    requireActiveMembership(member);
    await requireRecordDevice(uid, device);
    const stateRef = db.doc(`users/${uid}/recordReadState/${teamId}`);
    const field = STATE_FIELD[recordType];
    await db.runTransaction(async tx => {
      const state = await tx.get(stateRef);
      const current = state.exists ? (valueOf(state.data()[field]) ?? 0) : 0;
      const target = Math.max(current, want);
      tx.set(stateRef, {[field]: Timestamp.fromMillis(target), updatedAt: Timestamp.now()}, {merge: true});
    });
    const after = await stateRef.get();
    const d = after.exists ? after.data() : {};
    return {
      teamId,
      marked: true,
      recordSeenType: recordType,
      lastSeenIncidentAt: valueOf(d.lastSeenIncidentAt),
      lastSeenRoundAt: valueOf(d.lastSeenRoundAt),
      serverNow,
    };
  }

  // Compatibilidade: callables legadas usadas por clientes antigos (fornecem "since").
  function unread(collection) {
    return async req => {
      const uid = requireUid(req);
      const teamId = validate(() => teamIdentifier(req.data?.teamId));
      const since = req.data?.since;
      if (typeof since !== "number" || !Number.isFinite(since) ||
        since < 0 || since > Date.now() + 30_000) {
        throw new HttpsError("invalid-argument", "Confira os dados informados.");
      }
      const count = await unreadCount(uid, teamId, collection, since);
      return {teamId, count};
    };
  }

  return {
    onIncidentCreated: notifyCreated("occurrence"),
    onRoundReportCreated: notifyCreated("round"),
    unreadIncidents: unread("incidents"),
    unreadRounds: unread("roundReports"),
    unreadState,
    markRecordsSeen: markSeen,
  };
}
