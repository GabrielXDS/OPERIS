import {getStorage} from "firebase-admin/storage";
import {attachmentOperations, privateStorage} from "./attachments.js";
import {incidentOperations} from "./incidents.js";
import {recordOperations} from "./records.js";
import {initializeApp} from "firebase-admin/app";
import {getFirestore, FieldValue, Timestamp} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {onCall, HttpsError} from "firebase-functions/v2/https";
import {onDocumentCreated} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {setGlobalOptions} from "firebase-functions/v2";
import {uuid, teamIdentifier, cooling, expired, TTL_MS} from "./policy.js";

import {teamOperations, alertTargets, requireEnabled, requireActiveMembership, requireUid} from "./teams.js";
import {requireActiveDevice} from "./devices.js";
import {callableOptions} from "./app-check-policy.js";
import {releaseOperations} from "./releases.js";
import {pttOperations} from "./ptt.js";
import {shiftOperations} from "./shifts.js";
import {livekitSecrets, LIVEKIT_API_KEY, LIVEKIT_API_SECRET, LIVEKIT_URL, livekitLabSecrets, LIVEKIT_LAB_API_KEY, LIVEKIT_LAB_API_SECRET, LIVEKIT_LAB_URL} from "./ptt-secrets.js";

initializeApp();
setGlobalOptions({region:"southamerica-east1", maxInstances:20});
const db = getFirestore();
const options = callableOptions;
const operations = teamOperations(db);
const deviceCall = (handler, callOptions = options) => onCall(callOptions, async req => {
  if (req.data?.deviceId != null)
    await requireActiveDevice(db, {get: async ref => (await db.getAll(ref))[0]}, req, requireUid(req));
  return handler(req);
});
function uidOf(req) {
  return requireUid(req);
}
function checked(fn) {
  try { return fn(); } catch { throw new HttpsError("invalid-argument","Dados inválidos."); }
}
// Old enrollment cannot bypass the approval policy. Existing endpoint remains explicitly closed.
export const registerDevice = onCall(options, async req => {
  uidOf(req);
  throw new HttpsError("failed-precondition","Atualize o aplicativo para cadastrar o perfil e solicitar entrada.");
});

export const syncDevice = deviceCall(operations.sync);
export const upsertProfile = onCall(options, operations.profile);
export const createTeam = deviceCall(operations.create);
export const previewTeamInvite = deviceCall(operations.preview);
export const joinTeam = deviceCall(operations.join);
export const listMyTeams = deviceCall(operations.list);
export const getTeamDetails = deviceCall(operations.details);
export const setEmergencyContacts = deviceCall(operations.setEmergencyContacts);
export const updateMemberFunction = deviceCall(operations.updateMemberFunction);
export const setAvailability = deviceCall(operations.setStatus);
export const deactivateDevice = onCall(options, operations.deactivate);
export const selectActiveTeam = deviceCall(operations.selectTeam);
export const manageTeamInvite = deviceCall(operations.manageInvite);
export const dissolveTeam = deviceCall(operations.dissolve);
export const reviewMembership = deviceCall(operations.review);
export const removeTeamMember = deviceCall(operations.removeMember);

// Release policy is served from config/release (server-editable without a new APK).
// The client caches exactly the returned fields; blocking decisions use fresh data or a valid cache.
export const getAndroidRelease = onCall(options, releaseOperations(db).getRelease);

