import test from 'node:test';
import assert from 'node:assert/strict';
import {Timestamp} from 'firebase-admin/firestore';
import {attachmentOperations,privateStorage,MAX_ATTACHMENT_BYTES} from '../src/attachments.js';
function fixture() {
 const data=new Map([['teams/a',{enabled:true}],['teams/b',{enabled:true}],['teams/a/members/u',{enabled:true,status:'active'}],['devices/u',{enabled:true}],['incidents/i',{teamId:'a',authorUid:'u',ownerSector:'BRIGADA',attachments:[]}],['roundReports/r',{teamId:'a',authorUid:'u',ownerSector:'BRIGADA',attachments:[]}]]);
 const objects=new Map();let now=100000,queue=Promise.resolve();
 const db={doc:path=>({path}),runTransaction(fn){const run=queue.then(async()=>{const writes=[];let writing=false;const tx={async get(ref){assert.equal(writing,false);return {exists:data.has(ref.path),data:()=>data.get(ref.path)};},async getAll(...refs){return Promise.all(refs.map(r=>this.get(r)));},set(ref,v){writing=true;writes.push(()=>data.set(ref.path,v));},create(ref,v){assert.ok(!data.has(ref.path));this.set(ref,v);},update(ref,v){writing=true;writes.push(()=>data.set(ref.path,{...data.get(ref.path),...v}));}};const r=await fn(tx);writes.forEach(f=>f());return r;});queue=run.catch(()=>{});return run;}};
 const storage={async upload(t){return {uploadUrl:'https://example.invalid/'+t.ticketId,headers:{'Content-Type':t.contentType}};},async inspect(path){return objects.get(path);},async download(a,expiresAt){return {downloadUrl:'https://example.invalid/'+a.attachmentId,expiresAt};}};
 const ops=attachmentOperations(db,storage,()=>now),req=(d,uid='u')=>({auth:uid?{uid}:null,data:d});
 const payload={teamId:'a',recordType:'incident',recordId:'i',fileName:'ficticio.pdf',contentType:'application/pdf'};
 async function ticket(change={}){return ops.requestAttachmentUpload(req({...payload,...change}));}
 function upload(t,change={}){objects.set(t.storagePath,{name:t.storagePath,size:100,contentType:'application/pdf',generation:'1',validSignature:true,...change});}
 return {data,objects,ops,req,payload,ticket,upload,storage,expire(){now+=600001;}};
}
const denied=(p,code)=>assert.rejects(p,e=>e.code===code);
test('anexos aceitam installationId moderno sem devices/{uid}',async()=>{
 const f=fixture();f.data.delete('devices/u');f.data.set('devices/install-u',{enabled:true,uid:'u'});
 const modern=d=>f.req({...d,deviceId:'install-u'});
 const t=await f.ops.requestAttachmentUpload(modern(f.payload));f.upload(t);
 const {attachment}=await f.ops.finalizeAttachment(modern({ticketId:t.ticketId}));
 assert.equal(attachment.fileName,'ficticio.pdf');
 const ref=await f.ops.requestAttachmentDownload(modern({teamId:'a',recordType:'incident',recordId:'i',attachmentId:t.ticketId}));
 assert.ok(ref.downloadUrl);
});
test('anexo de outro setor exige compartilhamento, inclusive download',async()=>{
 const f=fixture(),t=await f.ticket();f.upload(t);await f.ops.finalizeAttachment(f.req({ticketId:t.ticketId}));
 f.data.set('teams/a/members/v',{enabled:true,status:'active'});f.data.set('devices/v',{enabled:true});
 f.data.set('users/v',{name:'V',operationalFunction:'VIGILANTE'});
 const d={teamId:'a',recordType:'incident',recordId:'i',attachmentId:t.ticketId};
 await denied(f.ops.requestAttachmentDownload(f.req(d,'v')),'permission-denied');
 f.data.get('incidents/i').sharedWithSectors=['SEGURANCA'];
 assert.ok((await f.ops.requestAttachmentDownload(f.req(d,'v'))).downloadUrl);
});
for(const method of ['requestAttachmentUpload','finalizeAttachment','requestAttachmentDownload'])test(method+': Auth obrigatório',async()=>{const f=fixture();await denied(f.ops[method](f.req({},null)),'unauthenticated');});
for(const status of ['pending','rejected','removed','disabled'])test(status+' bloqueado em upload/finalize/download',async()=>{const f=fixture(),t=await f.ticket();f.upload(t);f.data.set('teams/a/members/u',{status,enabled:status!=='disabled'});await denied(f.ticket(),'permission-denied');await denied(f.ops.finalizeAttachment(f.req({ticketId:t.ticketId})),'permission-denied');await denied(f.ops.requestAttachmentDownload(f.req({...f.payload,attachmentId:t.ticketId,fileName:undefined})),'invalid-argument');await denied(f.ops.requestAttachmentDownload(f.req({teamId:'a',recordType:'incident',recordId:'i',attachmentId:t.ticketId})),'permission-denied');});
test('outra equipe e registro inexistente',async()=>{const f=fixture();await denied(f.ticket({teamId:'b'}),'permission-denied');await denied(f.ticket({recordId:'missing'}),'not-found');f.data.set('teams/b/members/u',{enabled:true,status:'active'});await denied(f.ticket({teamId:'b'}),'not-found');});
test('limite 3 inclui reservas concorrentes',async()=>{const f=fixture();const r=await Promise.allSettled([1,2,3,4].map(()=>f.ticket()));assert.equal(r.filter(x=>x.status==='fulfilled').length,3);assert.equal(r.find(x=>x.status==='rejected').reason.code,'resource-exhausted');});
for(const [label,change] of [['maior que 10 MB',{size:MAX_ATTACHMENT_BYTES+1}],['MIME inválido',{contentType:'text/html'}],['bytes inválidos',{validSignature:false}],['path adulterado',{name:'evil/path'}]])test(label+' bloqueado no objeto real',async()=>{const f=fixture(),t=await f.ticket();f.upload(t,change);await denied(f.ops.finalizeAttachment(f.req({ticketId:t.ticketId})),'invalid-argument');assert.equal(f.data.get('incidents/i').attachments.length,0);});
test('ticket ausente, expirado, alheio e adulterado',async()=>{const f=fixture();await denied(f.ops.finalizeAttachment(f.req({ticketId:'missing'})),'not-found');const t=await f.ticket();f.upload(t);await denied(f.ops.finalizeAttachment(f.req({ticketId:t.ticketId},'other')),'permission-denied');f.expire();await denied(f.ops.finalizeAttachment(f.req({ticketId:t.ticketId})),'failed-precondition');const g=fixture(),u=await g.ticket();g.data.get('attachmentTickets/'+u.ticketId).teamId='b';await denied(g.ops.finalizeAttachment(g.req({ticketId:u.ticketId})),'permission-denied');});
test('metadata falsa não é aceita ou usada',async()=>{const f=fixture(),t=await f.ticket();f.upload(t);for(const key of ['size','contentType','storagePath','authorUid','teamId'])await denied(f.ops.finalizeAttachment(f.req({ticketId:t.ticketId,[key]:'forged'})),'invalid-argument');const {attachment}=await f.ops.finalizeAttachment(f.req({ticketId:t.ticketId}));assert.equal(attachment.size,100);assert.equal(attachment.uploadedByUid,'u');assert.equal(attachment.storagePath,t.storagePath);});
test('fluxo válido e finalize idempotente nos dois módulos',async()=>{for(const [recordType,recordId] of [['incident','i'],['round','r']]){const f=fixture(),t=await f.ticket({recordType,recordId});f.upload(t);const a=await f.ops.finalizeAttachment(f.req({ticketId:t.ticketId}));assert.deepEqual(await f.ops.finalizeAttachment(f.req({ticketId:t.ticketId})),a);assert.equal(f.data.get((recordType==='incident'?'incidents/':'roundReports/')+recordId).attachments.length,1);const download=await f.ops.requestAttachmentDownload(f.req({teamId:'a',recordType,recordId,attachmentId:t.ticketId}));assert.equal(download.expiresAt,220000);await denied(f.ops.requestAttachmentDownload(f.req({teamId:'b',recordType,recordId,attachmentId:t.ticketId})),'permission-denied');}});
test('formato inicial, extensão, UID forjado e tipo de registro inválidos',async()=>{const f=fixture();for(const change of [{contentType:'text/html'},{fileName:'../test.pdf'},{fileName:'test.png'},{recordType:'__proto__'},{uid:'evil'}])await denied(f.ticket(change),'invalid-argument');});
test('limite revalidado no finalize, ticket expirado libera reserva',async()=>{const f=fixture(),t=await f.ticket();f.upload(t);f.data.get('incidents/i').attachments=[{},{},{}];await denied(f.ops.finalizeAttachment(f.req({ticketId:t.ticketId})),'resource-exhausted');const g=fixture();await g.ticket();await g.ticket();await g.ticket();g.expire();assert.ok((await g.ticket()).ticketId);});
test('falha de assinatura libera reserva; rate limit limita novas tentativas',async()=>{const f=fixture();f.storage.upload=async()=>{throw Error('signing unavailable');};for(let i=0;i<20;i++)await assert.rejects(f.ticket(),/signing unavailable/);assert.equal(f.data.get('incidents/i').attachmentReservations.length,0);await denied(f.ticket(),'resource-exhausted');});
test('adapter assina PUT sem sobrescrita, tamanho limitado e GET com geração fixa',async()=>{const calls=[];const bucket=()=>({file:(path,options)=>({async getSignedUrl(config){calls.push({path,options,config});return ['https://example.invalid/signed'];}})});const adapter=privateStorage(bucket);await adapter.upload({storagePath:'safe',contentType:'image/png',expiresAt:Timestamp.fromMillis(100000)});assert.equal(calls[0].config.extensionHeaders['x-goog-if-generation-match'],'0');assert.equal(calls[0].config.extensionHeaders['x-goog-content-length-range'],`1,${MAX_ATTACHMENT_BYTES}`);await adapter.download({storagePath:'safe',generation:'12',fileName:'ficticio.png'},200000);assert.equal(calls[1].config.queryParams.generation,'12');});
