import {Timestamp} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {HttpsError} from "firebase-functions/v2/https";
import {createHash} from "node:crypto";
import {requireUid, requireEnabled, requireActiveMembership, validate} from "./teams.js";
import {requireActiveDevice} from "./devices.js";
import {teamIdentifier} from "./policy.js";

export const INCIDENT_TYPES = Object.freeze(["ALARM_TRIGGER","MEDICAL","TRANSPORT","POWER_OUTAGE","FIRE","ACCIDENT","SUDDEN_ILLNESS","LEAK","ELEVATOR_TRAP","SECURITY_BREACH","OTHER"]);
export const ROUND_TYPES = Object.freeze(["OPEN_DOOR","EXTINGUISHER_DEPRESSURIZED","EXTINGUISHER_EXPIRED","BROKEN_ITEM","BURNT_LIGHTING","OBSTRUCTED_HYDRANT","UNLOCKED_ACCESS","EQUIPMENT_MISPLACED","LEAK_FOUND","IRREGULARITY"]);
const invalid = () => {throw new HttpsError("invalid-argument","Confira os dados informados.");};
export function cleanText(value, max, optional=false) {
  if(typeof value!=="string" || value.length>max) return invalid();
  const result=value.normalize("NFC").replace(/[\u0000-\u0008\u000b-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g," ").replace(/<[^>]*>/g,"").trim();
  if((!optional && !result) || result.length>max)return invalid();
  return result;
}
function input(req, allowed) {
  const data=req.data;
  if(!data || typeof data!=="object" || Array.isArray(data) || Object.keys(data).some(k=>k!=="deviceId"&&!allowed.includes(k)))invalid();
  return {...data,teamId:validate(()=>teamIdentifier(data.teamId))};
}
const serialize = snap => { const {attachmentReservations, ...data}=snap.data(); return {...data,deleted:data.deleted===true,createdAt:data.createdAt.toMillis(),updatedAt:data.updatedAt.toMillis(),...(data.shiftStartedAt?{shiftStartedAt:data.shiftStartedAt?.toMillis?.()??data.shiftStartedAt}:{}),...(data.deletedAt?{deletedAt:data.deletedAt.toMillis()}:{})}; };
export const sectorForFunction=f=>f==="BRIGADISTA"?"BRIGADA":(["VIGILANTE","AGP"].includes(f)?"SEGURANCA":null);
export const canView=(record,uid,operationalFunction,role,integrated=false)=>{
  if(integrated)return true;
  const sector=sectorForFunction(operationalFunction);
  const ownerSector=record.ownerSector||sectorForFunction(record.authorOperationalFunction);
  return role==="owner"||role==="admin"||record.authorUid===uid||
    (!!sector&&(ownerSector===sector||(record.sharedWithSectors||[]).includes(sector)));
};

export function incidentOperations(db, options = {}) {
  const messaging = options.messaging ?? getMessaging;
  async function authorized(tx,req,uid,teamId) {
    const [team,member,user]=await tx.getAll(db.doc(`teams/${teamId}`),db.doc(`teams/${teamId}/members/${uid}`),db.doc(`users/${uid}`));
    requireEnabled(team);requireActiveMembership(member);
    await requireActiveDevice(db,tx,req,uid);
    if(!user.exists)throw new HttpsError("failed-precondition","Informe seu nome primeiro.");
    return {name:cleanText(user.data().name,40),member:member.data(),operationalFunction:String(member.data().operationalFunction||user.data().operationalFunction||"").toUpperCase()};
  }
  async function shareTargets(teamId,targetSector,senderUid){
    const members=await db.collection(`teams/${teamId}/members`).where("status","==","active").limit(100).get();
    const candidates=members.docs.filter(m=>m.id!==senderUid&&m.data().enabled===true);
    const rows=await Promise.all(candidates.map(async m=>{
      const [user,devices]=await Promise.all([
        db.doc(`users/${m.id}`).get(),
        db.collection("devices").where("uid","==",m.id).get()
      ]);
      const op=String(m.data().operationalFunction||user.data()?.operationalFunction||"").toUpperCase();
      if(sectorForFunction(op)!==targetSector)return null;
      const device=devices.docs.find(d=>d.data().enabled===true&&d.data().fcmToken) || await db.doc(`devices/${m.id}`).get();
      const token=device.data()?.enabled===true?device.data()?.fcmToken:null;
      return token?{uid:m.id,token}:null;
    }));
    const seen=new Set();return rows.filter(Boolean).filter(r=>!seen.has(r.token)&&seen.add(r.token));
  }
  async function authorizeMutation(tx,req,uid,teamId,record) {
    const {name,member}=await authorized(tx,req,uid,teamId);
    // Unauthorized mutations are rejected server-side, never just hidden in the UI.
    if(record.authorUid!==uid && member.role!=="owner" && member.role!=="admin")
      throw new HttpsError("permission-denied","Somente o autor, um administrador ou o criador da equipe pode alterar este registro.");
    return {name,member};
  }
  async function rate(tx,uid,action) {
    const key=createHash("sha256").update(uid).digest("hex");
    const ref=db.doc(`operationLimits/operations_${action}_${key}`), snap=await tx.get(ref), now=Date.now();
    const fresh=snap.exists && now-snap.data().startedAt.toMillis()<60_000;
    const count=fresh?snap.data().count:0;
    if(count>=(action==="create"?10:60))throw new HttpsError("resource-exhausted","Muitas tentativas. Aguarde.");
    return ()=>tx.set(ref,{count:count+1,startedAt:fresh?snap.data().startedAt:Timestamp.fromMillis(now),expiresAt:Timestamp.fromMillis(now+120_000)});
  }
  function handlers(collection,types,idKey,typeKey,actionKey,status) {
    const integrated=collection==="roundReports";
    return {
      async create(req) {
        const extraAllowed=collection==="incidents"?["reference"]:[];
        const uid=requireUid(req), d=input(req,["teamId",typeKey,"title","location","description",actionKey,"shiftId",...extraAllowed]);
        if(!types.includes(d[typeKey]))invalid();
        const shiftId=validate(()=>teamIdentifier(d.shiftId));
        const fields={[typeKey]:d[typeKey],title:cleanText(d.title,80),location:cleanText(d.location,100),description:cleanText(d.description,2000),[actionKey]:cleanText(d[actionKey]??"",2000,true),
          ...(collection==="incidents"?{reference:cleanText(d.reference??"",80,true)}:{})};
        if(fields.title.length<3)invalid();
        const ref=db.collection(collection).doc();
        return db.runTransaction(async tx=>{
          const {name:authorName,operationalFunction:authorOperationalFunction}=await authorized(tx,req,uid,d.teamId), commitRate=await rate(tx,uid,"create"), now=Timestamp.now();
          const shiftSnap=await tx.get(db.doc(`shifts/${shiftId}`));
          if(!shiftSnap.exists||shiftSnap.data().teamId!==d.teamId)throw new HttpsError("not-found","Plantão não encontrado.");
          const shift=shiftSnap.data();
          if(shift.status!=="ACTIVE")throw new HttpsError("failed-precondition","O plantão informado já foi encerrado.");
          if(!(shift.participants||[]).some(p=>p.uid===uid&&p.endedAt==null))
            throw new HttpsError("permission-denied","Você não possui participação ativa neste plantão.");
          const ownerSector=sectorForFunction(authorOperationalFunction);
          if(!ownerSector)throw new HttpsError("failed-precondition","Defina sua função operacional antes de registrar.");
          const record={[idKey]:ref.id,...fields,teamId:d.teamId,shiftId,shiftLabel:shift.shiftLabel||"",shiftStartedAt:shift.startedAt,
            authorUid:uid,authorName,authorOperationalFunction,ownerSector,sharedWithSectors:[],shareHistory:[],createdAt:now,updatedAt:now,
            organizationId:null,siteId:null,attachments:[],editCount:0,status,deleted:false,editHistory:[]};
          commitRate();tx.create(ref,record);
          return {record:{...record,shiftStartedAt:shift.startedAt.toMillis(),createdAt:now.toMillis(),updatedAt:now.toMillis()}};
        });
      },
      async list(req) {
        const uid=requireUid(req),d=input(req,["teamId","limit","beforeId","deletedOnly"]),limit=d.limit??30;
        if(!Number.isInteger(limit)||limit<1||limit>50)invalid();
        if(d.deletedOnly!==undefined&&typeof d.deletedOnly!=="boolean")invalid();
        if(d.beforeId!==undefined)validate(()=>teamIdentifier(d.beforeId));
        return db.runTransaction(async tx=>{
          const viewer=await authorized(tx,req,uid,d.teamId);const commitRate=await rate(tx,uid,"read");
          let scan=null;
          if(d.beforeId){
            scan=await tx.get(db.doc(`${collection}/${d.beforeId}`));
            if(!scan.exists||scan.data().teamId!==d.teamId)throw new HttpsError("not-found","Registro não encontrado.");
          }
          const wantDeleted=d.deletedOnly===true,records=[];
          let finished=false;
          // Firestore cannot negate a field here and legacy documents lack "deleted",
          // so tombstones are filtered in memory while pages are scanned.
          while(records.length<limit&&!finished){
            let q=db.collection(collection).where("teamId","==",d.teamId).orderBy("createdAt","desc").orderBy("__name__","desc").limit(limit+1);
            if(scan)q=q.startAfter(scan);
            const result=await tx.get(q);
            if(!result.docs.length){finished=true;break;}
            scan=result.docs.at(-1);
            finished=result.docs.length<limit+1;
            const visible=result.docs.filter(s=>canView(s.data(),uid,viewer.operationalFunction,viewer.member.role,integrated));
            if(wantDeleted)records.push(...visible.filter(s=>s.data().deleted===true));
            else records.push(...visible.filter(s=>!(s.data().deleted===true)));
          }
          commitRate();
          const hasMore=records.length>limit||!finished;
          records.length=Math.min(records.length,limit);
          return {records:records.map(serialize),nextCursor:hasMore?(records.at(-1)?.id||scan?.id||null):null};
        });
      },
      async details(req) {
        const uid=requireUid(req),d=input(req,["teamId",idKey]),id=validate(()=>teamIdentifier(d[idKey]));
        return db.runTransaction(async tx=>{
          const viewer=await authorized(tx,req,uid,d.teamId);const commitRate=await rate(tx,uid,"read");
          const snap=await tx.get(db.doc(`${collection}/${id}`));
          if(!snap.exists||snap.data().teamId!==d.teamId)throw new HttpsError("not-found","Registro não encontrado.");
          if(!canView(snap.data(),uid,viewer.operationalFunction,viewer.member.role,integrated))throw new HttpsError("permission-denied","Esta ficha não foi compartilhada com o seu setor.");
          commitRate();return {record:serialize(snap)};
        });
      },
      async share(req) {
        const uid=requireUid(req),d=input(req,["teamId",idKey,"targetSector"]),id=validate(()=>teamIdentifier(d[idKey]));
        if(integrated)throw new HttpsError("failed-precondition","Rondas são integradas entre Brigada e Segurança e não precisam ser compartilhadas.");
        const targetSector=String(d.targetSector||"").toUpperCase();
        if(!["BRIGADA","SEGURANCA"].includes(targetSector))invalid();
        const result=await db.runTransaction(async tx=>{
          const actor=await authorized(tx,req,uid,d.teamId),snap=await tx.get(db.doc(`${collection}/${id}`));
          if(!snap.exists||snap.data().teamId!==d.teamId)throw new HttpsError("not-found","Registro não encontrado.");
          const before=snap.data(),ownerSector=before.ownerSector||sectorForFunction(before.authorOperationalFunction);
          if(targetSector===ownerSector)throw new HttpsError("failed-precondition","A ficha já pertence a este setor.");
          const canShare=before.authorUid===uid||actor.member.role==="owner"||actor.member.role==="admin"||
            (actor.operationalFunction==="VIGILANTE"&&ownerSector==="SEGURANCA");
          if(!canShare)throw new HttpsError("permission-denied","Você não tem permissão para compartilhar esta ficha.");
          if((before.sharedWithSectors||[]).includes(targetSector))return {record:serialize(snap),notify:false,actorName:actor.name};
          const now=Timestamp.now(),shareEvent={targetSector,sharedAt:now.toMillis(),sharedByUid:uid,sharedByName:actor.name,
            sharedByFunction:actor.operationalFunction};
          const merged={...before,ownerSector,sharedWithSectors:[...(before.sharedWithSectors||[]),targetSector],
            shareHistory:[...(before.shareHistory||[]).slice(-99),shareEvent],updatedAt:now};
          tx.set(db.doc(`${collection}/${id}`),merged,{merge:true});
          return {record:serialize({data:()=>merged}),notify:true,actorName:actor.name};
        });
        if(result.notify){
          try{
            const targets=await shareTargets(d.teamId,targetSector,uid);
            if(targets.length)await messaging().sendEachForMulticast({tokens:targets.map(t=>t.token),android:{priority:"high"},data:{
              type:"record_shared",recordType:collection==="incidents"?"occurrence":"round",recordId:id,teamId:d.teamId,
              title:String(result.record.title||"Registro compartilhado").slice(0,80),sharedBy:result.actorName,targetSector,sharedAt:String(Date.now())
            }});
          }catch(e){console.error("Falha ao notificar compartilhamento de ficha",e);}
        }
        return {record:result.record};
      },
      async update(req) {
        const extraAllowed=collection==="incidents"?["reference"]:[];
        const uid=requireUid(req),d=input(req,["teamId",idKey,typeKey,"title","location","description",actionKey,...extraAllowed]);
        if(!types.includes(d[typeKey]))invalid();
        const fields={[typeKey]:d[typeKey],title:cleanText(d.title,80),location:cleanText(d.location,100),description:cleanText(d.description,2000),[actionKey]:cleanText(d[actionKey]??"",2000,true),
          ...(collection==="incidents"?{reference:cleanText(d.reference??"",80,true)}:{})};
        if(fields.title.length<3)invalid();
        const id=validate(()=>teamIdentifier(d[idKey]));
        const result=await db.runTransaction(async tx=>{
          const snap=await tx.get(db.doc(`${collection}/${id}`));
          if(!snap.exists||snap.data().teamId!==d.teamId)throw new HttpsError("not-found","Registro não encontrado.");
          if(snap.data().deleted===true)throw new HttpsError("failed-precondition","Registro excluído não pode ser alterado.");
          const {name}=await authorizeMutation(tx,req,uid,d.teamId,snap.data()),commitRate=await rate(tx,uid,"update"),now=Timestamp.now();
          const before=snap.data(),history=[...(before.editHistory||[]).slice(-98),{action:"edit",at:now.toMillis(),byUid:uid,byName:name}];
          const merged={...before,...fields,updatedAt:now,updatedByUid:uid,updatedByName:name,editCount:(before.editCount||0)+1,editHistory:history};
          tx.set(db.doc(`${collection}/${id}`),merged,{merge:true});
          commitRate();return {record:serialize({data:()=>merged}),updatedByName:name};
        });
        try{
          for(const targetSector of result.record.sharedWithSectors||[]){
            const targets=await shareTargets(d.teamId,targetSector,uid);
            if(targets.length)await messaging().sendEachForMulticast({tokens:targets.map(t=>t.token),android:{priority:"high"},data:{
              type:"record_updated",recordType:collection==="incidents"?"occurrence":"round",recordId:id,teamId:d.teamId,
              title:String(result.record.title||"Registro atualizado").slice(0,80),sharedBy:result.updatedByName,targetSector,sharedAt:String(Date.now())
            }});
          }
        }catch(e){console.error("Falha ao notificar atualização de ficha compartilhada",e);}
        return {record:result.record};
      },
      async softDelete(req) {
        const uid=requireUid(req),d=input(req,["teamId",idKey]),id=validate(()=>teamIdentifier(d[idKey]));
        return db.runTransaction(async tx=>{
          const snap=await tx.get(db.doc(`${collection}/${id}`));
          if(!snap.exists||snap.data().teamId!==d.teamId)throw new HttpsError("not-found","Registro não encontrado.");
          if(snap.data().deleted===true)throw new HttpsError("failed-precondition","Registro já excluído.");
          const {name}=await authorizeMutation(tx,req,uid,d.teamId,snap.data()),commitRate=await rate(tx,uid,"update"),now=Timestamp.now();
          const before=snap.data(),history=[...(before.editHistory||[]).slice(-98),{action:"delete",at:now.toMillis(),byUid:uid,byName:name}];
          const merged={...before,deleted:true,deletedAt:now,deletedByUid:uid,deletedByName:name,updatedAt:now,updatedByUid:uid,updatedByName:name,editCount:(before.editCount||0)+1,editHistory:history};
          tx.set(db.doc(`${collection}/${id}`),merged,{merge:true});
          commitRate();return {record:serialize({data:()=>merged})};
        });
      }
    };
  }
  const incidents=handlers("incidents",INCIDENT_TYPES,"incidentId","type","actionsTaken","OPEN"),rounds=handlers("roundReports",ROUND_TYPES,"reportId","findingType","immediateAction","IDENTIFIED");
  return {createIncident:incidents.create,listIncidents:incidents.list,getIncidentDetails:incidents.details,shareIncident:incidents.share,updateIncident:incidents.update,softDeleteIncident:incidents.softDelete,
    createRoundReport:rounds.create,listRoundReports:rounds.list,getRoundReportDetails:rounds.details,shareRoundReport:rounds.share,updateRoundReport:rounds.update,softDeleteRoundReport:rounds.softDelete};
}
