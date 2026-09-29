import test from "node:test";
import assert from "node:assert/strict";
import {Timestamp} from "firebase-admin/firestore";
import {inmem, fakeMessaging, okResps, failToken} from "./helpers/inmem.js";
import {recordOperations, recordPayload, LEASE_MS} from "../src/records.js";

const stamp = ms => ({toMillis: () => ms});
const rowData = e => (e && typeof e === "object" && Object.prototype.hasOwnProperty.call(e, "data") ? e.data : e);
const req = (data, uid = "u") => ({auth: {uid}, data});
const rejects = (promise, code) => assert.rejects(promise, e => e.code === code);

const T0 = 1_700_000_000_000;
const MIN = 60_000, HOUR = 3_600_000;

function baseSeed(teams = ["a"]) {
  const seed = {};
  for (const t of teams) {
    seed[`teams/${t}`] = {enabled: true, active: true};
    seed[`teams/${t}/members/u`] = {status: "active", enabled: true};
    seed[`teams/${t}/members/w`] = {status: "active", enabled: true};
    seed[`teams/${t}/members/z`] = {status: "active", enabled: true};
  }
  seed["devices/u"] = {enabled: true, fcmToken: "TOK_U"};
  seed["devices/w"] = {enabled: true, fcmToken: "TOK_W"};
  seed["devices/z"] = {enabled: true, fcmToken: "TOK_Z"};
  seed["users/u"] = {name: "U",operationalFunction:"BRIGADISTA"};
  seed["users/w"] = {name: "W",operationalFunction:"BRIGADISTA"};
  seed["users/z"] = {name: "Z",operationalFunction:"BRIGADISTA"};
  seed["users/v"] = {name: "V"};
  return seed;
}

function fixture(seed = baseSeed(), t = T0) {
  const store = new Map(Object.entries(seed));
  const db = inmem(store);
  let T = t;
  const now = () => T;
  const advance = ms => (T += ms);
  let handler = okResps;
  const messaging = fakeMessaging(payload => handler(payload));
  const ops = recordOperations(db, {now, messaging});
  const setSend = h => (handler = h);
  const event = path => {
    const ref = db.doc(path);
    const id = path.split("/").at(-1);
    return {id: "e-" + id, data: {id, exists: true, ref, data: () => store.get(path)}};
  };
  const incident = (id, overrides) => {
    store.set("incidents/" + id, {teamId: "a", ownerSector:"BRIGADA", deleted: false, authorUid: "w", incidentId: id,
      title: "Ocorrência " + id, createdAt: stamp(T - MIN), ...overrides});
    return event("incidents/" + id);
  };
  const round = (id, overrides) => {
    store.set("roundReports/" + id, {teamId: "a", ownerSector:"BRIGADA", deleted: false, authorUid: "w", reportId: id,
      title: "Ronda " + id, createdAt: stamp(T - MIN), ...overrides});
    return event("roundReports/" + id);
  };
  return {store, db, now, advance, ops, messaging, setSend, event, incident, round};
}

test("records: payload para ocorrência e ronda usa tipo/ids e limita título", () => {
  const base = {teamId: "a", authorUid: "u", createdAt: new Timestamp(0, 0), title: "T".repeat(200)};
  const occ = recordPayload({incidentId: "inc-1", ...base}, "occurrence", T0);
  assert.equal(occ.type, "record_created");
  assert.equal(occ.recordType, "occurrence");
  assert.equal(occ.recordId, "inc-1");
  assert.equal(occ.createdBy, "u");
  assert.equal(occ.createdAt, String(T0));
  assert.equal(occ.title.length, 80);
  const roundP = recordPayload({reportId: "rep-1", ...base}, "round", T0);
  assert.equal(roundP.recordType, "round");
  assert.equal(roundP.recordId, "rep-1");
});