// Walkie-talkie (PTT) over LiveKit Cloud. Credentials live only in secrets and
// are read lazily per invocation. Floor leases expire server-side automatically.
const ptt = pttOperations({db, apiKey: () => LIVEKIT_API_KEY.value(), apiSecret: () => LIVEKIT_API_SECRET.value(), serverUrl: () => LIVEKIT_URL.value()});
const pttOptions = {...options, secrets: [...livekitSecrets]};
export const getPttAccess = deviceCall(ptt.access, pttOptions);
const pttLab = pttOperations({db, apiKey: () => LIVEKIT_LAB_API_KEY.value(), apiSecret: () => LIVEKIT_LAB_API_SECRET.value(), serverUrl: () => LIVEKIT_LAB_URL.value(), enforceDuty: false});
const pttLabOptions = {...options, secrets: [...livekitLabSecrets]};
export const getPttAccessLab = deviceCall(pttLab.access, pttLabOptions);
export const requestPttFloor = deviceCall(ptt.request, pttOptions);
export const renewPttFloor = deviceCall(ptt.renew, pttOptions);
export const releasePttFloor = deviceCall(ptt.release, pttOptions);
export const requestPttFloorLab = deviceCall(pttLab.request, pttLabOptions);
export const renewPttFloorLab = deviceCall(pttLab.renew, pttLabOptions);
export const releasePttFloorLab = deviceCall(pttLab.release, pttLabOptions);
export const getPttAccessLabV2 = deviceCall(pttLab.access, pttLabOptions);
export const requestPttFloorLabV2 = deviceCall(pttLab.request, pttLabOptions);
export const renewPttFloorLabV2 = deviceCall(pttLab.renew, pttLabOptions);
export const releasePttFloorLabV2 = deviceCall(pttLab.release, pttLabOptions);

export const triggerAlert = onCall(options, async req => {
  const uid = uidOf(req);
  const alertId = checked(() => uuid(req.data?.alertId));
  return db.runTransaction(async tx => {
    const teamId = checked(()=>teamIdentifier(req.data?.teamId));
    const [team,membership,user] = await tx.getAll(db.doc("teams/"+teamId),
      db.doc("teams/"+teamId+"/members/"+uid),db.doc("users/"+uid));
    requireEnabled(team);requireActiveMembership(membership);const d=await requireActiveDevice(db,tx,req,uid);
    const alertRef = db.doc("alerts/" + alertId);
    const previous = await tx.get(alertRef);
    if (previous.exists) {
      if (previous.data().senderDeviceId !== d.id || previous.data().teamId !== teamId)
        throw new HttpsError("permission-denied","Identificador em uso em outro envio.");
      return {alertId,accepted:true};
    }
    if (cooling(d.data.lastAlertAt?.toMillis(),Date.now())) throw new HttpsError("resource-exhausted","Aguarde 5 segundos.");
    tx.update(d.ref,{lastAlertAt:Timestamp.now()});
    tx.create(alertRef,{alertId,teamId,senderUid:uid,senderDeviceId:d.id,senderName:user.data().name,createdAt:Timestamp.now(),status:"queued"});
    return {alertId,accepted:true};
  });
});

// Outbox persistente: uma falha após a resposta HTTP não perde o envio.
// Eventos podem repetir; os receptores deduplicam por alertId.
export const distributeAlert = onDocumentCreated({document:"alerts/{alertId}",retry:true}, async event => {
  const snap = event.data;
  if (!snap) return;
  const a = snap.data();
  if (expired(a.createdAt.toMillis(),Date.now())) {
    await snap.ref.update({status:"expired"}); return;
  }
  const team = await db.doc("teams/" + a.teamId).get();
  if (!team.exists || team.data().enabled !== true || team.data().active === false) { await snap.ref.update({status:"cancelled"}); return; }
  const targets = await alertTargets(db,a.teamId,a.senderUid || a.senderDeviceId);
  if (!targets.length) { await snap.ref.update({status:"no-recipients",acceptedByFcm:0}); return; }
  const result = await getMessaging().sendEachForMulticast({
    tokens:targets.map(d=>d.fcmToken),
    android:{priority:"high",ttl:Math.max(0,TTL_MS-(Date.now()-a.createdAt.toMillis()))},
    data:{type:"PANIC_ALERT",alertId:a.alertId,teamId:a.teamId,senderDeviceId:a.senderDeviceId,senderName:a.senderName,timestamp:String(a.createdAt.toMillis())}
  });
  let transient = false;
  await Promise.all(result.responses.map(async (r,i) => {
    if (r.success) return;
    const code = r.error?.code;
    if (["messaging/registration-token-not-registered","messaging/invalid-registration-token"].includes(code)) {
      await db.runTransaction(async tx => {
        const current = await tx.get(targets[i].ref);
        if (current.exists && current.data().fcmToken === targets[i].fcmToken) tx.update(targets[i].ref,{fcmToken:FieldValue.delete()});
      });
    } else transient = true;
  }));
  await snap.ref.update({status:transient?"retrying":"submitted",acceptedByFcm:result.successCount,failed:result.failureCount});
  if (transient) throw new Error("Falha FCM; repetir enquanto alerta estiver válido.");
});

