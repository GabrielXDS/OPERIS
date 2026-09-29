import test from "node:test";
import assert from "node:assert/strict";
import {Timestamp} from "firebase-admin/firestore";
import {teamOperations} from "../src/teams.js";

// In-memory Firestore contract double for teamOperations.details.
function teamFixture() {
  const data = new Map();
  const snap = path => ({id:path.split("/").at(-1),exists:data.has(path),data:()=>data.get(path)});
  const db = {
    doc: path => ({path,get:async()=>snap(path)}),
    async getAll(...refs) { return refs.map(r => snap(r.path)); },
    collection: name => ({where: (field, op, value) => ({limit: n => ({async get() {
      const docs=[...data.keys()]
        .filter(p=>p.startsWith(name+"/") && p.split("/").length===name.split("/").length+1 && data.get(p)?.status===value)
        .map(snap).slice(0,n);
      return {docs,size:docs.length};
    }})})})
  };
  return {data,ops:teamOperations(db)};
}
function seed(now=Date.now()) {
  const f=teamFixture();
  const device=id=>({enabled:true,fcmToken:id.repeat(80),lastSeenAt:Timestamp.fromMillis(now-1000),
    availability:"available",notificationsEnabled:true,channelReady:true,audioReady:true});
  f.data.set("teams/a",{name:"Brigada",ownerUid:"o",inviteCode:"ABCDEFGH",enabled:true,active:true});
  f.data.set("teamInvites/ABCDEFGH",{active:true,expiresAt:Timestamp.fromMillis(now+86400000)});
  for(const [id,role] of [["o","owner"],["adm","admin"],["u","member"]]) {
    f.data.set(`teams/a/members/${id}`,{uid:id,role,status:"active",enabled:true});
    f.data.set(`devices/${id}`,device(id));
    f.data.set(`users/${id}`,{name:id});
  }
  return f;
}
const req=(uid,teamId="a")=>({auth:{uid},data:{teamId}});
const membersOf=result=>result.members;

test("details: owner recebe última comunicação dos integrantes",async()=>{
  const {ops}=seed();
  const result=await ops.details(req("o"));
  assert.equal(membersOf(result).length,3);
  for(const m of membersOf(result)) {
    assert.equal("lastSeenAt" in m,true);
    assert.equal(typeof m.lastSeenAt,"number");
  }
});
test("details: admin recebe última comunicação dos integrantes",async()=>{
  const {ops}=seed();
  const result=await ops.details(req("adm"));
  assert.equal(membersOf(result).length,3);
  for(const m of membersOf(result)) {
    assert.equal("lastSeenAt" in m,true);
    assert.equal(typeof m.lastSeenAt,"number");
  }
});
test("details: membro comum não recebe última comunicação, mas mantém status operacional",async()=>{
  const {ops}=seed();
  const result=await ops.details(req("u"));
  assert.equal(membersOf(result).length,3);
  for(const m of membersOf(result)) {
    assert.equal("lastSeenAt" in m,false);
    assert.equal(m.status,"online");
    assert.equal(typeof m.appReady,"boolean");
  }
  assert.equal(result.onlineCount,3);
  assert.equal(result.offlineCount,0);
  assert.equal(result.readyCount,3);
});