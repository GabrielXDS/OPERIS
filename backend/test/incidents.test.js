import test from "node:test";
import assert from "node:assert/strict";
import {Timestamp} from "firebase-admin/firestore";
import {incidentOperations,INCIDENT_TYPES,ROUND_TYPES,cleanText,canView} from "../src/incidents.js";

// In-memory Firestore contract double: transactions commit only on success.
function fixture() {
 const data=new Map();let seq=0;
 const snap=path=>({id:path.split('/').at(-1),exists:data.has(path),data:()=>data.get(path)});
 const db={doc:path=>({path}),collection:name=>({doc:()=>({path:`${name}/id${++seq}`,id:`id${seq}`}),where:(_,op,team)=>{
   const q={name,team,n:50,cursor:null,orderBy(){return this;},limit(n){this.n=n;return this;},startAfter(s){this.cursor=s.id;return this;}};return q;
 }}),async runTransaction(fn){
   const writes=[];let writing=false;
   const tx={async get(ref){assert.equal(writing,false,'all reads before writes');if(ref.path)return snap(ref.path);
     let docs=[...data.keys()].filter(p=>p.startsWith(ref.name+'/')&&data.get(p).teamId===ref.team).map(snap).sort((a,b)=>b.data().createdAt.toMillis()-a.data().createdAt.toMillis()||b.id.localeCompare(a.id));
     if(ref.cursor)docs=docs.slice(docs.findIndex(s=>s.id===ref.cursor)+1);return {docs:docs.slice(0,ref.n)};
   },async getAll(...refs){return Promise.all(refs.map(r=>this.get(r)));},set(ref,value){writing=true;writes.push([ref.path,value]);},create(ref,value){assert.equal(data.has(ref.path),false);this.set(ref,value);}};
   const r=await fn(tx);writes.forEach(([p,v])=>data.set(p,v));return r;
 }};
 data.set('teams/a',{enabled:true});data.set('teams/b',{enabled:true});
 data.set('teams/a/members/u',{status:'active',enabled:true});data.set('teams/b/members/u',{status:'active',enabled:true});
 data.set('devices/u',{enabled:true});data.set('users/u',{name:'Gabriel',operationalFunction:'BRIGADISTA'});
 data.set('shifts/shift1',{shiftId:'shift1',teamId:'a',status:'ACTIVE',startedAt:Timestamp.now(),participants:[{uid:'u',endedAt:null}]});
 return {data,ops:incidentOperations(db)};
}
const req=data=>({auth:{uid:'u'},data});
const valid={teamId:'a',shiftId:'shift1',title:'Alarme',location:'Bloco A',description:'Evento',};
const rejects=(promise,code)=>assert.rejects(promise,e=>e.code===code);
for(const [label,create,list,details,types] of [
 ['incident','createIncident','listIncidents','getIncidentDetails',INCIDENT_TYPES],
 ['round','createRoundReport','listRoundReports','getRoundReportDetails',ROUND_TYPES]]) {
 const idKey=label==='incident'?'incidentId':'reportId',typeKey=label==='incident'?'type':'findingType',actionKey=label==='incident'?'actionsTaken':'immediateAction';
 test(`${label}: server fields, all enums, list and details`,async()=>{
  for(const type of types){const {ops}=fixture();const {record}=await ops[create](req({...valid,[typeKey]:type}));assert.equal(record.authorUid,'u');assert.equal(record.authorName,'Gabriel');assert.equal(record.status,label==='incident'?'OPEN':'IDENTIFIED');assert.equal(record.teamId,'a');assert.equal(record.editCount,0);assert.equal(record[actionKey],'');assert.equal(record[typeKey],type);assert.equal(typeof record[idKey],'string');assert.equal(record.organizationId,null);assert.equal(record.siteId,null);assert.deepEqual(record.attachments,[]);assert.ok(record.createdAt>0);assert.equal(record.updatedAt,record.createdAt);assert.deepEqual((await ops[details](req({teamId:'a',[idKey]:record[idKey]}))).record,record);

  }
 });
 test(`${label}: auth and inactive membership denied on all endpoints`,async()=>{
  for(const status of ['pending','rejected','removed','active'])for(const action of [create,list,details]) {
   const {ops,data}=fixture();const payload=action===create?{...valid,[typeKey]:types[0]}:action===list?{teamId:'a'}:{teamId:'a',[idKey]:'missing'};
   await rejects(ops[action]({data:payload}),'unauthenticated');
   data.set('teams/a/members/u',{enabled:status!=='active',status});await rejects(ops[action](req(payload)),'permission-denied');
  }
 });
 test(`${label}: team isolation, cursor isolation, bounded pagination`,async()=>{
  const {ops,data}=fixture();const {record}=await ops[create](req({...valid,[typeKey]:types[0]}));
  await rejects(ops[details](req({teamId:'b',[idKey]:record[idKey]})),'not-found');
  await rejects(ops[list](req({teamId:'b',beforeId:record[idKey]})),'not-found');
  assert.deepEqual((await ops[list](req({teamId:'b'}))).records,[]);
  const original=data.get(`${label==='incident'?'incidents':'roundReports'}/${record[idKey]}`);
  data.set(`${label==='incident'?'incidents':'roundReports'}/newer`,{...original,[idKey]:'newer',createdAt:Timestamp.fromMillis(original.createdAt.toMillis()+1)});
  const first=await ops[list](req({teamId:'a',limit:1}));assert.equal(first.records[0][idKey],'newer');assert.equal(first.nextCursor,'newer');
  const second=await ops[list](req({teamId:'a',limit:1,beforeId:first.nextCursor}));assert.equal(second.records[0][idKey],record[idKey]);assert.equal(second.nextCursor,null);
  for(const limit of [0,51,1.5,'2'])await rejects(ops[list](req({teamId:'a',limit})),'invalid-argument');
 });
 test(`${label}: rejects forged fields and malformed input`,async()=>{
  const {ops}=fixture();
  for(const key of ['authorUid','authorName','owner','role','status','membership','attachments','editCount','organizationId','siteId','createdAt','updatedAt',idKey])await rejects(ops[create](req({...valid,[typeKey]:types[0],[key]:'forged'})),'invalid-argument');
  for(const change of [{[typeKey]:'admin'},{teamId:'../b'},{title:' '},{title:'ab'},{title:'x'.repeat(81)},{location:'x'.repeat(101)},{[actionKey]:'x'.repeat(2001)},{location:4},{description:'x'.repeat(2001)},{[actionKey]:[]},{title:'<b></b>'}])await rejects(ops[create](req({...valid,[typeKey]:types[0],...change})),'invalid-argument');
 });
 test(`${label}: rate limits and disabled team/device`,async()=>{
  const {ops,data}=fixture();for(let i=0;i<10;i++)await ops[create](req({...valid,[typeKey]:types[0]}));await rejects(ops[create](req({...valid,[typeKey]:types[0]})),'resource-exhausted');
  for(let i=0;i<60;i++)await ops[list](req({teamId:'a'}));await rejects(ops[list](req({teamId:'a'})),'resource-exhausted');
  for(const path of ['teams/a','devices/u']){const f=fixture();f.data.set(path,{enabled:false});await rejects(f.ops[create](req({...valid,[typeKey]:types[0]})),'permission-denied');}
  assert.equal([...data.keys()].filter(k=>k.startsWith(label==='incident'?'incidents/':'roundReports/')).length,10);
 });
}
test('cliente moderno usa installationId sem depender de devices/{uid}',async()=>{
 const {ops,data}=fixture();
 data.delete('devices/u');data.set('devices/install-u',{enabled:true,uid:'u'});
 const modern=payload=>({auth:{uid:'u'},data:{...payload,deviceId:'install-u'}});
 assert.deepEqual((await ops.listIncidents(modern({teamId:'a'}))).records,[]);
 assert.deepEqual((await ops.listRoundReports(modern({teamId:'a'}))).records,[]);
 data.set('devices/install-u',{enabled:false,uid:'u'});
 await rejects(ops.listIncidents(modern({teamId:'a'})),'permission-denied');
});
test('cliente moderno consegue criar e excluir registros com installationId',async()=>{
 const {ops,data}=fixture();data.delete('devices/u');data.set('devices/install-u',{enabled:true,uid:'u'});
 const modern=payload=>({auth:{uid:'u'},data:{...payload,deviceId:'install-u'}});
 const incident=await ops.createIncident(modern({...valid,type:'OTHER'}));
 assert.equal((await ops.softDeleteIncident(modern({teamId:'a',incidentId:incident.record.incidentId}))).record.deleted,true);
 const round=await ops.createRoundReport(modern({...valid,findingType:'IRREGULARITY'}));
 assert.equal((await ops.softDeleteRoundReport(modern({teamId:'a',reportId:round.record.reportId}))).record.deleted,true);
});
test('ronda é integrada entre Brigada e Segurança; ocorrência continua isolada',async()=>{
 const {ops,data}=fixture();
 data.set('teams/a/members/v',{status:'active',enabled:true});data.set('devices/v',{enabled:true});data.set('users/v',{name:'Vigilante',operationalFunction:'VIGILANTE'});
 const round=await ops.createRoundReport(req({...valid,findingType:'IRREGULARITY'}));
 const incident=await ops.createIncident(req({...valid,type:'OTHER'}));
 const vreq=data=>({auth:{uid:'v'},data});
 assert.equal((await ops.getRoundReportDetails(vreq({teamId:'a',reportId:round.record.reportId}))).record.reportId,round.record.reportId);
 assert.equal((await ops.listRoundReports(vreq({teamId:'a'}))).records.length,1);
 await rejects(ops.getIncidentDetails(vreq({teamId:'a',incidentId:incident.record.incidentId})),'permission-denied');
 await rejects(ops.shareRoundReport(req({teamId:'a',reportId:round.record.reportId,targetSector:'SEGURANCA'})),'failed-precondition');
 assert.equal(canView({authorUid:'b',ownerSector:'BRIGADA'},'v','VIGILANTE','member',true),true);
});