const incidentHandlers = incidentOperations(db);
export const createIncident = deviceCall(incidentHandlers.createIncident);
export const listIncidents = deviceCall(incidentHandlers.listIncidents);
export const getIncidentDetails = deviceCall(incidentHandlers.getIncidentDetails);
export const shareIncident = deviceCall(incidentHandlers.shareIncident);
export const updateIncident = deviceCall(incidentHandlers.updateIncident);
export const softDeleteIncident = deviceCall(incidentHandlers.softDeleteIncident);
export const createRoundReport = deviceCall(incidentHandlers.createRoundReport);
export const listRoundReports = deviceCall(incidentHandlers.listRoundReports);
export const getRoundReportDetails = deviceCall(incidentHandlers.getRoundReportDetails);
export const shareRoundReport = deviceCall(incidentHandlers.shareRoundReport);
export const updateRoundReport = deviceCall(incidentHandlers.updateRoundReport);
export const softDeleteRoundReport = deviceCall(incidentHandlers.softDeleteRoundReport);

const attachments = attachmentOperations(db, privateStorage(() => getStorage().bucket()));
export const requestAttachmentUpload = deviceCall(attachments.requestAttachmentUpload);
export const finalizeAttachment = deviceCall(attachments.finalizeAttachment);
export const requestAttachmentDownload = deviceCall(attachments.requestAttachmentDownload);

// Registros (Ocorrências e Rondas): aviso FCM na criação e contadores de não lidos.
const recordHandlers = recordOperations(db);
export const notifyIncidentCreated = onDocumentCreated({document:"incidents/{incidentId}",retry:true}, recordHandlers.onIncidentCreated);
export const notifyRoundReportCreated = onDocumentCreated({document:"roundReports/{reportId}",retry:true}, recordHandlers.onRoundReportCreated);
export const unreadIncidents = deviceCall(recordHandlers.unreadIncidents);
export const unreadRounds = deviceCall(recordHandlers.unreadRounds);
export const unreadState = deviceCall(recordHandlers.unreadState);
export const markRecordsSeen = deviceCall(recordHandlers.markRecordsSeen);

const shifts = shiftOperations(db);
export const startShift = deviceCall(shifts.start);
export const notifyShiftOpened = onDocumentCreated({document:"shifts/{shiftId}",retry:true}, async event=>{
  if(!event.data)return null;
  return shifts.notifyOpened(event.data);
});

export const getActiveShift = deviceCall(shifts.current);
export const listShifts = deviceCall(shifts.list);
export const getShiftReport = deviceCall(shifts.report);
export const addIntermediateBrigadista = deviceCall(shifts.addIntermediate);
export const confirmSecurityPosts = deviceCall(shifts.confirmSecurityPosts);
export const updateAgpPost = deviceCall(shifts.updatePost);
export const startAgpCoverage = deviceCall(shifts.startCoverage);
export const finishAgpCoverage = deviceCall(shifts.finishCoverage);
export const finishShift = deviceCall(shifts.finish);
export const notifyNightShiftStart = onSchedule({schedule:"0 19 * * *",timeZone:"America/Sao_Paulo",region:"southamerica-east1"}, async()=>shifts.notifyShiftBoundary("START"));
export const autoCloseNightShifts = onSchedule({schedule:"0 7 * * *",timeZone:"America/Sao_Paulo",region:"southamerica-east1"}, async()=>{
  const closeResult=await shifts.autoCloseExpired();
  const notifyResult=await shifts.notifyShiftBoundary("END");
  return {closeResult,notifyResult};
});

