import {HttpsError} from "firebase-functions/v2/https";
import {AccessToken, TrackSource} from "livekit-server-sdk";
import {createHash} from "node:crypto";
import {Timestamp} from "firebase-admin/firestore";
import {requireUid, requireEnabled, requireActiveMembership, validate} from "./teams.js";
import {requireActiveDevice} from "./devices.js";
import {requestedDeviceId} from "./devices.js";
import {teamIdentifier} from "./policy.js";

export const HEARTBEAT_MS = 4000;
export const LEASE_MS = 15000;
export const MAX_TRANSMISSION_MS = 60000;
export const TOKEN_TTL_SECONDS = 1800;
const DEFAULT_NAME = "Integrante";
export const PTT_CHANNELS = ["TODOS", "BRIGADA", "SEGURANCA"];

function requestedChannel(req, user, member=null) {
  const value=req.data?.channel;
  const channel=value==null || String(value).trim()==="" ? "TODOS" : String(value).trim().toUpperCase();
  if(!PTT_CHANNELS.includes(channel)) throw new HttpsError("invalid-argument","Canal de rádio inválido.");
  if(channel==="TODOS") return channel;
  const fn=String(member?.data?.()?.operationalFunction||user?.data()?.operationalFunction||"").trim().toUpperCase();
  const allowed=fn==="BRIGADISTA" ? "BRIGADA" : (["VIGILANTE","AGP"].includes(fn) ? "SEGURANCA" : null);
  if(channel!==allowed) throw new HttpsError("permission-denied","Sua função não possui acesso a este canal.");
  return channel;
}

export function pttRoomKey(teamId, channel="TODOS") {
  const scope=channel==="TODOS" ? "ptt:"+teamId : "ptt:"+teamId+":"+channel;
  return "operis_" + createHash("sha256").update(scope).digest("hex").slice(0, 24);
}

const millis = v => (typeof v === "number" ? v : v?.toMillis?.() ?? null);
const floorState = snap => {
  const d = snap.exists ? snap.data() : {};
  return {holderUid: d.holderUid ?? null, holderName: d.holderName ?? null, expiresAt: millis(d.expiresAt)};
};
const leaseExpired = (state, now) => !state.holderUid || !state.expiresAt || now >= state.expiresAt;
function dutyShiftId(member) {
  const d=member?.data?.()||{};
  const shiftId=typeof d.activeShiftId==="string"?d.activeShiftId.trim():"";
  if(d.onDuty!==true||!shiftId||shiftId.includes("/"))
    throw new HttpsError("failed-precondition","Assuma um plantão ativo para utilizar o rádio.");
  return shiftId;
}
function requireActiveDuty(member,shift,uid,teamId,nowMs) {
  const shiftId=dutyShiftId(member),d=shift?.exists?shift.data():null;
  const end=millis(d?.officialEndAt);
  const active=d&&d.teamId===teamId&&d.status==="ACTIVE"&&(!end||nowMs<end)&&
    (d.participants||[]).some(p=>p.uid===uid&&p.endedAt==null);
  if(!active)throw new HttpsError("failed-precondition","Seu plantão não está ativo para o rádio.");
  return shiftId;
}

