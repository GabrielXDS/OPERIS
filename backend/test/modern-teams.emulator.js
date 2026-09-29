import test from "node:test";
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {getFirestore} from "firebase-admin/firestore";

if(!process.env.FIRESTORE_EMULATOR_HOST || process.env.GCLOUD_PROJECT!=="demo-alerta-equipe")
  throw new Error("Somente emulador demo.");

const f=await import("../src/index.js");
const {alertTargets}=await import("../src/teams.js");
const db=getFirestore();
const suffix=Date.now()+"_"+Math.floor(Math.random()*10000);
const owner="modern_owner_"+suffix, member="modern_member_"+suffix;
const ownerDevice="dev_owner_"+suffix, memberDevice="dev_member_"+suffix;
const req=(uid,deviceId,data={})=>({auth:{uid},data:{...data,deviceId}});

test("instalacoes modernas usam installationId em todo o fluxo de equipes",async()=>{
  await f.upsertProfile.run(req(owner,ownerDevice,{name:"Owner Modern",operationalFunction:"BRIGADISTA",token:"o".repeat(80)}));
  await f.upsertProfile.run(req(member,memberDevice,{name:"Member Modern",operationalFunction:"AGP",token:"m".repeat(80)}));
  assert.equal((await db.doc("devices/"+owner).get()).exists,false);
  assert.equal((await db.doc("devices/"+member).get()).exists,false);
  assert.equal((await db.doc("devices/"+ownerDevice).get()).data().uid,owner);

  const created=await f.createTeam.run(req(owner,ownerDevice,{name:"Equipe Modern",requestId:randomUUID()}));
  const team=(await db.doc("teams/"+created.teamId).get()).data();
  let account=await f.listMyTeams.run(req(owner,ownerDevice));
  assert.equal(account.deviceId,ownerDevice);
  assert.equal(account.teams[0].teamId,created.teamId);

  const preview=await f.previewTeamInvite.run(req(member,memberDevice,{inviteCode:team.inviteCode}));
  const joined=await f.joinTeam.run(req(member,memberDevice,{inviteCode:team.inviteCode,expectedTeamId:preview.teamId}));
  assert.equal(joined.status,"pending");
  let details=await f.getTeamDetails.run(req(owner,ownerDevice,{teamId:created.teamId}));
  assert.equal(details.requests[0].uid,member);

  await f.reviewMembership.run(req(owner,ownerDevice,{teamId:created.teamId,uid:member,action:"approve"}));
  await f.syncDevice.run(req(member,memberDevice,{token:"n".repeat(80),modernClient:true,diagnostics:{notificationsEnabled:true,channelReady:true,audioReady:true,appVersion:"4.2.1"}}));
  await f.setAvailability.run(req(member,memberDevice,{availability:"paused",pauseReason:"break"}));
  details=await f.getTeamDetails.run(req(owner,ownerDevice,{teamId:created.teamId}));
  assert.equal(details.totalMembers,2);
  assert.ok(details.members.some(m=>m.uid===member && m.status==="paused"));
  assert.ok((await alertTargets(db,created.teamId,owner)).some(t=>t.id===memberDevice));

  const unread=await f.unreadState.run(req(owner,ownerDevice,{teamId:created.teamId}));
  assert.equal(unread.totalUnread,0);
  const seen=await f.markRecordsSeen.run(req(owner,ownerDevice,{teamId:created.teamId,recordType:"occurrence",seenThrough:0}));
  assert.equal(seen.marked,true);

  await f.manageTeamInvite.run(req(owner,ownerDevice,{teamId:created.teamId,action:"rotate"}));
  const rotated=(await db.doc("teams/"+created.teamId).get()).data().inviteCode;
  assert.notEqual(rotated,team.inviteCode);
});
