import test from "node:test";
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {initializeApp} from "firebase-admin/app";
import {getFirestore,Timestamp} from "firebase-admin/firestore";
import {teamOperations} from "../src/teams.js";
import {shiftOperations} from "../src/shifts.js";
import {incidentOperations} from "../src/incidents.js";
import {recordOperations} from "../src/records.js";
import {attachmentOperations} from "../src/attachments.js";
if(process.env.GCLOUD_PROJECT!=="demo-alerta-equipe"||!process.env.FIRESTORE_EMULATOR_HOST?.startsWith("127.0.0.1:"))
  throw new Error("Somente Firestore Emulator local demo-alerta-equipe.");
initializeApp({projectId:"demo-alerta-equipe"});
const db=getFirestore(),shifts=shiftOperations(db),profiles=teamOperations(db);
const denied=(p,code)=>assert.rejects(p,e=>e.code===code);
async function fixture() {
  const teamId="review_"+randomUUID(),ids={};
  await db.doc(`teams/${teamId}`).set({enabled:true});
  for(const [key,op,role] of [["b","BRIGADISTA","member"],["v","VIGILANTE","member"],["g","AGP","member"],["h","AGP","member"],["o","BRIGADISTA","owner"]]) {
    const uid=teamId+"_"+key;ids[key]=uid;
    await db.doc(`users/${uid}`).set({name:key,operationalFunction:op});
    await db.doc(`devices/${uid}`).set({enabled:true});
    await db.doc(`teams/${teamId}/members/${uid}`).set({enabled:true,status:"active",role});
  }
  const req=(key,data={})=>({auth:{uid:ids[key]},data:{teamId,...data}});
  const start=async(key,post)=> (await shifts.start(req(key,{post}))).shift;
  const calls=[],messaging=()=>({async sendEachForMulticast(p){calls.push(p);return {responses:p.tokens.map(()=>({success:true}))};}});
  return {teamId,ids,req,start,calls,ops:incidentOperations(db,{messaging}),records:recordOperations(db,{messaging})};
}
const fields={title:"Registro teste",location:"Bloco",description:"Descrição"};

test("cargo permanente: todos os pares bloqueados nos cadastros legado e por instalação",async()=>{
  for(const current of ["BRIGADISTA","VIGILANTE","AGP"])for(const modern of [false,true]) {
    const uid="role_"+randomUUID(),base={name:"Pessoa",token:"t".repeat(80),...(modern?{deviceId:"d_"+randomUUID()}:{})};
    await profiles.profile({auth:{uid},data:{...base,operationalFunction:current}});
    for(const forged of ["BRIGADISTA","VIGILANTE","AGP"].filter(v=>v!==current))
      await denied(profiles.profile({auth:{uid},data:{...base,operationalFunction:forged}}),"failed-precondition");
    await denied(profiles.profile({auth:{uid},data:{...base,operationalFunction:"ADMIN"}}),"invalid-argument");
    assert.equal((await db.doc(`users/${uid}`).get()).data().operationalFunction,current);
  }
});

test("plantão: cargo vem do servidor; AGP escolhe posto válido e não ocupa posto duplicado",async()=>{
  const f=await fixture();
  await denied(f.start("g","MOVEL"),"invalid-argument");
  const first=await shifts.start(f.req("b",{post:"BATALHAO",operationalFunction:"AGP"}));
  assert.equal(first.shift.participants[0].operationalFunction,"BRIGADISTA");assert.equal(first.shift.participants[0].post,"");
  await f.start("g","BATALHAO");await denied(f.start("h","BATALHAO"),"already-exists");
  const result=await f.start("h","PORTARIA_VEICULOS");assert.equal(result.shiftId,first.shift.shiftId);
});

