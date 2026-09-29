import {Timestamp} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {randomUUID} from "node:crypto";
import {HttpsError} from "firebase-functions/v2/https";
import {requireUid, requireEnabled, requireActiveMembership, alertTargets} from "./teams.js";
import {teamIdentifier} from "./policy.js";
import {cleanText, sectorForFunction} from "./incidents.js";
import {officialShiftWindow, pilotBoundary} from "./shift-schedule.js";

const invalid=()=>{throw new HttpsError("invalid-argument","Confira os dados do plantão.");};
const text=(v,max,optional=false)=>cleanText(v??"",max,optional);
const participantId=v=>{if(typeof v!=="string"||v.length<1||v.length>128||v.includes("/"))invalid();return v;};
const millis=v=>v?.toMillis?.()??null;
const serialize=s=>{const d=s.data();return {...d,startedAt:millis(d.startedAt),endedAt:millis(d.endedAt),createdAt:millis(d.createdAt),updatedAt:millis(d.updatedAt),
  officialStartAt:millis(d.officialStartAt),officialEndAt:millis(d.officialEndAt),reportGeneratedAt:millis(d.reportGeneratedAt),autoClosedAt:millis(d.autoClosedAt)};};
const effectiveEndMs=d=>millis(d.officialEndAt)??null;
const guestUid=uid=>String(uid||"").startsWith("guest:");
function markDuty(tx,db,teamId,uid,shiftId,now){
  if(guestUid(uid))return;
  tx.set(db.doc(`teams/${teamId}/members/${uid}`),{onDuty:true,activeShiftId:shiftId,dutyStartedAt:now,dutyEndedAt:null},{merge:true});
}
function clearDuty(tx,db,teamId,uid,now){
  if(guestUid(uid))return;
  tx.set(db.doc(`teams/${teamId}/members/${uid}`),{onDuty:false,activeShiftId:null,dutyEndedAt:now},{merge:true});
}
function clearDutyForParticipants(tx,db,teamId,before,now){
  for(const p of (before.participants||[])) if(p.endedAt==null) clearDuty(tx,db,teamId,p.uid,now);
}
function closeAutomatically(before,endMs,now){
  const participants=(before.participants||[]).map(p=>p.endedAt==null?{...p,endedAt:endMs}:p);
  const coverages=(before.coverages||[]).map(c=>c.endedAt==null?{...c,endedAt:endMs,endedByUid:"system",endedByName:"OPERIS"}:c);
  return {...before,status:"CLOSED",endedAt:Timestamp.fromMillis(endMs),updatedAt:now,participants,coverages,autoClosed:true,autoClosedAt:now,
    reportGeneratedAt:now,closingNotes:before.closingNotes||"Plantão encerrado automaticamente às 07:00 pelo OPERIS."};
}


async function notifyShiftBoundary(db,kind,nowMs=Date.now()){
  const boundary=pilotBoundary(kind,nowMs);
  if(!boundary.active)return {teams:0,tokens:0};
  const teams=await db.collection("teams").where("enabled","==",true).limit(200).get();
  let sentTeams=0,sentTokens=0;
  for(const team of teams.docs){
    if(team.data().active===false)continue;
    const targets=await alertTargets(db,team.id,"__operis_system__");
    if(!targets.length)continue;
    const teamName=String(team.data().name||team.id).slice(0,80);
    const collapse="shift_"+kind.toLowerCase()+"_"+team.id+"_"+boundary.key;
    const response=await getMessaging().sendEachForMulticast({
      tokens:targets.map(t=>t.fcmToken),
      android:{priority:"normal",collapseKey:collapse,ttl:3600000},
      data:{type:"shift_schedule_boundary",boundary:kind,teamId:team.id,teamName,date:boundary.dateLabel,boundaryKey:collapse}
    });
    sentTeams++;sentTokens+=response.successCount;
  }
  return {teams:sentTeams,tokens:sentTokens};
}

