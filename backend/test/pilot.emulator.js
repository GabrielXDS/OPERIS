import test from "node:test";
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {createServer} from "node:http";
import express from "express";
import {getFirestore} from "firebase-admin/firestore";
if(!process.env.FIRESTORE_EMULATOR_HOST || process.env.GCLOUD_PROJECT!=="demo-alerta-equipe")throw new Error("Somente emulador demo.");
process.env.ALERTA_ENV="pilot";
const f=await import("../src/index.js");
const {callableOptions}=await import("../src/app-check-policy.js");
const {alertTargets}=await import("../src/teams.js");
const db=getFirestore();
const names=["upsertProfile","createTeam","previewTeamInvite","joinTeam","reviewMembership",
  "listMyTeams","getTeamDetails","setAvailability","manageTeamInvite","syncDevice","triggerAlert","registerDevice"];
const req=(uid,data={})=>({auth:{uid},data}); // No App Check context.

test("PILOT HTTP: todas as callables sem App Check continuam exigindo Auth, inclusive com auth/UID forjados no body",async()=>{
  assert.equal(callableOptions.enforceAppCheck.value(),false);
  const app=express();app.use(express.json());
  for(const name of names)app.post("/"+name,f[name]);
  const server=createServer(app);
  await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
  try {
    for(const name of names) {
      const result=await fetch(`http://127.0.0.1:${server.address().port}/${name}`,{
        method:"POST",headers:{"Content-Type":"application/json"},
        body:JSON.stringify({data:{auth:{uid:"forged"},uid:"forged",role:"owner",status:"active",ALERTA_ENV:"pilot"}}),
      });
      assert.equal(result.status,401,name);
      const body=await result.json();
      assert.equal(body.error.status,"UNAUTHENTICATED",name);
      assert.equal(body.error.message,"Autenticação necessária.",name);
      await assert.rejects(f[name].run({auth:{},data:{uid:"forged"}}),e=>e.code==="unauthenticated",name);
    }
  } finally {await new Promise(resolve=>server.close(resolve));}
});

test("PILOT: pending, outra equipe e member não escalam privilégios; aprovação e destinatários permanecem isolados",async()=>{
  const suffix=randomUUID(),owner="o_"+suffix,pending="p_"+suffix,other="x_"+suffix;
  for(const uid of [owner,pending,other])await f.upsertProfile.run(req(uid,{name:uid,operationalFunction:"BRIGADISTA",token:uid.repeat(2),role:"owner",enabled:true}));
  const team=await f.createTeam.run(req(owner,{name:"Pilot",requestId:randomUUID(),ownerUid:other,requireApproval:false}));
  const foreign=await f.createTeam.run(req(other,{name:"Outra",requestId:randomUUID()}));
  const teamRef=db.doc("teams/"+team.teamId),data=(await teamRef.get()).data();
  assert.equal(data.ownerUid,owner);assert.equal(data.requireApproval,true);
  const joined=await f.joinTeam.run(req(pending,{inviteCode:data.inviteCode,expectedTeamId:team.teamId,
    role:"owner",status:"active",enabled:true,ownerUid:pending}));
  assert.equal(joined.status,"pending");
  const member=(await teamRef.collection("members").doc(pending).get()).data();
  assert.equal(member.status,"pending");assert.equal(member.role,"member");assert.equal(member.enabled,false);
  for(const uid of [pending,other]) {
    for(const [name,args] of [
      ["getTeamDetails",{teamId:team.teamId}],
      ["triggerAlert",{teamId:team.teamId,alertId:randomUUID()}],
      ["manageTeamInvite",{teamId:team.teamId,action:"rotate"}],
      ["reviewMembership",{teamId:team.teamId,uid:pending,action:"approve"}],
    ]) {
      const expected=name==="reviewMembership" && args.uid===uid ? "invalid-argument" : "permission-denied";
      await assert.rejects(f[name].run(req(uid,args)),e=>e.code===expected,name);
    }
  }
  assert.deepEqual(await alertTargets(db,team.teamId,owner),[]);
  await f.reviewMembership.run(req(owner,{teamId:team.teamId,uid:pending,action:"approve",role:"admin"}));
  assert.equal((await teamRef.collection("members").doc(pending).get()).data().role,"member");
  await assert.rejects(f.reviewMembership.run(req(pending,{teamId:team.teamId,uid:other,action:"approve"})),e=>e.code==="permission-denied");
  assert.deepEqual((await alertTargets(db,team.teamId,owner)).map(t=>t.id),[pending]);
  assert.deepEqual(await alertTargets(db,foreign.teamId,other),[]);
  const details=await f.getTeamDetails.run(req(pending,{teamId:team.teamId}));
  assert.equal(JSON.stringify(details).includes("fcmToken"),false);
  assert.equal(details.invite,null);
  const alertId=randomUUID();
  await f.triggerAlert.run(req(pending,{teamId:team.teamId,alertId}));
  assert.equal((await f.triggerAlert.run(req(pending,{teamId:team.teamId,alertId}))).accepted,true);
  await assert.rejects(f.triggerAlert.run(req(pending,{teamId:team.teamId,alertId:randomUUID()})),e=>e.code==="resource-exhausted");
});
