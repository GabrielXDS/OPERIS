import test from "node:test";
import assert from "node:assert/strict";
import {Timestamp} from "firebase-admin/firestore";
import {TokenVerifier} from "livekit-server-sdk";
import {pttOperations, pttRoomKey, HEARTBEAT_MS, LEASE_MS, MAX_TRANSMISSION_MS, TOKEN_TTL_SECONDS} from "../src/ptt.js";

const API_KEY = "testkey";
const API_SECRET = "testsecret";
const SERVER_URL = "wss://example.livekit.cloud";

function fixture(enforceDuty = true) {
  const data = new Map([
    ["teams/a", {enabled: true, active: true}],
    ["teams/b", {enabled: true, active: true}],
    ["teams/a/members/u", {enabled: true, status: "active", role: "member", onDuty:true, activeShiftId:"sa"}],
    ["teams/a/members/v", {enabled: true, status: "active", role: "member", onDuty:true, activeShiftId:"sa"}],
    ["teams/b/members/u", {enabled: true, status: "active", role: "member", onDuty:true, activeShiftId:"sb"}],
    ["teams/b/members/v", {enabled: true, status: "active", role: "member", onDuty:true, activeShiftId:"sb"}],
    ["shifts/sa", {teamId:"a", status:"ACTIVE", participants:[{uid:"u",endedAt:null},{uid:"v",endedAt:null}]}],
    ["shifts/sb", {teamId:"b", status:"ACTIVE", participants:[{uid:"u",endedAt:null},{uid:"v",endedAt:null}]}],
    ["users/u", {name: "Ana"}],
    ["users/v", {name: "Bruno"}],
    ["devices/u", {enabled: true}],
    ["devices/v", {enabled: true}],
  ]);
  let now = 1_000_000;
  let queue = Promise.resolve();
  const snap = path => ({exists: data.has(path), data: () => data.get(path)});
  const db = {
    doc: path => ({path}),
    getAll: (...refs) => Promise.all(refs.map(r => Promise.resolve(snap(r.path)))),
    runTransaction(fn) {
      const run = queue.then(async () => {
        const writes = [];
        let writing = false;
        const tx = {
          async get(ref) {
            assert.equal(writing, false);
            return snap(ref.path);
          },
          async getAll(...refs) {
            return Promise.all(refs.map(r => this.get(r)));
          },
          set(ref, v) {
            writing = true;
            writes.push(() => data.set(ref.path, v));
          },
          create(ref, v) {
            assert.ok(!data.has(ref.path));
            this.set(ref, v);
          },
          update(ref, v) {
            writing = true;
            writes.push(() => data.set(ref.path, {...data.get(ref.path), ...v}));
          },
          delete(ref) {
            writing = true;
            writes.push(() => data.delete(ref.path));
          },
        };
        const r = await fn(tx);
        writes.forEach(f => f());
        return r;
      });
      queue = run.catch(() => {});
      return run;
    },
  };
  const ops = pttOperations({db, apiKey: () => API_KEY, apiSecret: () => API_SECRET, serverUrl: () => SERVER_URL, now: () => now, enforceDuty});
  const req = (data, uid = "u") => ({auth: uid ? {uid} : null, data});
  return {data, ops, req, now: () => now, advance(ms) { now += ms; }};
}

const denied = (p, code) => assert.rejects(p, e => e.code === code);

test("getPttAccess: Auth obrigatório", async () => {
  const f = fixture();
  await denied(f.ops.access(f.req({teamId: "a"}, null)), "unauthenticated");
});

for (const status of ["pending", "rejected", "removed", "disabled"]) {
  test("getPttAccess: membro " + status + " bloqueado", async () => {
    const f = fixture();
    f.data.set("teams/a/members/u", {status, enabled: status !== "disabled"});
    await denied(f.ops.access(f.req({teamId: "a"})), "permission-denied");
  });
}

test("getPttAccess: radio exige participacao ativa em plantao", async () => {
  const f = fixture();
  f.data.get("teams/a/members/u").onDuty=false;
  await denied(f.ops.access(f.req({teamId:"a"})), "failed-precondition");
  f.data.get("teams/a/members/u").onDuty=true;
  f.data.get("shifts/sa").status="CLOSED";
  await denied(f.ops.access(f.req({teamId:"a"})), "failed-precondition");
});

test("getPttAccess: marcador de plantao exige participante ativo", async () => {
  const f = fixture();
  f.data.get("shifts/sa").participants=f.data.get("shifts/sa").participants.map(p=>p.uid==="u"?{...p,endedAt:123}:p);
  await denied(f.ops.access(f.req({teamId:"a"})), "failed-precondition");
});

test("laboratorio pode testar radio fora do plantao sem liberar equipe ou aparelho invalidos", async () => {
  const f = fixture(false);
  f.data.get("teams/a/members/u").onDuty=false;
  f.data.get("shifts/sa").status="CLOSED";
  const out = await f.ops.access(f.req({teamId:"a"}));
  assert.equal(out.serverUrl, SERVER_URL);
  f.data.set("devices/u", {enabled:false});
  await denied(f.ops.access(f.req({teamId:"a"})), "permission-denied");
});

