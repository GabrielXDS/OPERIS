import {Timestamp} from 'firebase-admin/firestore';
import {HttpsError} from 'firebase-functions/v2/https';
import {createHash, randomUUID} from 'node:crypto';
import {requireUid, requireEnabled, requireActiveMembership, validate} from './teams.js';
import {teamIdentifier} from './policy.js';
import {requireActiveDevice} from './devices.js';
import {cleanText, canView} from './incidents.js';

export const MAX_ATTACHMENT_BYTES=10*1024*1024;
const MIME=new Set(['image/jpeg','image/png','application/pdf']);
const collections={incident:'incidents',round:'roundReports'};
const error=(code,message)=>{throw new HttpsError(code,message);};
const id=value=>validate(()=>teamIdentifier(value));
const pathFor=t=>`teams/${t.teamId}/${t.recordType}/${t.recordId}/${t.ticketId}`;
const millis=value=>value?.toMillis?.()??0;
function params(req,allowed) {
 const d=req.data;
 if(!d||typeof d!=='object'||Array.isArray(d)||Object.keys(d).some(k=>k!=='deviceId'&&!allowed.includes(k)))error('invalid-argument','Dados de anexo inválidos.');
 return d;
}
export function attachmentOperations(db,storage,clock=()=>Date.now()) {
 async function authorize(tx,req,uid,teamId,recordType,recordId) {
  if(!Object.hasOwn(collections,recordType))error('invalid-argument','Tipo de registro inválido.');
  const ref=db.doc(`${collections[recordType]}/${recordId}`);
  const [team,member,record,user]=await tx.getAll(db.doc(`teams/${teamId}`),db.doc(`teams/${teamId}/members/${uid}`),ref,db.doc(`users/${uid}`));
  requireEnabled(team);requireActiveMembership(member);
  await requireActiveDevice(db,tx,req,uid);
  if(!record.exists||record.data().teamId!==teamId)error('not-found','Registro não encontrado.');
  if(!canView(record.data(),uid,String(user.data()?.operationalFunction||'').toUpperCase(),member.data().role,recordType==='round'))
    error('permission-denied','Esta ficha não foi compartilhada com o seu setor.');
  return {ref,record:record.data()};
 }
 async function rate(tx,uid,action) {
  const ref=db.doc('operationLimits/attachment_'+action+'_'+createHash('sha256').update(uid).digest('hex'));
  const s=await tx.get(ref),now=clock(),fresh=s.exists&&now-millis(s.data().startedAt)<60_000,count=fresh?s.data().count:0;
  if(count>=(action==='download'?60:20))error('resource-exhausted','Muitas tentativas de anexos. Aguarde.');
  return ()=>tx.set(ref,{count:count+1,startedAt:fresh?s.data().startedAt:Timestamp.fromMillis(now),expiresAt:Timestamp.fromMillis(now+120000)});
 }
 function owned(t,uid) {
  if(!t||t.uid!==uid)error('permission-denied','Ticket não autorizado.');
  id(t.teamId);id(t.recordId);id(t.ticketId);
  if(t.storagePath!==pathFor(t))error('permission-denied','Caminho de upload inválido.');
 }
 return {
  async requestAttachmentUpload(req) {
   const uid=requireUid(req),d=params(req,['teamId','recordType','recordId','fileName','contentType']);
   const teamId=id(d.teamId),recordId=id(d.recordId),recordType=d.recordType;
   if(!MIME.has(d.contentType))error('invalid-argument','Use JPG, PNG ou PDF.');
   const fileName=cleanText(d.fileName,120);
   const ext=fileName.toLowerCase().split('.').at(-1);
   if(!({ 'image/jpeg':['jpg','jpeg'],'image/png':['png'],'application/pdf':['pdf']}[d.contentType].includes(ext))||/[\\/]/.test(fileName))error('invalid-argument','Nome ou formato inválido.');
   const ticketId=randomUUID(),ticket={ticketId,uid,teamId,recordType,recordId,fileName,contentType:d.contentType,expiresAt:Timestamp.fromMillis(clock()+10*60_000),status:'PENDING'};
   ticket.storagePath=pathFor(ticket);
   await db.runTransaction(async tx=>{
    const {ref,record}=await authorize(tx,req,uid,teamId,recordType,recordId),commit=await rate(tx,uid,'upload');
    const reservations=(record.attachmentReservations??[]).filter(t=>millis(t.expiresAt)>clock());
    if((record.attachments??[]).length+reservations.length>=3)error('resource-exhausted','Limite de 3 anexos (incluindo uploads pendentes).');
    commit();tx.create(db.doc('attachmentTickets/'+ticketId),ticket);
    tx.update(ref,{attachmentReservations:[...reservations,{ticketId,expiresAt:ticket.expiresAt}]});
   });
   try {
    const access=await storage.upload(ticket);
    return {ticketId,storagePath:ticket.storagePath,expiresAt:millis(ticket.expiresAt),...access};
   } catch(e) {
    // A failed signing attempt must not consume a slot for ten minutes.
    await db.runTransaction(async tx=>{
     const ref=db.doc(`${collections[recordType]}/${recordId}`),s=await tx.get(ref);
     if(s.exists)tx.update(ref,{attachmentReservations:(s.data().attachmentReservations??[]).filter(t=>t.ticketId!==ticketId)});
     tx.update(db.doc('attachmentTickets/'+ticketId),{status:'FAILED'});
    });
    throw e;
   }
  },
  async finalizeAttachment(req) {
   const uid=requireUid(req),d=params(req,['ticketId']),ticketId=id(d.ticketId),ticketRef=db.doc('attachmentTickets/'+ticketId);
   const pre=await db.runTransaction(async tx=>{
    const snap=await tx.get(ticketRef);if(!snap.exists)error('not-found','Ticket não encontrado.');
    const t=snap.data();owned(t,uid);const {record}=await authorize(tx,req,uid,t.teamId,t.recordType,t.recordId),commit=await rate(tx,uid,'finalize');
    if(t.status!=='FINALIZED'&&(t.status!=='PENDING'||millis(t.expiresAt)<=clock()))error('failed-precondition','Upload expirado. Selecione o arquivo novamente.');
    commit();return {ticket:t,existing:(record.attachments??[]).find(a=>a.attachmentId===ticketId)};
   });
   if(pre.existing)return {attachment:pre.existing};
   const t=pre.ticket,actual=await storage.inspect(t.storagePath);
   if(!actual)error('not-found','Arquivo ainda não enviado. Tente novamente.');
   if(actual.name!==t.storagePath||!MIME.has(actual.contentType)||actual.contentType!==t.contentType||!Number.isSafeInteger(actual.size)||actual.size<1||actual.size>MAX_ATTACHMENT_BYTES||!actual.validSignature)
    error('invalid-argument','Arquivo inválido: confira formato e limite de 10 MB.');
   return db.runTransaction(async tx=>{
    const snap=await tx.get(ticketRef);if(!snap.exists)error('not-found','Ticket não encontrado.');
    const current=snap.data();owned(current,uid);
    const {ref,record}=await authorize(tx,req,uid,current.teamId,current.recordType,current.recordId);
    const attachments=record.attachments??[],existing=attachments.find(a=>a.attachmentId===ticketId);
    if(existing)return {attachment:existing};
    if(current.status!=='PENDING'||millis(current.expiresAt)<=clock())error('failed-precondition','Upload expirado.');
    if(attachments.length>=3)error('resource-exhausted','Limite de 3 anexos.');
    const attachment={attachmentId:ticketId,storagePath:t.storagePath,fileName:t.fileName,contentType:actual.contentType,size:actual.size,uploadedByUid:uid,createdAt:clock(),generation:actual.generation};
    tx.update(ref,{attachments:[...attachments,attachment],attachmentReservations:(record.attachmentReservations??[]).filter(r=>r.ticketId!==ticketId),updatedAt:Timestamp.fromMillis(clock())});
    tx.update(ticketRef,{status:'FINALIZED',finalizedAt:Timestamp.fromMillis(clock())});
    return {attachment};
   });
  },
  async requestAttachmentDownload(req) {
   const uid=requireUid(req),d=params(req,['teamId','recordType','recordId','attachmentId']);
   const teamId=id(d.teamId),recordId=id(d.recordId),attachmentId=id(d.attachmentId);
   const a=await db.runTransaction(async tx=>{
    const {record}=await authorize(tx,req,uid,teamId,d.recordType,recordId),commit=await rate(tx,uid,'download');
    const attachment=(record.attachments??[]).find(a=>a.attachmentId===attachmentId);
    if(!attachment||attachment.storagePath!==pathFor({teamId,recordType:d.recordType,recordId,ticketId:attachmentId}))error('not-found','Anexo não encontrado.');
    commit();return attachment;
   });
   return storage.download(a,clock()+2*60_000);
  }
 };
}