test('sanitizes control characters and markup',()=>{assert.equal(cleanText(' <b>Teste</b>\u0000 ',120),'Teste');assert.equal(cleanText('',120,true),'');});

test('sem cargo válido não cria registro com ownerSector nulo',async()=>{
 const {ops,data}=fixture();data.set('users/u',{name:'Gabriel'});
 await rejects(ops.createIncident(req({...valid,type:'OTHER'})),'failed-precondition');
 await rejects(ops.createRoundReport(req({...valid,findingType:'IRREGULARITY'})),'failed-precondition');
});

test('isolamento simétrico de setores, compartilhamento e legado desconhecido',()=>{
 for(const [ownerSector,allowed,denied] of [['BRIGADA',['BRIGADISTA'],['VIGILANTE','AGP']],['SEGURANCA',['VIGILANTE','AGP'],['BRIGADISTA']]]) {
  const record={authorUid:'author',ownerSector};
  for(const op of allowed)assert.equal(canView(record,'viewer',op,'member'),true);
  for(const op of denied)assert.equal(canView(record,'viewer',op,'member'),false);
  const shared={...record,sharedWithSectors:[ownerSector==='BRIGADA'?'SEGURANCA':'BRIGADA']};
  for(const op of denied)assert.equal(canView(shared,'viewer',op,'member'),true);
 }
 const unknown={authorUid:'author'};
 assert.equal(canView(unknown,'viewer','BRIGADISTA','member'),false);
 assert.equal(canView(unknown,'author','','member'),true);
 assert.equal(canView(unknown,'viewer','','admin'),true);
});

