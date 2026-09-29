import test from "node:test";
import assert from "node:assert/strict";
import {createHash,randomUUID} from "node:crypto";
import {getFirestore,Timestamp} from "firebase-admin/firestore";

// Proteção explícita: este teste nunca deve acessar Firestore de produção.
if (!process.env.FIRESTORE_EMULATOR_HOST || process.env.GCLOUD_PROJECT !== "demo-alerta-equipe") {
  throw new Error("Execute somente via emulator com projeto demo-alerta-equipe.");
}
const {upsertProfile,createTeam,syncDevice,triggerAlert}=await import("../src/index.js");
const db=getFirestore();
let teamId;
const req=(uid,data)=>({auth:uid?{uid}:undefined,data});
const uid="A_"+Date.now();
test("sem autenticação é rejeitado",async()=>{
 await assert.rejects(triggerAlert.run(req(null,{alertId:randomUUID()})),e=>e.code==="unauthenticated");
});
test("cadastro, vínculo confiável e sincronização",async()=>{
 await upsertProfile.run(req(uid,{name:"Portaria",operationalFunction:"BRIGADISTA",token:"t".repeat(80)}));
 const result=await createTeam.run(req(uid,{name:"Teste",requestId:randomUUID(),ownerUid:"forged"}));
 teamId=result.teamId;
 await syncDevice.run(req(uid,{token:"new".repeat(30)}));
 assert.equal((await db.doc("devices/"+uid).get()).data().fcmToken,"new".repeat(30));
});
test("transação limita disparos concorrentes e fixa origem/equipe",async()=>{
 const ids=[randomUUID(),randomUUID()];
 await assert.rejects(triggerAlert.run(req(uid,{alertId:randomUUID(),teamId:"forged"})),e=>e.code==="permission-denied");
 const results=await Promise.allSettled(ids.map(alertId=>triggerAlert.run(req(uid,{alertId,teamId,senderName:"Falso",tokens:["bad"]}))));
 assert.equal(results.filter(r=>r.status==="fulfilled").length,1);
 assert.equal(results.filter(r=>r.status==="rejected" && r.reason.code==="resource-exhausted").length,1);
 const index=results.findIndex(r=>r.status==="fulfilled"),id=ids[index];
 const a=(await db.doc("alerts/"+id).get()).data();
 assert.equal(a.teamId,teamId);assert.equal(a.senderDeviceId,uid);assert.equal(a.senderName,"Portaria");
 const again=await triggerAlert.run(req(uid,{alertId:id,teamId}));
 assert.equal(again.accepted,true);
});
test("revogação bloqueia atualização de token e disparo",async()=>{
 await db.doc("devices/"+uid).update({enabled:false});
 await assert.rejects(syncDevice.run(req(uid,{token:"x".repeat(80)})),e=>e.code==="permission-denied");
 await assert.rejects(triggerAlert.run(req(uid,{alertId:randomUUID(),teamId})),e=>e.code==="permission-denied");
});
test("cadastro antigo não contorna aprovação",async()=>{
 const {registerDevice}=await import("../src/index.js");
 await assert.rejects(registerDevice.run(req("unknown",{name:"Invasor",code:"A".repeat(48),token:"t".repeat(80)})),e=>e.code==="failed-precondition");
 assert.equal((await db.doc("users/unknown").get()).exists,false);
});
test("Security Rules negam leitura e escrita REST direta",async()=>{
 const base="http://"+process.env.FIRESTORE_EMULATOR_HOST+"/v1/projects/demo-alerta-equipe/databases/(default)/documents/";
 const read=await fetch(base+"teams/"+teamId);
 assert.equal(read.status,403);
 const write=await fetch(base+"teams/forged",{method:"PATCH",headers:{"Content-Type":"application/json"},body:JSON.stringify({fields:{enabled:{booleanValue:true}}})});
 assert.equal(write.status,403);
});