test("getPttAccess: equipe indisponível, device indisponível e id inválido", async () => {
  const f = fixture();
  f.data.set("teams/a", {enabled: false});
  await denied(f.ops.access(f.req({teamId: "a"})), "permission-denied");
  f.data.set("teams/a", {enabled: true});
  f.data.set("devices/u", {enabled: false});
  await denied(f.ops.access(f.req({teamId: "a"})), "permission-denied");
  for (const bad of [undefined, "", "x y", "a/b", 42]) {
    await denied(f.ops.access(f.req({teamId: bad})), "invalid-argument");
  }
});

test("getPttAccess: token válido, identidade=UID, nome do perfil e grant restrito ao microfone", async () => {
  const f = fixture();
  const out = await f.ops.access(f.req({teamId: "a"}));
  assert.equal(out.serverUrl, SERVER_URL);
  assert.equal(out.expiresInSeconds, TOKEN_TTL_SECONDS);
  assert.equal(out.roomName, pttRoomKey("a"));
  assert.deepEqual(Object.keys(out).sort(), ["expiresInSeconds", "participantToken", "roomName", "serverUrl"]);
  const verifier = new TokenVerifier(API_KEY, API_SECRET);
  const claims = await verifier.verify(out.participantToken);
  assert.equal(claims.sub, "u");
  assert.equal(claims.name, "Ana");
  assert.equal(claims.video.room, out.roomName);
  assert.equal(claims.video.roomJoin, true);
  assert.equal(claims.video.canSubscribe, true);
  assert.equal(claims.video.canPublish, true);
  assert.deepEqual(claims.video.canPublishSources, ["microphone"]);
  assert.equal(claims.video.canPublishData, undefined);
});

test("getPttAccess: nome padrão, nome de equipe válido e room estável e isolado", async () => {
  const f = fixture();
  f.data.get("users/u").name = "";
  let out = await f.ops.access(f.req({teamId: "a"}));
  const claims = await new TokenVerifier(API_KEY, API_SECRET).verify(out.participantToken);
  assert.equal(claims.name, "Integrante");
  f.data.delete("users/u");
  out = await f.ops.access(f.req({teamId: "a"}));
  const claims2 = await new TokenVerifier(API_KEY, API_SECRET).verify(out.participantToken);
  assert.equal(claims2.name, "Integrante");
  assert.equal(out.roomName, pttRoomKey("a"));
  assert.notEqual(pttRoomKey("a"), pttRoomKey("b"));
  assert.notEqual(out.roomName, pttRoomKey("b"));
});

for (const method of ["request", "renew", "release"]) {
  test(method + "PttFloor: Auth obrigatório", async () => {
    const f = fixture();
    await denied(f.ops[method](f.req({teamId: "a"}, null)), "unauthenticated");
  });
  test(method + "PttFloor: membro inativo e id inválido", async () => {
    const f = fixture();
    f.data.set("teams/a/members/u", {enabled: true, status: "pending"});
    await denied(f.ops[method](f.req({teamId: "a"})), "permission-denied");
    f.data.set("teams/a/members/u", {enabled: true, status: "active"});
    await denied(f.ops[method](f.req({teamId: "   "})), "invalid-argument");
  });
}

test("requestPttFloor: primeiro concede com lease, segundo usuário negado com holderName", async () => {
  const f = fixture();
  assert.deepEqual(await f.ops.request(f.req({teamId: "a"})), {granted: true});
  const floor = f.data.get("pttFloors/" + pttRoomKey("a"));
  assert.equal(floor.holderUid, "u");
  assert.equal(floor.holderName, "Ana");
  assert.equal(floor.expiresAt.toMillis(), f.now() + LEASE_MS);
  assert.deepEqual(await f.ops.request(f.req({teamId: "a"}, "v")), {granted: false, holderName: "Ana"});
});

test("requestPttFloor: mesmo holder renova e não aparece holderUid na resposta", async () => {
  const f = fixture();
  await f.ops.request(f.req({teamId: "a"}));
  assert.deepEqual(await f.ops.request(f.req({teamId: "a"})), {granted: true});
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("a")).holderUid, "u");
});

test("requestPttFloor: lease expirada é retomável por outro usuário", async () => {
  const f = fixture();
  await f.ops.request(f.req({teamId: "a"}));
  f.advance(LEASE_MS);
  assert.deepEqual(await f.ops.request(f.req({teamId: "a"}, "v")), {granted: true});
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("a")).holderUid, "v");
});