for(const [label,create,update,softDelete,details,list,types] of [
 ['incident','createIncident','updateIncident','softDeleteIncident','getIncidentDetails','listIncidents',INCIDENT_TYPES],
 ['round','createRoundReport','updateRoundReport','softDeleteRoundReport','getRoundReportDetails','listRoundReports',ROUND_TYPES]]) {
 const idKey=label==='incident'?'incidentId':'reportId',typeKey=label==='incident'?'type':'findingType',actionKey=label==='incident'?'actionsTaken':'immediateAction';
 test(`${label}: update by author preserves record and writes audit trail`,async()=>{
  const {ops}=fixture();
  const {record}=await ops[create](req({...valid,[typeKey]:types[0]}));
  const updated=await ops[update](req({teamId:'a',[idKey]:record[idKey],[typeKey]:types[1],title:'Nova atualização',location:'Bloco B',description:'Ajustado pelo autor',[actionKey]:'Medida nova'}));
  assert.equal(updated.record[idKey],record[idKey]);
  assert.equal(updated.record[typeKey],types[1]);
  assert.equal(updated.record.title,'Nova atualização');
  assert.equal(updated.record.location,'Bloco B');
  assert.equal(updated.record.description,'Ajustado pelo autor');
  assert.equal(updated.record[actionKey],'Medida nova');
  assert.equal(updated.record.updatedByUid,'u');
  assert.equal(updated.record.updatedByName,'Gabriel');
  assert.equal(updated.record.editCount,1);
  assert.equal(updated.record.createdAt,record.createdAt);
  assert.ok(updated.record.updatedAt>=record.updatedAt);
  const entry=updated.record.editHistory.at(-1);
  assert.equal(entry.action,'edit');assert.equal(entry.byUid,'u');assert.equal(entry.byName,'Gabriel');assert.ok(entry.at>0);
  // attachments and identity are never reassigned by an edit
  assert.deepEqual(updated.record.attachments,[]);assert.equal(updated.record.authorUid,'u');
 });
 test(`${label}: mutation authorization enforced server-side`,async()=>{
  const {ops,data}=fixture();
  data.set(`teams/a/members/w`,{status:'active',enabled:true});
  data.set(`devices/w`,{enabled:true});
  data.set(`users/w`,{name:'Wesley'});
  data.set(`teams/a/members/o`,{status:'active',enabled:true,role:'owner'});
  data.set(`devices/o`,{enabled:true});
  data.set(`users/o`,{name:'Osvaldo'});
  const {record}=await ops[create](req({...valid,[typeKey]:types[0]}));
  const base={teamId:'a',[idKey]:record[idKey],[typeKey]:types[0],title:'Nova',location:'L',description:'D',[actionKey]:''};
  await rejects(ops[update]({auth:{uid:'w'},data:base}),'permission-denied');
  await rejects(ops[softDelete]({auth:{uid:'w'},data:{teamId:'a',[idKey]:record[idKey]}}),'permission-denied');
  assert.equal((await ops[update]({auth:{uid:'o'},data:{...base,title:'Pelo dono'}})).record.updatedByUid,'o');
  assert.equal((await ops[softDelete]({auth:{uid:'o'},data:{teamId:'a',[idKey]:record[idKey]}})).record.deleted,true);
 });
 test(`${label}: soft delete hides by default, lists with deletedOnly, stays read-only`,async()=>{
  const {ops}=fixture();
  const {record}=await ops[create](req({...valid,[typeKey]:types[0]}));
  assert.deepEqual((await ops[list](req({teamId:'a'}))).records.map(r=>r[idKey]),[record[idKey]]);
  const del=await ops[softDelete](req({teamId:'a',[idKey]:record[idKey]}));
  assert.equal(del.record.deleted,true);assert.ok(del.record.deletedAt>0);assert.equal(del.record.deletedByUid,'u');assert.equal(del.record.deletedByName,'Gabriel');
  assert.deepEqual((await ops[list](req({teamId:'a'}))).records,[]);
  const gone=(await ops[list](req({teamId:'a',deletedOnly:true}))).records;
  assert.equal(gone.length,1);assert.equal(gone[0][idKey],record[idKey]);assert.equal(gone[0].deleted,true);
  assert.equal((await ops[details](req({teamId:'a',[idKey]:record[idKey]}))).record.deleted,true);
  await rejects(ops[softDelete](req({teamId:'a',[idKey]:record[idKey]})),'failed-precondition');
  await rejects(ops[update](req({teamId:'a',[idKey]:record[idKey],[typeKey]:types[0],title:'Nova',location:'L',description:'D',[actionKey]:''})),'failed-precondition');
  await rejects(ops[list](req({teamId:'a',deletedOnly:'yes'})),'invalid-argument');
 });
}