test("entradas simultâneas não criam dois plantões nem dois AGPs no mesmo posto",async()=>{
  const f=await fixture();
  const result=await Promise.allSettled([f.start("g","BATALHAO"),f.start("h","BATALHAO")]);
  assert.equal(result.filter(r=>r.status==="fulfilled").length,1);
  const all=await db.collection("shifts").where("teamId","==",f.teamId).get();assert.equal(all.size,1);
  assert.equal(all.docs[0].data().participants.length,1);
});

for(const [label,create,share,update,list,details,idKey,typeKey,type,collection] of [
  ["Ocorrência","createIncident","shareIncident","updateIncident","listIncidents","getIncidentDetails","incidentId","type","OTHER","incidents"],
  ["Ronda","createRoundReport","shareRoundReport","updateRoundReport","listRoundReports","getRoundReportDetails","reportId","findingType","IRREGULARITY","roundReports"],
]) {
  test(`${label}: exige shiftId, plantão ACTIVE e participante ativo`,async()=>{
    const f=await fixture(),shift=await f.start("b"),base={...fields,[typeKey]:type};
    await denied(f.ops[create](f.req("b",base)),"invalid-argument");
    await denied(f.ops[create](f.req("v",{...base,shiftId:shift.shiftId})),"permission-denied");
    await denied(f.ops[create](f.req("b",{...base,shiftId:shift.shiftId,ownerSector:"SEGURANCA"})),"invalid-argument");
    await f.start("v");await shifts.finish(f.req("b",{shiftId:shift.shiftId}));
    await denied(f.ops[create](f.req("b",{...base,shiftId:shift.shiftId})),"permission-denied");
    await shifts.finish(f.req("v",{shiftId:shift.shiftId}));
    await denied(f.ops[create](f.req("v",{...base,shiftId:shift.shiftId})),"failed-precondition");
  });
  test(`${label}: compartilhamento idempotente preserva autoria/setor; edição notifica sem criar demanda`,async()=>{
    const f=await fixture(),shift=await f.start("b");await f.start("v");
    await db.doc(`devices/${f.ids.v}`).update({fcmToken:"fake-sector-token"});
    const {record}=await f.ops[create](f.req("b",{...fields,[typeKey]:type,shiftId:shift.shiftId}));
    const key={[idKey]:record[idKey]};
    const integrated=collection==="roundReports";
    if(integrated) { assert.equal((await f.ops[details](f.req("v",key))).record[idKey],record[idKey]); assert.equal((await f.ops[details](f.req("g",key))).record[idKey],record[idKey]); }
    else await denied(f.ops[details](f.req("v",key)),"permission-denied");
    const attachment=attachmentOperations(db,{download:async()=>({ok:true})});
    await db.doc(`${collection}/${record[idKey]}`).update({attachments:[{attachmentId:"test",
      storagePath:`teams/${f.teamId}/${collection==="incidents"?"incident":"round"}/${record[idKey]}/test`}]});
    const attachmentRequest=f.req("v",{recordType:collection==="incidents"?"incident":"round",recordId:record[idKey],attachmentId:"test"});
    if(integrated) assert.deepEqual(await attachment.requestAttachmentDownload(attachmentRequest),{ok:true});
    else await denied(attachment.requestAttachmentDownload(attachmentRequest),"permission-denied");
    if(integrated) {
      await denied(f.ops[share](f.req("v",{...key,targetSector:"SEGURANCA"})),"failed-precondition");
      await denied(f.ops[share](f.req("b",{...key,targetSector:"SEGURANCA"})),"failed-precondition");
    } else {
      await denied(f.ops[share](f.req("v",{...key,targetSector:"SEGURANCA"})),"permission-denied");
      for(let i=0;i<2;i++)await f.ops[share](f.req("b",{...key,targetSector:"SEGURANCA"}));
      assert.equal(f.calls.length,1);assert.deepEqual(f.calls[0].tokens,["fake-sector-token"]);
    }
    const edited=await f.ops[update](f.req("o",{...key,...fields,[typeKey]:type,title:"Atualizada"}));
    assert.equal(edited.record.ownerSector,"BRIGADA");assert.equal(edited.record.authorUid,f.ids.b);assert.equal(edited.record.updatedByUid,f.ids.o);
    if(integrated) { assert.equal(f.calls.length,0);assert.equal(edited.record.shareHistory.length,0);assert.deepEqual(edited.record.sharedWithSectors,[]); }
    else { assert.equal(f.calls.length,2);assert.equal(f.calls[1].data.type,"record_updated");assert.equal(edited.record.shareHistory.length,1);assert.deepEqual(edited.record.sharedWithSectors,["SEGURANCA"]); }
    assert.equal((await f.ops[details](f.req("v",key))).record.title,"Atualizada");
    assert.deepEqual(await attachment.requestAttachmentDownload(attachmentRequest),{ok:true});
    assert.equal((await db.collection("demands").where("teamId","==",f.teamId).get()).size,0);
  });
  test(`${label}: paginação filtra setor sem perder a última página; contadores seguem autorização`,async()=>{
    const f=await fixture(),shift=await f.start("b");
    const {record}=await f.ops[create](f.req("b",{...fields,[typeKey]:type,shiftId:shift.shiftId}));
    await db.doc(`${collection}/${record[idKey]}`).delete(); // Only isolated emulator fixture.
    for(let i=0;i<5;i++)await db.doc(`${collection}/${f.teamId}_${i}`).set({...record,[idKey]:`${f.teamId}_${i}`,
      authorUid:f.ids.o,ownerSector:i<2?"SEGURANCA":"BRIGADA",createdAt:Timestamp.fromMillis(100-i),updatedAt:Timestamp.now()});
    const integrated=collection==="roundReports";
    const all=[];let cursor=null;do { const page=await f.ops[list](f.req("b",{limit:2,...(cursor?{beforeId:cursor}:{})}));all.push(...page.records);cursor=page.nextCursor; } while(cursor);
    assert.equal(new Set(all.map(r=>r[idKey])).size,integrated?5:3);
    const legacy=collection==="incidents"?"unreadIncidents":"unreadRounds";
    assert.equal((await f.records[legacy](f.req("b",{since:0}))).count,integrated?5:3);
    assert.equal((await f.records[legacy](f.req("v",{since:0}))).count,integrated?5:2);
    const canonical=await f.records.unreadState(f.req("b"));assert.equal(canonical.totalUnread,integrated?5:3);
    await db.doc(`users/${f.ids.b}`).update({operationalFunction:""});
    assert.equal((await f.records[legacy](f.req("b",{since:0}))).count,integrated?5:0);
  });
}