test("records: legacy unread (com since do cliente) continua contando ativos menos próprios", async () => {
  const {store, ops} = fixture();
  store.set("incidents/r1", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "w", title: "1", createdAt: stamp(T0 - 10_000)});
  store.set("incidents/r2", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "u", title: "Minha", createdAt: stamp(T0 - 5_000)});
  store.set("incidents/r3", {teamId: "a", ownerSector: "BRIGADA", deleted: true, authorUid: "w", title: "Tomb", createdAt: stamp(T0 - 1_000)});
  const {count} = await ops.unreadIncidents(req({teamId: "a", since: T0 - 60_000}));
  assert.equal(count, 1);
  await rejects(ops.unreadIncidents(req({teamId: "a", since: "1000"})), "invalid-argument");
});

test("records: estado canônico — count usa lastSeen do servidor, exclui própria e tombstone", async () => {
  const {store, ops} = fixture();
  store.set("users/u/recordReadState/a", {lastSeenIncidentAt: stamp(T0 - 30_000)});
  store.set("incidents/r1", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "w", title: "Nova", createdAt: stamp(T0 - 10_000)});
  store.set("incidents/r2", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "w", title: "Antiga", createdAt: stamp(T0 - 60_000)});
  store.set("incidents/r3", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "u", title: "Privada", createdAt: stamp(T0 - 5_000)});
  store.set("incidents/r4", {teamId: "a", ownerSector: "BRIGADA", deleted: true, authorUid: "w", title: "Apagada", createdAt: stamp(T0 - 1_000)});
  store.set("roundReports/q1", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "z", title: "Ronda ativa", createdAt: stamp(T0 - 20_000)});
  const rs = await ops.unreadState(req({teamId: "a"}));
  assert.equal(rs.unreadIncidents, 1);
  assert.equal(rs.unreadRounds, 1);
  assert.equal(rs.totalUnread, 2);
  assert.equal(rs.lastSeenIncidentAt, T0 - 30_000);
  assert.equal(rs.lastSeenRoundAt, null);
});

test("records: sem estado salvo, conta backlog completo exceto o próprio uid", async () => {
  const {store, ops} = fixture();
  store.set("incidents/r1", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "w", title: "1", createdAt: stamp(T0 - 20_000)});
  store.set("incidents/r2", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "w", title: "2", createdAt: stamp(T0 - 9_000)});
  store.set("incidents/r3", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "u", title: "Minha", createdAt: stamp(T0 - 2_000)});
  const rs = await ops.unreadState(req({teamId: "a"}));
  assert.equal(rs.unreadIncidents, 2);
  assert.equal(rs.lastSeenIncidentAt, null);
});

test("records: reinstall conceitual — backend restaura estado após perda do cache local", async () => {
  const {store, ops} = fixture();
  store.set("incidents/r1", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "w", title: "Antiga", createdAt: stamp(T0 - HOUR)});
  store.set("incidents/r2", {teamId: "a", ownerSector: "BRIGADA", deleted: false, authorUid: "w", title: "Depois de visto", createdAt: stamp(T0 - 10_000)});
  const first = await ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: T0 - 30_000}));
  assert.equal(first.lastSeenIncidentAt, T0 - 30_000);
  const rs = await ops.unreadState(req({teamId: "a"}));
  assert.equal(rs.unreadIncidents, 1);
  assert.equal(rs.lastSeenIncidentAt, T0 - 30_000);
});

test("records: markSeen — abrir Ocorrências mexe só em lastSeenIncidentAt", async () => {
  const {store, ops} = fixture();
  store.set("users/u/recordReadState/a",
    {lastSeenIncidentAt: stamp(T0 - 10_000), lastSeenRoundAt: stamp(T0 - 10_000)});
  const r = await ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: T0}));
  const state = rowData(store.get("users/u/recordReadState/a"));
  assert.equal(state.lastSeenIncidentAt.toMillis(), T0);
  assert.equal(state.lastSeenRoundAt.toMillis(), T0 - 10_000);
  assert.equal(r.lastSeenIncidentAt, T0);
  assert.equal(r.lastSeenRoundAt, T0 - 10_000);
});