export function pttOperations({db, apiKey, apiSecret, serverUrl, now = () => Date.now(), enforceDuty = true}) {
  async function authorize(uid, teamId, tx, req) {
    const team = await tx.get(db.doc("teams/" + teamId));
    requireEnabled(team);
    const member = await tx.get(db.doc("teams/" + teamId + "/members/" + uid));
    requireActiveMembership(member);
    await requireActiveDevice(db, tx, req, uid);
    if (enforceDuty) {
      const shiftId=dutyShiftId(member);
      const shift=await tx.get(db.doc("shifts/"+shiftId));
      requireActiveDuty(member,shift,uid,teamId,now());
    }
    return member;
  }

  function userDisplayName(user) {
    if (user?.exists && typeof user.data().name === "string") {
      const name = user.data().name.trim();
      if (name) return name;
    }
    return DEFAULT_NAME;
  }

  function hold(floorRef, tx, uid, name, leaseMs) {
    const expiresAt = Timestamp.fromMillis(now() + leaseMs);
    tx.set(floorRef, {holderUid: uid, holderName: name, acquiredAt: Timestamp.now(), heartbeatAt: Timestamp.now(), expiresAt});
  }

  // Token with publish restricted to the microphone. Client never publishes
  // camera or screen, so no egress/recording/transcription ever touches the room.
  async function access(req) {
    const uid = requireUid(req);
    const teamId = validate(() => teamIdentifier(req.data?.teamId));
    const [team, member, user] = await db.getAll(
      db.doc("teams/" + teamId),
      db.doc("teams/" + teamId + "/members/" + uid),
      db.doc("users/" + uid),
    );
    requireEnabled(team);
    requireActiveMembership(member);
    const device = await requireActiveDevice(db, {get: async ref => (await db.getAll(ref))[0]}, req, uid);
    if (enforceDuty) {
      const shiftId=dutyShiftId(member);
      const [shift]=await db.getAll(db.doc("shifts/"+shiftId));
      requireActiveDuty(member,shift,uid,teamId,now());
    }
    const channel = requestedChannel(req, user, member);

    const roomName = pttRoomKey(teamId, channel);
    const name = userDisplayName(user);
    const tokenIdentity = requestedDeviceId(req) ? uid + ":" + device.id : uid;
    const token = new AccessToken(apiKey(), apiSecret(), {identity: tokenIdentity, name, ttl: TOKEN_TTL_SECONDS});
    token.addGrant({
      roomJoin: true,
      room: roomName,
      canSubscribe: true,
      canPublish: true,
      canPublishSources: [TrackSource.MICROPHONE],
    });
    return {
      serverUrl: String(serverUrl()).replace(/\/+$/, ""),
      participantToken: await token.toJwt(),
      roomName,
      expiresInSeconds: TOKEN_TTL_SECONDS,
    };
  }

  async function request(req) {
    const uid = requireUid(req);
    const teamId = validate(() => teamIdentifier(req.data?.teamId));
    return db.runTransaction(async tx => {
      const member = await authorize(uid, teamId, tx, req);
      const user = await tx.get(db.doc("users/" + uid));
      const channel = requestedChannel(req, user, member);
      const floorRef = db.doc("pttFloors/" + pttRoomKey(teamId, channel));
      const floor = await tx.get(floorRef);
      const state = floorState(floor);
      if (leaseExpired(state, now())) {
        hold(floorRef, tx, uid, userDisplayName(user), LEASE_MS);
        return {granted: true};
      }
      if (state.holderUid === uid) return {granted: true};
      return {granted: false, holderName: state.holderName || DEFAULT_NAME};
    });
  }

  async function renew(req) {
    const uid = requireUid(req);
    const teamId = validate(() => teamIdentifier(req.data?.teamId));
    return db.runTransaction(async tx => {
      const member = await authorize(uid, teamId, tx, req);
      const user = await tx.get(db.doc("users/" + uid));
      const channel = requestedChannel(req, user, member);
      const floorRef = db.doc("pttFloors/" + pttRoomKey(teamId, channel));
      const floor = await tx.get(floorRef);
      const state = floorState(floor);
      if (leaseExpired(state, now())) {
        hold(floorRef, tx, uid, userDisplayName(user), LEASE_MS);
        return {granted: true};
      }
      if (state.holderUid === uid) {
        tx.update(floorRef, {heartbeatAt: Timestamp.now(), expiresAt: Timestamp.fromMillis(now() + LEASE_MS)});
        return {granted: true};
      }
      return {granted: false, holderName: state.holderName || DEFAULT_NAME};
    });
  }

  async function release(req) {
    const uid = requireUid(req);
    const teamId = validate(() => teamIdentifier(req.data?.teamId));
    return db.runTransaction(async tx => {
      const member = await authorize(uid, teamId, tx, req);
      const user = await tx.get(db.doc("users/" + uid));
      const channel = requestedChannel(req, user, member);
      const floorRef = db.doc("pttFloors/" + pttRoomKey(teamId, channel));
      const floor = await tx.get(floorRef);
      const state = floorState(floor);
      if (leaseExpired(state, now())) return {released: true};
      if (state.holderUid !== uid) return {released: false};
      tx.delete(floorRef);
      return {released: true};
    });
  }

  return {access, request, renew, release};
}