if(process.env.FIRESTORE_EMULATOR_HOST && process.env.GCLOUD_PROJECT==='demo-alerta-equipe') {
 test('real Firestore: operations, indexes, isolation and concurrent rate limit',async()=>{
  const {initializeApp,getApps}=await import('firebase-admin/app');
  if(!getApps().length)initializeApp({projectId:'demo-alerta-equipe'});
  const {getFirestore}=await import('firebase-admin/firestore');const db=getFirestore();
  const uid='ops_'+Date.now(),teamId=uid,shiftId=uid+'_shift',ops=incidentOperations(db),request=d=>({auth:{uid},data:{teamId,...d}});
  await db.doc('teams/'+teamId).set({enabled:true});await db.doc(`teams/${teamId}/members/${uid}`).set({enabled:true,status:'active'});
  await db.doc('devices/'+uid).set({enabled:true});await db.doc('users/'+uid).set({name:'Teste'});
  await db.doc('shifts/'+shiftId).set({shiftId,teamId,status:'ACTIVE',startedAt:Timestamp.now(),participants:[{uid,endedAt:null}]});
  for(const [create,list,details,type] of [['createIncident','listIncidents','getIncidentDetails','ALARM_TRIGGER'],['createRoundReport','listRoundReports','getRoundReportDetails','OPEN_DOOR']]){
   const idKey=create==='createIncident'?'incidentId':'reportId',typeKey=create==='createIncident'?'type':'findingType';
   const {record}=await ops[create](request({...valid,teamId,shiftId,[typeKey]:type}));
   assert.equal((await ops[list](request({limit:1}))).records[0][idKey],record[idKey]);
   assert.equal((await ops[details](request({[idKey]:record[idKey]}))).record.authorUid,uid);
   assert.deepEqual((await ops[list](request({beforeId:record[idKey]}))).records,[]);
  }
  const outcomes=await Promise.allSettled(Array.from({length:10},()=>ops.createIncident(request({...valid,teamId,shiftId,type:'ALARM_TRIGGER'}))));
  assert.equal(outcomes.filter(x=>x.status==='fulfilled').length,8);
  assert.ok(outcomes.filter(x=>x.status==='rejected').every(x=>x.reason.code==='resource-exhausted'));
  await db.doc(`teams/${teamId}/members/${uid}`).update({status:'pending'});
  await rejects(ops.listIncidents(request({})),'permission-denied');
 });
}