test("troca: autorização, permuta sem duplicidade, auditoria preservada além de 200 alterações",async()=>{
  const f=await fixture(),shift=await f.start("g","BATALHAO");await f.start("h","PORTARIA_VEICULOS");await f.start("v");
  const args={shiftId:shift.shiftId,targetUid:f.ids.g,post:"PORTARIA_VEICULOS"};
  await denied(shifts.updatePost(f.req("b",args)),"permission-denied");
  await denied(shifts.updatePost(f.req("g",{...args,role:"owner"})),"permission-denied");
  const history=Array.from({length:200},(_,i)=>({targetUid:"historical",editedAt:i}));
  await db.doc(`shifts/${shift.shiftId}`).update({postHistory:history});
  const result=await shifts.updatePost(f.req("v",args));
  assert.deepEqual(result.shift.postHistory.slice(0,200),history);assert.equal(result.changes.length,2);
  const entry=result.changes[0];assert.equal(entry.targetUid,f.ids.g);assert.equal(entry.fromPost,"BATALHAO");assert.equal(entry.toPost,"PORTARIA_VEICULOS");
  assert.equal(entry.editedByUid,f.ids.v);assert.equal(entry.editedByFunction,"VIGILANTE");assert.equal(entry.editedByRole,"member");assert.ok(entry.editedAt);
  assert.equal(new Set(result.shift.participants.filter(p=>p.operationalFunction==="AGP").map(p=>p.post)).size,2);
});

