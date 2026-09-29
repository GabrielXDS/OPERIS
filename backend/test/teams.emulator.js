import test from "node:test";
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {getFirestore,Timestamp} from "firebase-admin/firestore";
if(!process.env.FIRESTORE_EMULATOR_HOST || process.env.GCLOUD_PROJECT!=="demo-alerta-equipe")throw new Error("Somente emulador demo.");
const f=await import("../src/index.js");
const {alertTargets}=await import("../src/teams.js");
const {migrateLegacyDevice}=await import("../src/legacy-migration.js");
const db=getFirestore(),suffix=Date.now()+"_"+Math.floor(Math.random()*10000);
const a="owner_"+suffix,b="member_"+suffix,c="other_"+suffix;
const req=(uid,data={})=>({auth:uid?{uid}:undefined,data});
let teamId,code;
test("perfil + criação atômica e idempotente; cliente não escolhe owner",async()=>{
 for(const uid of [a,b,c])await f.upsertProfile.run(req(uid,{name:uid,operationalFunction:"BRIGADISTA",token:uid.repeat(2)}));
 const requestId=randomUUID();
 const results=await Promise.all([1,2].map(()=>f.createTeam.run(req(a,{name:"Brigada Teste",requestId,ownerUid:b,role:"member"}))));
 assert.equal(results[0].teamId,results[1].teamId);teamId=results[0].teamId;
 const team=(await db.doc("teams/"+teamId).get()).data();code=team.inviteCode;
 assert.equal(team.ownerUid,a);assert.notEqual(teamId,code);
 assert.equal((await db.doc("teams/"+teamId+"/members/"+a).get()).data().role,"owner");
});
test("prévia não cria membership; confirmação vinculada e role member",async()=>{
 const preview=await f.previewTeamInvite.run(req(b,{inviteCode:code.toLowerCase()}));
 assert.equal(preview.teamId,teamId);
 assert.equal((await db.doc("teams/"+teamId+"/members/"+b).get()).exists,false);
 await assert.rejects(f.joinTeam.run(req(b,{inviteCode:code,expectedTeamId:"forged",role:"owner"})),e=>e.code==="failed-precondition");
 const joined=await f.joinTeam.run(req(b,{inviteCode:code,expectedTeamId:teamId,role:"owner"}));
 assert.equal(joined.role,"member");
 assert.equal(joined.status,"pending");
 assert.deepEqual(await alertTargets(db,teamId,a),[]);
 await assert.rejects(f.getTeamDetails.run(req(b,{teamId})),e=>e.code==="permission-denied");
 await assert.rejects(f.triggerAlert.run(req(b,{teamId,alertId:randomUUID()})),e=>e.code==="permission-denied");
 await assert.rejects(f.reviewMembership.run(req(c,{teamId,uid:b,action:"approve"})),e=>e.code==="permission-denied");
 await f.reviewMembership.run(req(a,{teamId,uid:b,action:"approve"}));
 const again=await f.joinTeam.run(req(b,{inviteCode:code,expectedTeamId:teamId}));
 assert.equal(again.role,"member");assert.equal((await db.doc("teams/"+teamId).get()).data().deviceCount,2);
});
test("detalhes autorizados e sem tokens; convite somente owner",async()=>{
 await assert.rejects(f.getTeamDetails.run(req(c,{teamId})),e=>e.code==="permission-denied");
 const owner=await f.getTeamDetails.run(req(a,{teamId})),member=await f.getTeamDetails.run(req(b,{teamId}));
 assert.equal(owner.invite.code,code);assert.equal(member.invite,null);
 assert.equal(JSON.stringify(owner).includes("fcmToken"),false);
 assert.equal(owner.totalMembers,2);assert.equal(owner.readyCount,0);
});
test("heartbeat usa servidor, pausa não exclui destinatário, offline prevalece",async()=>{
 await f.syncDevice.run(req(b,{token:b.repeat(2),modernClient:true,diagnostics:{
   notificationsEnabled:true,channelReady:true,audioReady:true,appVersion:"0.2.0",lastSeenAt:9999999999999}}));
 await f.setAvailability.run(req(b,{availability:"paused",pauseReason:"lunch"}));
 let details=await f.getTeamDetails.run(req(a,{teamId}));
 assert.equal(details.pausedCount,1);assert.equal(details.readyCount,1);
 assert.ok(details.members.find(m=>m.uid===b).lastSeenAt<=Date.now());
 assert.deepEqual((await alertTargets(db,teamId,a)).map(t=>t.id),[b]);
 await db.doc("devices/"+b).update({lastSeenAt:Timestamp.fromMillis(Date.now()-180001)});
 details=await f.getTeamDetails.run(req(a,{teamId}));
 assert.equal(details.pausedCount,0);assert.equal(details.offlineCount,2);assert.equal(details.readyCount,0);
});
test("multi-equipe e isolamento de origem, idempotência por equipe",async()=>{
 const other=await f.createTeam.run(req(a,{name:"Outra equipe",requestId:randomUUID()}));
 const list=await f.listMyTeams.run(req(a));assert.equal(list.teams.length,2);
 const id=randomUUID();
 await f.triggerAlert.run(req(a,{teamId,alertId:id,senderName:"forged"}));
 assert.equal((await db.doc("alerts/"+id).get()).data().senderName,a);
 await assert.rejects(f.triggerAlert.run(req(a,{teamId:other.teamId,alertId:id})),e=>e.code==="permission-denied");
 await assert.rejects(f.triggerAlert.run(req(c,{teamId,alertId:randomUUID()})),e=>e.code==="permission-denied");
 assert.deepEqual(await alertTargets(db,other.teamId,a),[]);
});
test("owner gira/revoga convite sem remover membros, member não administra",async()=>{
 await assert.rejects(f.manageTeamInvite.run(req(b,{teamId,action:"rotate"})),e=>e.code==="permission-denied");
 await f.manageTeamInvite.run(req(a,{teamId,action:"rotate"}));
 await assert.rejects(f.previewTeamInvite.run(req(c,{inviteCode:code})),e=>e.code==="not-found");
 const newCode=(await db.doc("teams/"+teamId).get()).data().inviteCode;
 assert.notEqual(newCode,code);
 await f.manageTeamInvite.run(req(a,{teamId,action:"revoke"}));
 await assert.rejects(f.previewTeamInvite.run(req(c,{inviteCode:newCode})),e=>e.code==="not-found");
 assert.equal((await f.getTeamDetails.run(req(b,{teamId}))).totalMembers,2);
});
test("rate limiting bloqueia enumeração por UID",async()=>{
 const uid="rate_"+suffix;
 await f.upsertProfile.run(req(uid,{name:"Rate",operationalFunction:"BRIGADISTA",token:"r".repeat(80)}));
 for(let i=0;i<6;i++)await assert.rejects(f.previewTeamInvite.run(req(uid,{inviteCode:"AB7K92QD"})));
 await assert.rejects(f.previewTeamInvite.run(req(uid,{inviteCode:"AB7K92QD"})),e=>e.code==="resource-exhausted");
});
test("documentos legados sozinhos não concedem acesso",async()=>{
 const uid="legacy_"+suffix,id="legacyTeam_"+suffix;
 await db.doc("teams/"+id).set({enabled:true,deviceCount:1});
 await db.doc("members/"+uid).set({enabled:true,teamId:id});
 await db.doc("teams/"+id+"/devices/"+uid).set({enabled:true,name:"Legado",fcmToken:"l".repeat(80)});
 assert.deepEqual(await alertTargets(db,id,"another"),[]);
 await assert.rejects(f.syncDevice.run(req(uid,{token:"x".repeat(80)})),e=>e.code==="permission-denied");
});
test("convite expirado é rejeitado e regras negam documentos privados",async()=>{
 const result=await f.createTeam.run(req(c,{name:"Expirado",requestId:randomUUID()}));
 const t=(await db.doc("teams/"+result.teamId).get()).data();
 await db.doc("teamInvites/"+t.inviteCode).update({expiresAt:Timestamp.fromMillis(1)});
 await assert.rejects(f.previewTeamInvite.run(req(a,{inviteCode:t.inviteCode})),e=>e.code==="not-found");
 for(const path of ["devices/"+a,"users/"+a,"teamInvites/"+code,"teams/"+teamId+"/members/"+a]){
  const response=await fetch("http://"+process.env.FIRESTORE_EMULATOR_HOST+"/v1/projects/demo-alerta-equipe/databases/(default)/documents/"+path);
  assert.equal(response.status,403);
 }
});