// URLs are short-lived bearer credentials, never persisted in Firestore.
export function privateStorage(bucket) {
 return {
  async upload(t) {
   const headers={'Content-Type':t.contentType,'x-goog-if-generation-match':'0','x-goog-content-length-range':`1,${MAX_ATTACHMENT_BYTES}`};
   const [uploadUrl]=await bucket().file(t.storagePath).getSignedUrl({version:'v4',action:'write',expires:millis(t.expiresAt),contentType:t.contentType,extensionHeaders:{'x-goog-if-generation-match':'0','x-goog-content-length-range':headers['x-goog-content-length-range']}});
   return {uploadUrl,headers};
  },
  async inspect(path) {
   try {
    const [m]=await bucket().file(path).getMetadata();
    const size=Number(m.size);
    if(!Number.isSafeInteger(size)||size>MAX_ATTACHMENT_BYTES||size<1)return {name:m.name,size,contentType:m.contentType,validSignature:false};
    const [bytes]=await bucket().file(path,{generation:m.generation}).download({start:0,end:7});
    const validSignature=m.contentType==='image/jpeg'?bytes[0]===255&&bytes[1]===216&&bytes[2]===255:m.contentType==='image/png'?bytes.equals(Buffer.from([137,80,78,71,13,10,26,10])):m.contentType==='application/pdf'&&bytes.subarray(0,5).toString()==='%PDF-';
    return {name:m.name,size,contentType:m.contentType,generation:m.generation,validSignature};
   } catch(e) {if(e.code===404)return null;throw e;}
  },
  async download(a,expiresAt) {
   const [downloadUrl]=await bucket().file(a.storagePath,{generation:a.generation}).getSignedUrl({version:'v4',action:'read',expires:expiresAt,queryParams:{generation:a.generation},responseDisposition:"attachment; filename*=UTF-8''"+encodeURIComponent(a.fileName)});
   return {downloadUrl,expiresAt};
  }
 };
}
