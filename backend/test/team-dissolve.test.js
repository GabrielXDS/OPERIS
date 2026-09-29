import test from "node:test";
import assert from "node:assert/strict";
import {teamOperations} from "../src/teams.js";

function fixture(role="owner", deviceEnabled=true) {
  const data=new Map([
    ["teams/a",{name:"Equipe A",ownerUid:"u",inviteCode:"ABCDEFGH",enabled:true,active:true}],
    ["teamInvites/ABCDEFGH",{active:true}],
    ["teams/a/members/u",{uid:"u",role,status:"active",enabled:true}],
    ["teams/a/members/v",{uid:"v",role:"member",status:"active",enabled:true}],
    ["users/u",{name:"Ana",teamIds:["a"]}],
    ["users/v",{name:"Bruno",teamIds:["a"]}],
    ["devices/u",{enabled:deviceEnabled}],
    ["devices/v",{enabled:true}],
  ]);
  const batchWrites=[];
  const ref=path=>({path,collection:name=>({doc:id=>ref(`${path}/${name}/${id}`)})});
  const snap=path=>({id:path.split("/").at(-1),ref:ref(path),exists:data.has(path),data:()=>data.get(path)});
  const apply=(kind,path,value,merge=false)=>{
    if(kind==="update" && !data.has(path)) throw new Error("missing update "+path);
    data.set(path,merge||kind==="update"?{...(data.get(path)||{}),...value}:value);
  };
  const db={
    doc:ref,
    collection:name=>({limit:n=>({async get(){const docs=[...data.keys()].filter(p=>p.startsWith(name+"/")&&p.split("/").length===name.split("/").length+1).slice(0,n).map(snap);return{docs,size:docs.length}}})}),
    async getAll(...refs){return refs.map(r=>snap(r.path))},
    async runTransaction(fn){
      const writes=[];
      const tx={
        async get(r){return snap(r.path)},
        async getAll(...refs){return refs.map(r=>snap(r.path))},
        set(r,v,opts){writes.push(["set",r.path,v,opts?.merge===true])},
        update(r,v){writes.push(["update",r.path,v,true])},
      };
      const out=await fn(tx);writes.forEach(w=>apply(...w));return out;
    },
    batch(){
      const writes=[];
      return {set(r,v,opts){writes.push(["set",r.path,v,opts?.merge===true]);batchWrites.push([r.path,v])},async commit(){writes.forEach(w=>apply(...w))}};
    }
  };
  return {data,batchWrites,ops:teamOperations(db),req:{auth:{uid:"u"},data:{teamId:"a"}}};
}

const denied=(promise,code)=>assert.rejects(promise,e=>e.code===code);

test("dissolveTeam: proprietário desativa equipe, revoga convite e desativa vínculos",async()=>{
  const f=fixture();
  assert.deepEqual(await f.ops.dissolve(f.req),{dissolved:true,teamId:"a"});
  assert.equal(f.data.get("teams/a").enabled,false);
  assert.equal(f.data.get("teams/a").active,false);
  assert.equal(f.data.get("teams/a").dissolvedBy,"u");
  assert.equal(f.data.get("teamInvites/ABCDEFGH").active,false);
  assert.equal(f.data.get("teams/a/members/u").status,"team_dissolved");
  assert.equal(f.data.get("teams/a/members/v").status,"team_dissolved");
  assert.deepEqual(f.batchWrites.filter(([p])=>p.startsWith("users/")).map(([p])=>p).sort(),["users/u","users/v"]);
});

test("dissolveTeam: admin ou membro não pode desfazer equipe",async()=>{
  for(const role of ["admin","member"]) {
    const f=fixture(role);
    await denied(f.ops.dissolve(f.req),"permission-denied");
    assert.equal(f.data.get("teams/a").active,true);
  }
});

test("dissolveTeam: aparelho inativo bloqueia ação",async()=>{
  const f=fixture("owner",false);
  await denied(f.ops.dissolve(f.req),"permission-denied");
  assert.equal(f.data.get("teams/a").active,true);
});

test("removeMember: proprietário remove membro ativo e encerra o vínculo",async()=>{
  const f=fixture("owner");
  const req={...f.req,data:{teamId:"a",uid:"v"}};
  assert.deepEqual(await f.ops.removeMember(req),{removed:true,uid:"v"});
  const member=f.data.get("teams/a/members/v");
  assert.equal(member.status,"removed");
  assert.equal(member.enabled,false);
  assert.equal(member.removedBy,"u");
});

test("removeMember: administrador remove membro comum",async()=>{
  const f=fixture("admin");
  const req={...f.req,data:{teamId:"a",uid:"v"}};
  assert.deepEqual(await f.ops.removeMember(req),{removed:true,uid:"v"});
  assert.equal(f.data.get("teams/a/members/v").status,"removed");
});

test("removeMember: membro comum não pode remover outro integrante",async()=>{
  const f=fixture("member");
  await denied(f.ops.removeMember({...f.req,data:{teamId:"a",uid:"v"}}),"permission-denied");
  assert.equal(f.data.get("teams/a/members/v").status,"active");
});

test("removeMember: administrador não remove outro administrador e ninguém remove a si mesmo",async()=>{
  const f=fixture("admin");
  f.data.set("teams/a/members/v",{uid:"v",role:"admin",status:"active",enabled:true});
  await denied(f.ops.removeMember({...f.req,data:{teamId:"a",uid:"v"}}),"permission-denied");
  await denied(f.ops.removeMember({...f.req,data:{teamId:"a",uid:"u"}}),"invalid-argument");
});