test("owner vê solicitação; recusa não cria acesso e revisão é idempotente",async()=>{
 const result=await f.createTeam.run(req(c,{name:"Aprovação",requestId:randomUUID()}));
 const t=(await db.doc("teams/"+result.teamId).get()).data();
 await f.joinTeam.run(req(a,{inviteCode:t.inviteCode,expectedTeamId:result.teamId}));
 const pending=await f.getTeamDetails.run(req(c,{teamId:result.teamId}));
 assert.equal(pending.requests[0].uid,a);assert.ok(pending.requests[0].requestedAt>0);
 await assert.rejects(f.reviewMembership.run(req(a,{teamId:result.teamId,uid:a,action:"approve"})),e=>e.code==="invalid-argument");
 await f.reviewMembership.run(req(c,{teamId:result.teamId,uid:a,action:"reject"}));
 await f.reviewMembership.run(req(c,{teamId:result.teamId,uid:a,action:"reject"}));
 assert.equal((await db.doc("teams/"+result.teamId).get()).data().pendingCount,0);
 const userDoc=(await db.doc("users/"+a).get()).data();
 assert.equal((userDoc.teamIds||[]).includes(result.teamId),false,"reject NÃO adiciona teamIds");
 assert.equal((userDoc.requestedTeamIds||[]).includes(result.teamId),false,"reject remove requestedTeamIds");
 await assert.rejects(f.triggerAlert.run(req(a,{teamId:result.teamId,alertId:randomUUID()})),e=>e.code==="permission-denied");
 assert.deepEqual(await alertTargets(db,result.teamId,c),[]);
});
test("membro comum e owner de outra equipe não aprovam; aprovação concorrente conta uma vez",async()=>{
 const newcomer="new_"+suffix;
 await f.upsertProfile.run(req(newcomer,{name:"Novo",operationalFunction:"BRIGADISTA",token:"z".repeat(80)}));
 await f.manageTeamInvite.run(req(a,{teamId,action:"rotate"}));
 const code=(await db.doc("teams/"+teamId).get()).data().inviteCode;
 await f.joinTeam.run(req(newcomer,{inviteCode:code,expectedTeamId:teamId}));
 await assert.rejects(f.reviewMembership.run(req(b,{teamId,uid:newcomer,action:"approve"})),e=>e.code==="permission-denied");
 await assert.rejects(f.reviewMembership.run(req(c,{teamId,uid:newcomer,action:"approve"})),e=>e.code==="permission-denied");
 await Promise.all([1,2].map(()=>f.reviewMembership.run(req(a,{teamId,uid:newcomer,action:"approve"}))));
 const m=(await db.doc("teams/"+teamId+"/members/"+newcomer).get()).data();
 assert.equal(m.status,"active");assert.equal(m.role,"member");
 const details=await f.getTeamDetails.run(req(a,{teamId}));
 assert.equal(details.totalMembers,3);assert.ok(details.members.some(m=>m.uid===newcomer));
 assert.ok((await alertTargets(db,teamId,a)).some(t=>t.id===newcomer));
 assert.equal((await db.doc("teams/"+teamId).get()).data().pendingCount,0);
 const sent=await f.triggerAlert.run(req(newcomer,{teamId,alertId:randomUUID()}));assert.equal(sent.accepted,true);
 assert.ok((await alertTargets(db,teamId,newcomer)).some(t=>t.id===a));
});
test("aprovação exige convite ainda válido e auth; pendente não aparece na lista ativa",async()=>{
 const newcomer="expires_"+suffix;
 await f.upsertProfile.run(req(newcomer,{name:"Pendente",operationalFunction:"BRIGADISTA",token:"e".repeat(80)}));
 const code=(await db.doc("teams/"+teamId).get()).data().inviteCode;
 await f.joinTeam.run(req(newcomer,{inviteCode:code,expectedTeamId:teamId}));
 const account=await f.listMyTeams.run(req(newcomer));
 assert.equal(account.teams.length,0);assert.equal(account.requests[0].status,"pending");
 await db.doc("teamInvites/"+code).update({expiresAt:Timestamp.fromMillis(1)});
 await assert.rejects(f.reviewMembership.run(req(a,{teamId,uid:newcomer,action:"approve"})),e=>e.code==="failed-precondition");
 await assert.rejects(f.createTeam.run(req(null,{name:"X",requestId:randomUUID()})),e=>e.code==="unauthenticated");
 await assert.rejects(f.reviewMembership.run(req(null,{teamId,uid:newcomer,action:"approve"})),e=>e.code==="unauthenticated");
});