async function notifyShiftOpenedCreated(db,snap){
  const d=snap?.data?.();if(!d||d.status!=="ACTIVE"||!d.teamId)return {tokens:0};
  const opener=(d.participants||[]).find(p=>p.endedAt==null);
  const team=await db.doc("teams/"+d.teamId).get();
  if(!team.exists||team.data().enabled!==true||team.data().active===false)return {tokens:0};
  const targets=await alertTargets(db,d.teamId,opener?.uid||"");
  if(!targets.length)return {tokens:0};
  const teamName=String(team.data().name||d.teamId).slice(0,80);
  const key="shift_opened_"+snap.id;
  const response=await getMessaging().sendEachForMulticast({
    tokens:targets.map(t=>t.fcmToken),
    android:{priority:"high",collapseKey:key,ttl:3600000},
    data:{type:"shift_opened",teamId:d.teamId,teamName,shiftId:snap.id,openedBy:String(opener?.name||"OPERIS").slice(0,40),startedAt:String(millis(d.startedAt)||Date.now()),boundaryKey:key}
  });
  return {tokens:response.successCount,failed:response.failureCount};
}

export function shiftOperations(db){
  async function member(tx,uid,teamId){
    const [team,membership,user]=await tx.getAll(db.doc(`teams/${teamId}`),db.doc(`teams/${teamId}/members/${uid}`),db.doc(`users/${uid}`));
    requireEnabled(team);requireActiveMembership(membership);
    if(!user.exists)throw new HttpsError("failed-precondition","Informe seu nome primeiro.");
    const operationalFunction=String(membership.data().operationalFunction||user.data().operationalFunction||"").toUpperCase();
    if(!["BRIGADISTA","VIGILANTE","AGP"].includes(operationalFunction))
      throw new HttpsError("failed-precondition","Defina sua função operacional antes de assumir o plantão.");
    return {name:text(user.data().name,40),role:membership.data().role,operationalFunction};
  }
  async function teamActive(tx,teamId){
    const q=db.collection("shifts").where("teamId","==",teamId).where("status","==","ACTIVE").limit(1);
    const snap=await tx.get(q);return snap.docs[0]??null;
  }
  async function start(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),nowMs=Date.now(),window=officialShiftWindow(nowMs);
    const now=Timestamp.fromMillis(nowMs);
    const requestedPost=text(req.data?.post,80,true).toUpperCase();
    const portariaName=text(req.data?.portariaName,40,true);
    const batalhaoName=text(req.data?.batalhaoName,40,true);
    if((portariaName&&!batalhaoName)||(!portariaName&&batalhaoName))
      throw new HttpsError("invalid-argument","Informe os nomes dos dois postos da Segurança.");
    const result=await db.runTransaction(async tx=>{
      const who=await member(tx,uid,teamId);
      let existing=await teamActive(tx,teamId);
      if(existing&&effectiveEndMs(existing.data())!=null&&effectiveEndMs(existing.data())<=nowMs){
        const closed=closeAutomatically(existing.data(),effectiveEndMs(existing.data()),now);
        clearDutyForParticipants(tx,db,teamId,existing.data(),now);
        tx.set(existing.ref,closed);
        existing=null;
      }
      const post=who.operationalFunction==="AGP"?requestedPost:"";
      if(who.operationalFunction==="AGP" && !["PORTARIA_VEICULOS","BATALHAO"].includes(post))
        throw new HttpsError("invalid-argument","Selecione Portaria de Veículos ou Batalhão.");
      const isAdministrative=["owner","admin"].includes(who.role);
      const canConfirmSecurity=who.operationalFunction==="VIGILANTE"||isAdministrative;
      const mustConfirmSecurity=who.operationalFunction==="VIGILANTE"&&!isAdministrative;
      if((portariaName||batalhaoName)&&!canConfirmSecurity)
        throw new HttpsError("permission-denied","Somente Vigilantes, administradores ou o criador podem confirmar os postos.");
      const existingSecurityPosts=existing?.data()?.securityPosts||[];
      if(mustConfirmSecurity&&existingSecurityPosts.length<2&&(!portariaName||!batalhaoName))
        throw new HttpsError("invalid-argument","Confirme Portaria de Veículos e Batalhão antes de assumir o plantão.");
      const securityPosts=(portariaName&&batalhaoName)?[
        {post:"PORTARIA_VEICULOS",name:portariaName,confirmedByUid:uid,confirmedByName:who.name,confirmedAt:nowMs},
        {post:"BATALHAO",name:batalhaoName,confirmedByUid:uid,confirmedByName:who.name,confirmedAt:nowMs}
      ]:null;
      const participant={uid,name:who.name,role:who.role,operationalFunction:who.operationalFunction,post,
        startedAt:nowMs,endedAt:null,intermediate:false};
      if(existing){
        const before=existing.data();
        if((before.participants||[]).some(p=>p.uid===uid&&p.endedAt==null))
          throw new HttpsError("already-exists","Você já está neste plantão.");
        if(post&&(before.participants||[]).some(p=>p.endedAt==null&&p.operationalFunction==="AGP"&&p.post===post))
          throw new HttpsError("already-exists","Este posto já possui um AGP ativo.");
        const participants=[...(before.participants||[]).filter(p=>p.uid!==uid||p.endedAt!=null),participant];
        const participantUids=[...new Set([...(before.participantUids||[]),uid])];
        const merged={...before,participants,participantUids,
          securityPosts:securityPosts??(before.securityPosts||[]),updatedAt:now};
        markDuty(tx,db,teamId,uid,existing.id,now);
        tx.set(existing.ref,merged,{merge:true});
        return {shift:serialize({data:()=>merged}),starter:participant,opened:false};
      }
      const ref=db.collection("shifts").doc();
      const record={shiftId:ref.id,teamId,status:"ACTIVE",startedAt:now,endedAt:null,createdAt:now,updatedAt:now,
        officialStartAt:window.open?Timestamp.fromMillis(window.startMs):null,officialEndAt:window.open?Timestamp.fromMillis(window.endMs):null,
        scheduleMode:window.open?"OFFICIAL":"MANUAL_TEST",autoClosed:false,autoClosedAt:null,reportGeneratedAt:null,
        shiftLabel:text(req.data?.shiftLabel,60,true)||"19 às 07 - Noturno",
        company:text(req.data?.company,100,true),location:text(req.data?.location,100,true),
        previousTeam:text(req.data?.previousTeam,60,true),nextTeam:"",
        equipmentNotes:text(req.data?.equipmentNotes||"Sem alterações",1000,true),
        openingNotes:text(req.data?.openingNotes||"Plantão recebido com todas as alterações e ordens em vigor.",1000,true),
        closingNotes:"",securityPosts:securityPosts||[],participantUids:[uid],participants:[participant]};
      tx.update(db.doc(`teams/${teamId}`),{updatedAt:now});
      markDuty(tx,db,teamId,uid,ref.id,now);
      tx.create(ref,record);return {shift:serialize({data:()=>record}),starter:participant,opened:true};
    });
    return {shift:result.shift,opened:result.opened};
  }
  async function current(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),nowMs=Date.now(),now=Timestamp.fromMillis(nowMs);
    return db.runTransaction(async tx=>{
      await member(tx,uid,teamId);const snap=await teamActive(tx,teamId);
      if(!snap){clearDuty(tx,db,teamId,uid,now);return {shift:null};}
      if(effectiveEndMs(snap.data())!=null&&effectiveEndMs(snap.data())<=nowMs){
        clearDutyForParticipants(tx,db,teamId,snap.data(),now);
        tx.set(snap.ref,closeAutomatically(snap.data(),effectiveEndMs(snap.data()),now));
        return {shift:null};
      }
      const active=(snap.data().participants||[]).some(p=>p.uid===uid&&p.endedAt==null);
      if(active)markDuty(tx,db,teamId,uid,snap.id,now); else clearDuty(tx,db,teamId,uid,now);
      return {shift:serialize(snap)};
    });
  }
  async function list(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),nowMs=Date.now(),now=Timestamp.fromMillis(nowMs);
    return db.runTransaction(async tx=>{
      await member(tx,uid,teamId);
      const snap=await tx.get(db.collection("shifts").where("teamId","==",teamId));
      const shifts=snap.docs.map(doc=>{
        if(doc.data().status==="ACTIVE"&&effectiveEndMs(doc.data())!=null&&effectiveEndMs(doc.data())<=nowMs){const closed=closeAutomatically(doc.data(),effectiveEndMs(doc.data()),now);clearDutyForParticipants(tx,db,teamId,doc.data(),now);tx.set(doc.ref,closed);return serialize({data:()=>closed});}
        return serialize(doc);
      }).sort((a,b)=>(b.startedAt||0)-(a.startedAt||0)).slice(0,100);
      return {shifts};
    });
  }
  async function report(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),shiftId=teamIdentifier(req.data?.shiftId);
    return db.runTransaction(async tx=>{
      const viewer=await member(tx,uid,teamId);
      const sector=req.data?.sector??sectorForFunction(viewer.operationalFunction);
      if(!["BRIGADA","SEGURANCA"].includes(sector))invalid();
      if(sector!==sectorForFunction(viewer.operationalFunction)&&!["owner","admin"].includes(viewer.role))
        throw new HttpsError("permission-denied","Relatório restrito ao seu setor.");
      const shiftSnap=await tx.get(db.doc(`shifts/${shiftId}`));
      if(!shiftSnap.exists||shiftSnap.data().teamId!==teamId)throw new HttpsError("not-found","Plantão não encontrado.");
      const nowMs=Date.now(),now=Timestamp.fromMillis(nowMs);
      let shiftData=shiftSnap.data();
      if(shiftData.status==="ACTIVE"&&effectiveEndMs(shiftData)!=null&&effectiveEndMs(shiftData)<=nowMs){shiftData=closeAutomatically(shiftData,effectiveEndMs(shiftData),now);tx.set(shiftSnap.ref,shiftData);}
      const [incidentSnap,roundSnap]=await Promise.all([
        tx.get(db.collection("incidents").where("shiftId","==",shiftId).limit(500)),
        tx.get(db.collection("roundReports").where("shiftId","==",shiftId).limit(500))
      ]);
      const functionByUid=new Map((shiftData.participants||[]).map(p=>[p.uid,p.operationalFunction||""]));
      const toEvent=(snap,type)=>{const d=snap.data();return {
        eventType:type,recordId:type==="INCIDENT"?(d.incidentId||snap.id):(d.reportId||snap.id),
        title:d.title||"",category:type==="INCIDENT"?(d.type||""):(d.findingType||""),location:d.location||"",
        description:d.description||"",actionText:type==="INCIDENT"?(d.actionsTaken||""):(d.immediateAction||""),
        authorName:d.authorName||"",authorOperationalFunction:d.authorOperationalFunction||functionByUid.get(d.authorUid)||"",
        status:d.status||"",createdAt:millis(d.createdAt)??0,attachmentCount:(d.attachments||[]).length,reference:type==="INCIDENT"?(d.reference||""):"",
        deleted:d.deleted===true
      };};
      const belongs=s=>s.data().teamId===teamId&&(s.data().ownerSector||sectorForFunction(s.data().authorOperationalFunction||functionByUid.get(s.data().authorUid)))===sector;
      const events=[...incidentSnap.docs.filter(belongs).map(s=>toEvent(s,"INCIDENT")),...roundSnap.docs.filter(belongs).map(s=>toEvent(s,"ROUND"))]
        .filter(e=>!e.deleted).sort((a,b)=>a.createdAt-b.createdAt);
      const participants=(shiftData.participants||[]).filter(p=>sectorForFunction(p.operationalFunction)===sector);
      return {shift:{...serialize({data:()=>shiftData}),participants,participantUids:[...new Set(participants.map(p=>p.uid))],
        postHistory:sector==="SEGURANCA"?(shiftData.postHistory||[]):[],coverages:sector==="SEGURANCA"?(shiftData.coverages||[]):[]},
        events,incidentCount:events.filter(e=>e.eventType==="INCIDENT").length,roundCount:events.filter(e=>e.eventType==="ROUND").length};
    });
  }
  async function addIntermediate(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),shiftId=teamIdentifier(req.data?.shiftId);
    const name=text(req.data?.name,40),startedAt=Number(req.data?.startedAt),endedAt=Number(req.data?.endedAt),now=Timestamp.now();
    if(!Number.isSafeInteger(startedAt)||!Number.isSafeInteger(endedAt)||endedAt<=startedAt)invalid();
    return db.runTransaction(async tx=>{
      const actor=await member(tx,uid,teamId),ref=db.doc(`shifts/${shiftId}`),snap=await tx.get(ref);
      if(!snap.exists||snap.data().teamId!==teamId)throw new HttpsError('not-found','Plantão não encontrado.');
      const before=snap.data();if(before.status!=='ACTIVE')throw new HttpsError('failed-precondition','O plantão já foi encerrado.');
      const actorActive=(before.participants||[]).some(p=>p.uid===uid&&p.endedAt==null);
      if(!['owner','admin'].includes(actor.role)&&!(actor.operationalFunction==='BRIGADISTA'&&actorActive))
        throw new HttpsError('permission-denied','Somente um brigadista ativo, administrador ou criador pode registrar participação intermediária.');
      const minMs=millis(before.officialStartAt)??millis(before.startedAt),maxMs=millis(before.officialEndAt)??((millis(before.startedAt)??Date.now())+86400000);
      if(startedAt<minMs||endedAt>maxMs)throw new HttpsError('invalid-argument','O horário intermediário deve estar dentro deste plantão.');
      if((before.participants||[]).filter(p=>p.intermediate===true).length>=30)throw new HttpsError('resource-exhausted','Limite de participações intermediárias atingido.');
      const guest={uid:'guest:'+randomUUID(),name,role:'guest',operationalFunction:'BRIGADISTA',post:'',startedAt,endedAt,intermediate:true,recordedByUid:uid,recordedByName:actor.name};
      const merged={...before,participants:[...(before.participants||[]),guest],updatedAt:now};tx.set(ref,merged);return {shift:serialize({data:()=>merged}),participant:guest};
    });
  }
  async function confirmSecurityPosts(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),shiftId=teamIdentifier(req.data?.shiftId),now=Timestamp.now();
    const portariaName=text(req.data?.portariaName,40),batalhaoName=text(req.data?.batalhaoName,40);
    return db.runTransaction(async tx=>{
      const actor=await member(tx,uid,teamId);
      const canConfirm=actor.operationalFunction==="VIGILANTE"||["owner","admin"].includes(actor.role);
      if(!canConfirm)throw new HttpsError("permission-denied","Somente Vigilantes, administradores ou o criador podem confirmar os postos.");
      const ref=db.doc(`shifts/${shiftId}`),snap=await tx.get(ref);
      if(!snap.exists||snap.data().teamId!==teamId)throw new HttpsError("not-found","Plantão não encontrado.");
      const before=snap.data();
      if(before.status!=="ACTIVE")throw new HttpsError("failed-precondition","O plantão já foi encerrado.");
      const confirmedAt=now.toMillis();
      const securityPosts=[
        {post:"PORTARIA_VEICULOS",name:portariaName,confirmedByUid:uid,confirmedByName:actor.name,confirmedAt},
        {post:"BATALHAO",name:batalhaoName,confirmedByUid:uid,confirmedByName:actor.name,confirmedAt}
      ];
      const merged={...before,securityPosts,updatedAt:now};
      tx.set(ref,merged);return {shift:serialize({data:()=>merged}),securityPosts};
    });
  }
  async function updatePost(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),shiftId=teamIdentifier(req.data?.shiftId),targetUid=participantId(req.data?.targetUid),now=Timestamp.now();
    const newPost=text(req.data?.post,80).toUpperCase();
    if(!["PORTARIA_VEICULOS","BATALHAO"].includes(newPost))
      throw new HttpsError("invalid-argument","Selecione Portaria de Veículos ou Batalhão.");
    return db.runTransaction(async tx=>{
      const editor=await member(tx,uid,teamId);
      const canManagePosts=editor.operationalFunction==="VIGILANTE"||editor.role==="owner"||editor.role==="admin";
      if(!canManagePosts)throw new HttpsError("permission-denied","Somente Vigilantes, administradores ou o criador da equipe podem redistribuir postos.");
      const ref=db.doc(`shifts/${shiftId}`),snap=await tx.get(ref);
      if(!snap.exists||snap.data().teamId!==teamId)throw new HttpsError("not-found","Plantão não encontrado.");
      const before=snap.data();
      if(before.status!=="ACTIVE")throw new HttpsError("failed-precondition","O plantão já foi encerrado.");
      const target=(before.participants||[]).find(p=>p.uid===targetUid&&p.endedAt==null);
      if(!target)throw new HttpsError("not-found","AGP ativo não encontrado neste plantão.");
      if(target.operationalFunction!=="AGP")throw new HttpsError("failed-precondition","Somente o posto de AGPs pode ser alterado por esta função.");
      if(target.post===newPost)throw new HttpsError("already-exists","O AGP já está neste posto.");
      const oldPost=target.post||"",other=(before.participants||[]).find(p=>p.uid!==targetUid&&p.endedAt==null&&p.operationalFunction==="AGP"&&p.post===newPost);
      if(!["PORTARIA_VEICULOS","BATALHAO"].includes(oldPost))throw new HttpsError("failed-precondition","Posto atual inválido.");
      if((before.coverages||[]).some(c=>c.endedAt==null&&(c.targetUid===targetUid||c.targetUid===other?.uid)))
        throw new HttpsError("failed-precondition","Encerre a cobertura antes de trocar o posto.");
      const baseAudit={editedByUid:uid,editedByName:editor.name,editedByFunction:editor.operationalFunction,editedByRole:editor.role,editedAt:now.toMillis()};
      const changes=[{targetUid,targetName:target.name,fromPost:oldPost,toPost:newPost,...baseAudit}];
      if(other&&oldPost){changes.push({targetUid:other.uid,targetName:other.name,fromPost:newPost,toPost:oldPost,...baseAudit});}
      const participants=(before.participants||[]).map(p=>{
        if(p.uid===targetUid&&p.endedAt==null)return {...p,post:newPost};
        if(other&&p.uid===other.uid&&p.endedAt==null)return {...p,post:oldPost};
        return p;
      });
      const postHistory=[...(before.postHistory||[]),...changes];
      const merged={...before,participants,postHistory,updatedAt:now};
      tx.set(ref,merged);return {shift:serialize({data:()=>merged}),changes};
    });
  }
  async function startCoverage(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),shiftId=teamIdentifier(req.data?.shiftId),targetUid=participantId(req.data?.targetUid),now=Timestamp.now();
    const coveringUid=participantId(req.data?.coveringUid||uid),reason=String(req.data?.reason||"").toUpperCase();
    if(!["JANTAR","NECESSIDADE_OPERACIONAL"].includes(reason))invalid();
    return db.runTransaction(async tx=>{
      const editor=await member(tx,uid,teamId);
      const canRecord=editor.operationalFunction==="VIGILANTE"||editor.role==="owner"||editor.role==="admin";
      if(!canRecord)throw new HttpsError("permission-denied","Somente Vigilantes, administradores ou o criador da equipe podem registrar rendição de AGP.");
      if(coveringUid!==uid&&editor.role!=="owner"&&editor.role!=="admin")throw new HttpsError("permission-denied","Você só pode registrar sua própria cobertura.");
      const ref=db.doc(`shifts/${shiftId}`),snap=await tx.get(ref);
      if(!snap.exists||snap.data().teamId!==teamId)throw new HttpsError("not-found","Plantão não encontrado.");
      const before=snap.data();if(before.status!=="ACTIVE")throw new HttpsError("failed-precondition","O plantão já foi encerrado.");
      const participants=before.participants||[];
      const target=participants.find(p=>p.uid===targetUid&&p.endedAt==null&&p.operationalFunction==="AGP");
      if(!target)throw new HttpsError("not-found","AGP ativo não encontrado neste plantão.");
      const covering=participants.find(p=>p.uid===coveringUid&&p.endedAt==null&&p.operationalFunction==="VIGILANTE");
      if(!covering)throw new HttpsError("failed-precondition","Selecione um Vigilante ativo para realizar a rendição.");
      if((before.coverages||[]).some(c=>c.targetUid===targetUid&&c.endedAt==null))throw new HttpsError("already-exists","Este AGP já está sendo rendido.");
      if((before.coverages||[]).some(c=>c.coveredByUid===coveringUid&&c.endedAt==null))throw new HttpsError("already-exists","Este Vigilante já está cobrindo outro posto.");
      const coverage={coverageId:randomUUID(),targetUid,targetName:target.name,post:target.post||"",coveredByUid:covering.uid,coveredByName:covering.name,
        reason,recordedByUid:uid,recordedByName:editor.name,recordedByRole:editor.role,startedAt:now.toMillis(),endedAt:null,endedByUid:null,endedByName:null};
      const coverages=[...(before.coverages||[]),coverage],merged={...before,coverages,updatedAt:now};
      tx.set(ref,merged);return {shift:serialize({data:()=>merged}),coverage};
    });
  }
  async function finishCoverage(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),shiftId=teamIdentifier(req.data?.shiftId),coverageId=participantId(req.data?.coverageId),now=Timestamp.now();
    return db.runTransaction(async tx=>{
      const editor=await member(tx,uid,teamId),ref=db.doc(`shifts/${shiftId}`),snap=await tx.get(ref);
      if(!snap.exists||snap.data().teamId!==teamId)throw new HttpsError("not-found","Plantão não encontrado.");
      const before=snap.data();if(before.status!=="ACTIVE")throw new HttpsError("failed-precondition","O plantão já foi encerrado.");
      const current=(before.coverages||[]).find(c=>c.coverageId===coverageId&&c.endedAt==null);
      if(!current)throw new HttpsError("not-found","Cobertura ativa não encontrada.");
      const canFinish=current.coveredByUid===uid||editor.role==="owner"||editor.role==="admin";
      if(!canFinish)throw new HttpsError("permission-denied","Somente o Vigilante que está cobrindo, um administrador ou o criador da equipe pode encerrar a cobertura.");
      const coverages=(before.coverages||[]).map(c=>c.coverageId===coverageId&&c.endedAt==null?{...c,endedAt:now.toMillis(),endedByUid:uid,endedByName:editor.name}:c);
      const merged={...before,coverages,updatedAt:now};tx.set(ref,merged);return {shift:serialize({data:()=>merged})};
    });
  }
  async function finish(req){
    const uid=requireUid(req),teamId=teamIdentifier(req.data?.teamId),shiftId=teamIdentifier(req.data?.shiftId),now=Timestamp.now();
    return db.runTransaction(async tx=>{
      const who=await member(tx,uid,teamId),ref=db.doc(`shifts/${shiftId}`),snap=await tx.get(ref);
      if(!snap.exists||snap.data().teamId!==teamId)throw new HttpsError("not-found","Plantão não encontrado.");
      const before=snap.data();if(before.status!=="ACTIVE")throw new HttpsError("failed-precondition","Este plantão já foi encerrado.");
      if(!(before.participants||[]).some(p=>p.uid===uid&&p.endedAt==null))
        throw new HttpsError("failed-precondition","Você não possui participação ativa neste plantão.");
      if(!before.participantUids?.includes(uid)&&who.role!=="owner"&&who.role!=="admin")throw new HttpsError("permission-denied","Sem permissão para encerrar este plantão.");
      if((before.coverages||[]).some(c=>c.endedAt==null&&(c.targetUid===uid||c.coveredByUid===uid)))
        throw new HttpsError("failed-precondition","Encerre a rendição temporária antes de sair do plantão.");
      const participants=(before.participants||[]).map(p=>p.uid===uid&&p.endedAt==null?{...p,endedAt:now.toMillis()}:p);
      if(!participants.some(p=>p.uid===uid&&p.endedAt!=null))
        throw new HttpsError("failed-precondition","Você não possui participação ativa neste plantão.");
      const stillActive=participants.some(p=>p.endedAt==null);
      const merged={...before,status:stillActive?"ACTIVE":"CLOSED",endedAt:stillActive?null:now,updatedAt:now,
        nextTeam:stillActive?before.nextTeam:text(req.data?.nextTeam,60,true),
        closingNotes:stillActive?before.closingNotes:text(req.data?.closingNotes||"Plantão entregue com todas as orientações e assinaturas de acordo.",1000,true),
        reportGeneratedAt:stillActive?(before.reportGeneratedAt||null):now,autoClosed:stillActive?(before.autoClosed===true):false,participants};
      clearDuty(tx,db,teamId,uid,now);
      if(!stillActive)clearDutyForParticipants(tx,db,teamId,before,now);
      tx.set(ref,merged);return {shift:serialize({data:()=>merged})};
    });
  }
  async function autoCloseExpired(){
    const nowMs=Date.now(),now=Timestamp.fromMillis(nowMs);
    const active=await db.collection("shifts").where("status","==","ACTIVE").limit(200).get();
    let closed=0;
    for(const doc of active.docs){
      if(effectiveEndMs(doc.data())==null||effectiveEndMs(doc.data())>nowMs)continue;
      const changed=await db.runTransaction(async tx=>{
        const snap=await tx.get(doc.ref);if(!snap.exists||snap.data().status!=="ACTIVE")return false;
        const endMs=effectiveEndMs(snap.data());if(endMs==null||endMs>nowMs)return false;
        clearDutyForParticipants(tx,db,snap.data().teamId,snap.data(),now);
        tx.set(doc.ref,closeAutomatically(snap.data(),endMs,now));return true;
      });
      if(changed)closed++;
    }
    return {closed};
  }
  return {start,current,list,report,addIntermediate,confirmSecurityPosts,updatePost,startCoverage,finishCoverage,finish,autoCloseExpired,notifyOpened:snap=>notifyShiftOpenedCreated(db,snap),notifyShiftBoundary:(kind,nowMs)=>notifyShiftBoundary(db,kind,nowMs)};
}