test("cobertura é temporária: não muda posto, não acumula responsável e impede troca/saída durante cobertura",async()=>{
  const f=await fixture(),shift=await f.start("g","BATALHAO");await f.start("h","PORTARIA_VEICULOS");await f.start("v");
  const args={shiftId:shift.shiftId,targetUid:f.ids.g,reason:"JANTAR"};
  const {coverage,shift:covered}=await shifts.startCoverage(f.req("v",args));
  assert.equal(coverage.targetUid,f.ids.g);assert.equal(coverage.post,"BATALHAO");assert.equal(coverage.coveredByUid,f.ids.v);
  assert.equal(coverage.recordedByUid,f.ids.v);assert.ok(coverage.startedAt);assert.equal(coverage.endedAt,null);
  assert.equal(covered.participants.find(p=>p.uid===f.ids.g).post,"BATALHAO");
  await denied(shifts.startCoverage(f.req("v",{...args,targetUid:f.ids.h})),"already-exists");
  await denied(shifts.updatePost(f.req("v",{...args,post:"PORTARIA_VEICULOS"})),"failed-precondition");
  await denied(shifts.finish(f.req("g",{shiftId:shift.shiftId})),"failed-precondition");
  const ended=await shifts.finishCoverage(f.req("v",{shiftId:shift.shiftId,coverageId:coverage.coverageId}));assert.ok(ended.shift.coverages[0].endedAt);
  await shifts.finish(f.req("g",{shiftId:shift.shiftId}));
  await denied(shifts.finish(f.req("g",{shiftId:shift.shiftId})),"failed-precondition");
});

test("relatórios separados no servidor, inclusive registros compartilhados e visão admin",async()=>{
  const f=await fixture(),shift=await f.start("b");await f.start("v");await f.start("g","BATALHAO");
  const brigada=await f.ops.createIncident(f.req("b",{...fields,type:"OTHER",shiftId:shift.shiftId}));
  await f.ops.createRoundReport(f.req("g",{...fields,findingType:"IRREGULARITY",shiftId:shift.shiftId}));
  await f.ops.shareIncident(f.req("b",{incidentId:brigada.record.incidentId,targetSector:"SEGURANCA"}));
  for(const [key,sector,ops] of [["b","BRIGADA",["BRIGADISTA"]],["v","SEGURANCA",["VIGILANTE","AGP"]]]) {
    const report=await shifts.report(f.req(key,{shiftId:shift.shiftId,sector}));assert.equal(report.events.length,1);
    assert.ok(report.shift.participants.every(p=>ops.includes(p.operationalFunction)));
    assert.ok(report.events.every(e=>ops.includes(e.authorOperationalFunction)));
  }
  await denied(shifts.report(f.req("b",{shiftId:shift.shiftId,sector:"SEGURANCA"})),"permission-denied");
  assert.equal((await shifts.report(f.req("o",{shiftId:shift.shiftId,sector:"SEGURANCA"}))).roundCount,1);
});


test("brigadista intermediário é nominal e não recebe identidade operacional",async()=>{
  const f=await fixture(),shift=await f.start("b");
  const startedAt=shift.startedAt,endedAt=startedAt+60*60*1000;
  await denied(shifts.addIntermediate(f.req("v",{shiftId:shift.shiftId,name:"Roberta",startedAt,endedAt})),"permission-denied");
  const result=await shifts.addIntermediate(f.req("o",{shiftId:shift.shiftId,name:"Roberta",startedAt,endedAt}));
  const guest=result.shift.participants.find(p=>p.intermediate===true);
  assert.ok(guest);assert.equal(guest.name,"Roberta");assert.equal(guest.operationalFunction,"BRIGADISTA");
  assert.equal(guest.role,"guest");assert.match(guest.uid,/^guest:/);
  assert.equal(result.shift.participantUids.includes(guest.uid),false);
});