test("records: markSeen — abrir Rondas mexe só em lastSeenRoundAt", async () => {
  const {store, ops} = fixture();
  store.set("users/u/recordReadState/a",
    {lastSeenIncidentAt: stamp(T0 - 10_000), lastSeenRoundAt: stamp(T0 - 10_000)});
  await ops.markRecordsSeen(req({teamId: "a", recordType: "round", seenThrough: T0}));
  const state = rowData(store.get("users/u/recordReadState/a"));
  assert.equal(state.lastSeenIncidentAt.toMillis(), T0 - 10_000);
  assert.equal(state.lastSeenRoundAt.toMillis(), T0);
});

test("records: markSeen — monotônico, não retrocede nem aceita futuro", async () => {
  const {store, ops} = fixture();
  await ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: T0 + HOUR}));
  assert.equal(rowData(store.get("users/u/recordReadState/a")).lastSeenIncidentAt.toMillis(), T0);
  await ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: T0 - 40_000}));
  assert.equal(rowData(store.get("users/u/recordReadState/a")).lastSeenIncidentAt.toMillis(), T0);
});

test("records: markSeen — seenThrough <= 0 não avança estado (lista vazia)", async () => {
  const {store, ops, now} = fixture();
  store.set("users/u/recordReadState/a", {lastSeenIncidentAt: stamp(T0 - 10_000)});
  await ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: 0}));
  assert.equal(rowData(store.get("users/u/recordReadState/a")).lastSeenIncidentAt.toMillis(), T0 - 10_000);
  assert.equal(now(), T0);
});

test("records: equipe A não interfere na equipe B — estado é por teamId", async () => {
  const {store, ops} = fixture(baseSeed(["a", "b"]));
  store.set("users/u/recordReadState/a", {lastSeenIncidentAt: stamp(T0 - 10_000)});
  store.set("users/u/recordReadState/b", {lastSeenIncidentAt: stamp(T0 - 50_000)});
  await ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: T0}));
  assert.equal(rowData(store.get("users/u/recordReadState/a")).lastSeenIncidentAt.toMillis(), T0);
  assert.equal(rowData(store.get("users/u/recordReadState/b")).lastSeenIncidentAt.toMillis(), T0 - 50_000);
});

test("records: usuário A não interfere no usuário B — estado é por uid", async () => {
  const {store, ops} = fixture();
  store.set("users/u/recordReadState/a", {lastSeenIncidentAt: stamp(T0 - 10_000)});
  store.set("users/v/recordReadState/a", {lastSeenIncidentAt: stamp(T0 - 20_000)});
  await ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: T0}));
  assert.equal(rowData(store.get("users/u/recordReadState/a")).lastSeenIncidentAt.toMillis(), T0);
  assert.equal(rowData(store.get("users/v/recordReadState/a")).lastSeenIncidentAt.toMillis(), T0 - 20_000);
});

test("records: markSeen valida auth, tipo, seenThrough e membership", async () => {
  const {store, ops} = fixture();
  await rejects(ops.markRecordsSeen({data: {teamId: "a", recordType: "occurrence", seenThrough: T0}}), "unauthenticated");
  await rejects(ops.markRecordsSeen(req({teamId: "a", recordType: "incidente", seenThrough: 1})), "invalid-argument");
  await rejects(ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: "agora"})), "invalid-argument");
  await rejects(ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: NaN})), "invalid-argument");
  store.set("teams/a/members/u", {status: "pending", enabled: true});
  await rejects(ops.markRecordsSeen(req({teamId: "a", recordType: "occurrence", seenThrough: 1})), "permission-denied");
});

test("records: unreadState valida auth, teamId e equipe ativa", async () => {
  const {store, ops} = fixture();
  await rejects(ops.unreadState({data: {teamId: "a"}}), "unauthenticated");
  await rejects(ops.unreadState(req({teamId: ""})), "invalid-argument");
  store.set("teams/a", {enabled: false});
  await rejects(ops.unreadState(req({teamId: "a"})), "permission-denied");
});