test("renewPttFloor: só o holder renova; não-holder renovando after expira retoma", async () => {
  const f = fixture();
  await f.ops.request(f.req({teamId: "a"}));
  assert.deepEqual(await f.ops.renew(f.req({teamId: "a"}, "v")), {granted: false, holderName: "Ana"});
  await f.ops.renew(f.req({teamId: "a"}));
  const floor = f.data.get("pttFloors/" + pttRoomKey("a"));
  assert.equal(floor.expiresAt.toMillis(), f.now() + LEASE_MS);
  assert.equal(floor.holderUid, "u");
  f.advance(LEASE_MS);
  assert.deepEqual(await f.ops.renew(f.req({teamId: "a"}, "v")), {granted: true});
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("a")).holderUid, "v");
});

test("releasePttFloor: holder libera, idempotente e outro usuário não força", async () => {
  const f = fixture();
  await f.ops.request(f.req({teamId: "a"}));
  assert.deepEqual(await f.ops.release(f.req({teamId: "a"})), {released: true});
  assert.ok(!f.data.has("pttFloors/" + pttRoomKey("a")));
  assert.deepEqual(await f.ops.release(f.req({teamId: "a"})), {released: true});
  await f.ops.request(f.req({teamId: "a"}, "v"));
  assert.deepEqual(await f.ops.release(f.req({teamId: "a"}, "u")), {released: false});
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("a")).holderUid, "v");
});

test("releasePttFloor: após expirar, liberar é idempotente", async () => {
  const f = fixture();
  await f.ops.request(f.req({teamId: "a"}));
  f.advance(LEASE_MS + 1);
  assert.deepEqual(await f.ops.release(f.req({teamId: "a"})), {released: true});
});

test("floors e tokens isolados por equipe", async () => {
  const f = fixture();
  await f.ops.request(f.req({teamId: "a"}));
  assert.deepEqual(await f.ops.request(f.req({teamId: "b"}, "v")), {granted: true});
  assert.equal(f.data.has("pttFloors/" + pttRoomKey("a")), true);
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("b")).holderUid, "v");
  const out = await f.ops.access(f.req({teamId: "b"}, "v"));
  assert.equal(out.roomName, pttRoomKey("b"));
});

test("constantes de timing esperadas", () => {
  assert.equal(HEARTBEAT_MS, 4000);
  assert.equal(LEASE_MS, 15000);
  assert.equal(MAX_TRANSMISSION_MS, 60000);
  assert.equal(TOKEN_TTL_SECONDS, 1800);
});

test("canal TODOS preserva room legado e compatibilidade com clientes antigos", async () => {
  const f = fixture();
  const legacy = await f.ops.access(f.req({teamId: "a"}));
  const general = await f.ops.access(f.req({teamId: "a", channel: "TODOS"}));
  assert.equal(general.roomName, legacy.roomName);
  assert.equal(general.roomName, pttRoomKey("a"));
  assert.equal(pttRoomKey("a", "TODOS"), pttRoomKey("a"));
});

test("canais funcionais autorizam apenas a função correspondente", async () => {
  const f = fixture();
  f.data.get("users/u").operationalFunction = "BRIGADISTA";
  f.data.get("users/v").operationalFunction = "VIGILANTE";
  const brigada = await f.ops.access(f.req({teamId: "a", channel: "BRIGADA"}, "u"));
  const seguranca = await f.ops.access(f.req({teamId: "a", channel: "SEGURANCA"}, "v"));
  assert.equal(brigada.roomName, pttRoomKey("a", "BRIGADA"));
  assert.equal(seguranca.roomName, pttRoomKey("a", "SEGURANCA"));
  assert.notEqual(brigada.roomName, seguranca.roomName);
  assert.notEqual(brigada.roomName, pttRoomKey("a"));
  await denied(f.ops.access(f.req({teamId: "a", channel: "SEGURANCA"}, "u")), "permission-denied");
  await denied(f.ops.access(f.req({teamId: "a", channel: "BRIGADA"}, "v")), "permission-denied");
  await denied(f.ops.access(f.req({teamId: "a", channel: "OUTRO"}, "u")), "invalid-argument");
});

test("AGP compartilha o canal SEGURANCA com Vigilantes", async () => {
  const f = fixture();
  f.data.get("users/u").operationalFunction = "AGP";
  const out = await f.ops.access(f.req({teamId: "a", channel: "SEGURANCA"}));
  assert.equal(out.roomName, pttRoomKey("a", "SEGURANCA"));
});

test("pisos BRIGADA, SEGURANCA e TODOS são independentes", async () => {
  const f = fixture();
  f.data.get("users/u").operationalFunction = "BRIGADISTA";
  f.data.get("users/v").operationalFunction = "VIGILANTE";
  assert.deepEqual(await f.ops.request(f.req({teamId: "a", channel: "BRIGADA"}, "u")), {granted: true});
  assert.deepEqual(await f.ops.request(f.req({teamId: "a", channel: "SEGURANCA"}, "v")), {granted: true});
  assert.deepEqual(await f.ops.request(f.req({teamId: "a", channel: "TODOS"}, "u")), {granted: true});
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("a", "BRIGADA")).holderUid, "u");
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("a", "SEGURANCA")).holderUid, "v");
  assert.equal(f.data.get("pttFloors/" + pttRoomKey("a")).holderUid, "u");
});