test("records: registro sem título ou equipe inativa não envia nem cria ledger", async () => {
  const {store, ops, incident, messaging} = fixture();
  await ops.onIncidentCreated(incident("inc-no-title", {title: "   "}));
  store.set("teams/a", {enabled: false});
  await ops.onIncidentCreated(incident("inc-off", {}));
  assert.equal(messaging.calls.length, 0);
  assert.equal(store.has("notificationEvents/e-inc-no-title"), false);
  assert.equal(store.has("notificationEvents/e-inc-off"), false);
});

test("records: sem destinatários resolve completed sem enviar", async () => {
  const {store, ops, incident, messaging} = fixture();
  store.set("teams/a/members/w", {status: "pending", enabled: true});
  store.set("teams/a/members/z", {status: "pending", enabled: true});
  await ops.onIncidentCreated(incident("inc-alone", {authorUid: "u"}));
  assert.equal(messaging.calls.length, 0);
  assert.equal(store.get("notificationEvents/e-inc-alone").data.status, "completed");
  assert.equal(store.get("notificationEvents/e-inc-alone").data.attempts, 0);
});

test("records: trigger envia uma vez e marca completed", async () => {
  const {store, ops, incident, messaging} = fixture();
  await ops.onIncidentCreated(incident("inc1"));
  assert.equal(messaging.calls.length, 1);
  assert.deepEqual(messaging.calls[0].tokens, ["TOK_U", "TOK_Z"]);
  assert.equal(messaging.calls[0].data.type, "record_created");
  assert.equal(messaging.calls[0].data.recordType, "occurrence");
  assert.equal(messaging.calls[0].data.recordId, "inc1");
  const ledger = rowData(store.get("notificationEvents/e-inc1"));
  assert.equal(ledger.status, "completed");
  assert.equal(ledger.attempts, 1);
  assert.equal(ledger.recordType, "occurrence");
  assert.equal(ledger.recordId, "inc1");
  assert.ok(ledger.completedAt);
});

test("records: eventId já completed não reenvia", async () => {
  const {store, ops, incident, messaging} = fixture();
  store.set("notificationEvents/e-inc1", {eventId: "e-inc1", status: "completed", completedAt: stamp(T0)});
  await ops.onIncidentCreated(incident("inc1"));
  assert.equal(messaging.calls.length, 0);
  assert.equal(rowData(store.get("notificationEvents/e-inc1")).status, "completed");
});

test("records: claim/lease válido impede envio concorrente (redelivery duplicado)", async () => {
  const {store, ops, incident, messaging} = fixture();
  store.set("notificationEvents/e-inc1", {eventId: "e-inc1", status: "processing",
    claimedAt: stamp(T0), leaseUntil: stamp(T0 + LEASE_MS), attempts: 1});
  await Promise.all([
    ops.onIncidentCreated(incident("inc1")),
    ops.onIncidentCreated(incident("inc1")),
  ]);
  assert.equal(messaging.calls.length, 0);
});

test("records: dois redeliveries após claim normal entregam uma única vez", async () => {
  const {store, ops, incident, messaging} = fixture();
  await Promise.all([
    ops.onIncidentCreated(incident("inc1")),
    ops.onIncidentCreated(incident("inc1")),
  ]);
  assert.equal(messaging.calls.length, 1);
  assert.equal(store.get("notificationEvents/e-inc1").data.status, "completed");
  assert.equal(store.get("notificationEvents/e-inc1").data.attempts, 1);
});

test("records: lease expirado permite retry", async () => {
  const {store, ops, incident, messaging, advance} = fixture();
  store.set("notificationEvents/e-inc1", {eventId: "e-inc1", status: "processing",
    claimedAt: stamp(T0), leaseUntil: stamp(T0 - 1), attempts: 1});
  advance(2_000);
  await ops.onIncidentCreated(incident("inc1"));
  assert.equal(messaging.calls.length, 1);
  assert.equal(store.get("notificationEvents/e-inc1").data.status, "completed");
  assert.equal(store.get("notificationEvents/e-inc1").data.attempts, 2);
});

test("records: erro transitório libera lease e permite retry até concluir", async () => {
  const {store, ops, incident, messaging, advance, setSend} = fixture();
  setSend(payload => payload.tokens.map((t, i) => i === 1 ? failToken("messaging/internal-error") : {success: true}));
  await assert.rejects(ops.onIncidentCreated(incident("inc1")), /Erro transitório/);
  assert.equal(messaging.calls.length, 1);
  const ledger = rowData(store.get("notificationEvents/e-inc1"));
  assert.equal(ledger.status, "retrying");
  assert.ok(ledger.leaseUntil.toMillis() < T0);
  setSend(okResps);
  advance(2_000);
  await ops.onIncidentCreated(incident("inc1"));
  assert.equal(messaging.calls.length, 2);
  assert.equal(store.get("notificationEvents/e-inc1").data.status, "completed");
});

test("records: token invalidado permanentemente é removido sem impedir completed", async () => {
  const {store, ops, incident, messaging, setSend} = fixture();
  setSend(payload => payload.tokens.map((t, i) => t === "TOK_Z" ? failToken("messaging/registration-token-not-registered") : {success: true}));
  await ops.onIncidentCreated(incident("inc1"));
  assert.equal(messaging.calls.length, 1);
  assert.ok(!("fcmToken" in store.get("devices/z").data));
  assert.equal(rowData(store.get("devices/u")).fcmToken, "TOK_U");
  assert.equal(store.get("notificationEvents/e-inc1").data.status, "completed");
});

test("records: registros distintos geram eventos e ledgers independentes", async () => {
  const {store, ops, incident, round, messaging} = fixture();
  await ops.onIncidentCreated(incident("inc1"));
  await ops.onRoundReportCreated(round("rep1"));
  assert.equal(messaging.calls.length, 2);
  assert.equal(messaging.calls[1].data.recordType, "round");
  assert.equal(store.get("notificationEvents/e-inc1").data.status, "completed");
  assert.equal(store.get("notificationEvents/e-rep1").data.status, "completed");
});

test("records: não lidos respeitam setor e compartilhamento", async () => {
  const {store, ops} = fixture();
  store.set("users/u", {name: "U", operationalFunction: "BRIGADISTA"});
  store.set("incidents/b1", {teamId: "a", deleted: false, authorUid: "w", ownerSector: "BRIGADA", title: "Brigada", createdAt: stamp(T0 - 10_000)});
  store.set("incidents/s1", {teamId: "a", deleted: false, authorUid: "z", ownerSector: "SEGURANCA", title: "Segurança", createdAt: stamp(T0 - 9_000)});
  store.set("incidents/s2", {teamId: "a", deleted: false, authorUid: "z", ownerSector: "SEGURANCA", sharedWithSectors: ["BRIGADA"], title: "Compartilhada", createdAt: stamp(T0 - 8_000)});
  const state = await ops.unreadState(req({teamId: "a"}));
  assert.equal(state.unreadIncidents, 2);
});

test("records: contadores legado e canônico negam setor oposto e cargo ausente",async()=>{
  const {store,ops,incident}=fixture();
  incident("brigada",{ownerSector:"BRIGADA"});incident("seguranca",{ownerSector:"SEGURANCA"});
  assert.equal((await ops.unreadIncidents(req({teamId:"a",since:0}))).count,1);
  assert.equal((await ops.unreadState(req({teamId:"a"}))).unreadIncidents,1);
  store.set("users/u",{name:"U"});
  assert.equal((await ops.unreadIncidents(req({teamId:"a",since:0}))).count,0);
  assert.equal((await ops.unreadState(req({teamId:"a"}))).unreadIncidents,0);
});

test("records: ficha legada só infere setor de cargo confiável; sem setor não transmite para todos",async()=>{
  const {ops,incident,messaging}=fixture();
  await ops.onIncidentCreated(incident("unknown",{ownerSector:null,authorOperationalFunction:null}));
  assert.equal(messaging.calls.length,0);
  await ops.onIncidentCreated(incident("known",{ownerSector:null,authorOperationalFunction:"BRIGADISTA"}));
  assert.equal(messaging.calls.length,1);
});
