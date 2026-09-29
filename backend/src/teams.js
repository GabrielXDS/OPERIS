import {FieldValue, Timestamp} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {randomInt, randomUUID, createHash} from "node:crypto";
import {requestedDeviceId, requireActiveDevice} from "./devices.js";
import {text, uuid, cleanName, normalizeInvite, teamIdentifier, availability, operationalStatus, deviceReady, INVITE_ALPHABET, MAX_TEAMS} from "./policy.js";

export const requireUid = req => {
  if (!req.auth?.uid) throw new HttpsError("unauthenticated","Autenticação necessária.");
  return req.auth.uid;
};
export function validate(fn) {
  try { return fn(); } catch { throw new HttpsError("invalid-argument","Confira os dados informados."); }
}
export function requireEnabled(snap) {
  if (!snap.exists || snap.data().enabled !== true || snap.data().active === false)
    throw new HttpsError("permission-denied","Cadastro ou equipe indisponível.");
  return snap.data();
}
export function requireActiveMembership(snap) {
  const member=requireEnabled(snap);
  if(member.status!=="active")throw new HttpsError("permission-denied","Participação ainda não aprovada.");
  return member;
}
const stamp = () => FieldValue.serverTimestamp();
const millis = v => v?.toMillis?.() ?? null;
const teamName = (id, data) => data.name || id;
export function generateInvite() { return Array.from({length:8},()=>INVITE_ALPHABET[randomInt(INVITE_ALPHABET.length)]).join(""); }

export function teamOperations(db) {
  async function rate(req, action, limit=10, window=60_000, ipMultiplier=10) {
    const uid=requireUid(req);
    const ip=req.rawRequest?.ip;
    const keys=[action+"_u_"+uid];
    if(ip) keys.push(action+"_ip_"+createHash("sha256").update(ip).digest("hex"));
    await db.runTransaction(async tx=>{
      const refs=keys.map(k=>db.doc("operationLimits/"+k)), snaps=await tx.getAll(...refs), now=Date.now();
      snaps.forEach((s,i)=>{
        const fresh=s.exists && now-millis(s.data().startedAt)<window;
        const count=fresh ? s.data().count : 0;
        const threshold = i === 0 ? limit : limit * ipMultiplier;
        if(count >= threshold) throw new HttpsError("resource-exhausted","Muitas tentativas. Aguarde para tentar novamente.");
      });
      snaps.forEach((s,i)=>{
        const fresh=s.exists && now-millis(s.data().startedAt)<window;
        tx.set(refs[i],{count:fresh?s.data().count+1:1,startedAt:fresh?s.data().startedAt:Timestamp.now(),
          expiresAt:Timestamp.fromMillis(now+window*2)});
      });
    });
  }
  async function authorize(uid,id) {
    const [team,member] = await db.getAll(db.doc("teams/"+id),db.doc("teams/"+id+"/members/"+uid));
    const t=requireEnabled(team),m=requireActiveMembership(member);
    return {team:t,member:m};
  }
  async function profile(req) {
    const uid=requireUid(req), token=validate(()=>text(req.data?.token,20,4096,"token")), deviceId=requestedDeviceId(req);
    const name=req.data?.name == null ? null : validate(()=>cleanName(req.data?.name));
    const operationalFunction=req.data?.operationalFunction == null ? null : String(req.data.operationalFunction).trim().toUpperCase();
    if(operationalFunction!=null && !["BRIGADISTA","VIGILANTE","AGP"].includes(operationalFunction))
      throw new HttpsError("invalid-argument","Função operacional inválida.");
    await rate(req,"profile",10);
    if (!deviceId) {
      if (!name) throw new HttpsError("failed-precondition","Informe seu nome primeiro.");
      await db.runTransaction(async tx=>{
        const ur=db.doc("users/"+uid), dr=db.doc("devices/"+uid);
        const [u,d]=await tx.getAll(ur,dr);
        if(d.exists) requireEnabled(d);
        const existingFunction=u.data()?.operationalFunction;
        if(existingFunction && existingFunction!==operationalFunction)
          throw new HttpsError("failed-precondition","A função operacional já foi definida e não pode ser alterada pelo aplicativo.");
        tx.set(ur,{name,...((existingFunction||operationalFunction)?{operationalFunction:existingFunction||operationalFunction}:{}),updatedAt:stamp(),...(!u.exists?{createdAt:stamp(),teamIds:[]}: {})},{merge:true});
        tx.set(dr,{enabled:true,fcmToken:token,lastTokenSyncAt:stamp(),...(!d.exists?{createdAt:stamp(),availability:"available",pauseReason:null}: {})},{merge:true});
      });
      return {deviceId:uid,uid,name,operationalFunction};
    }
    const active = await db.collection("devices").where("uid","==",uid).get();
    await db.runTransaction(async tx=>{
      const ur=db.doc("users/"+uid), dr=db.doc("devices/"+deviceId), legacy=db.doc("devices/"+uid);
      const [u,d,old]=await tx.getAll(ur,dr,legacy);
      if(!u.exists && !name) throw new HttpsError("failed-precondition","Informe seu nome primeiro.");
      const existingFunction=u.data()?.operationalFunction;
      if(existingFunction && operationalFunction && existingFunction!==operationalFunction)
        throw new HttpsError("failed-precondition","A função operacional já foi definida e não pode ser alterada pelo aplicativo.");
      active.docs.filter(s=>s.id!==deviceId && s.data().enabled===true).forEach(s=>tx.update(s.ref,{enabled:false,deactivatedAt:stamp()}));
      if(legacy.exists && legacy.id!==deviceId) tx.update(legacy,{supersededBy:deviceId,updatedAt:stamp()});
      tx.set(ur,{...(name?{name}:{}),...((!existingFunction&&operationalFunction)?{operationalFunction}:{}),activeDeviceId:deviceId,updatedAt:stamp(),...(!u.exists?{createdAt:stamp(),teamIds:[]}: {})},{merge:true});
      tx.set(dr,{uid,enabled:true,fcmToken:token,lastTokenSyncAt:stamp(),
        ...(!d.exists?{createdAt:stamp(),availability:"available",pauseReason:null}: {})},{merge:true});
    });
    const user=await db.doc("users/"+uid).get();
    return {deviceId,uid,name:user.data()?.name||"",operationalFunction:user.data()?.operationalFunction||"",lastSelectedTeamId:user.data()?.lastSelectedTeamId||""};
  }
  async function create(req) {
    const uid=requireUid(req),name=validate(()=>cleanName(req.data?.name,60)),requestId=validate(()=>uuid(req.data?.requestId));
    const requestedFunction=String(req.data?.operationalFunction||"").trim().toUpperCase();
    
    const requestRef=db.doc("teamCreationRequests/"+uid+"_"+requestId);
    const previous=await requestRef.get();
    if(previous.exists) {
      const a=await authorize(uid,previous.data().teamId);
      return {teamId:previous.data().teamId,teamName:teamName(previous.data().teamId,a.team),role:a.member.role};
    }
    await rate(req,"createTeam",5,86400000);
    for(let i=0;i<5;i++) {
      const code=generateInvite(),inviteId=randomUUID(),tr=db.collection("teams").doc(),ir=db.doc("teamInvites/"+code);
      try {
        return await db.runTransaction(async tx=>{
          const ur=db.doc("users/"+uid);
          await requireActiveDevice(db,tx,req,uid);
          const [u,invite,old]=await tx.getAll(ur,ir,requestRef);
          if(!u.exists) throw new HttpsError("failed-precondition","Informe seu nome primeiro.");
          const ownerFunction=["BRIGADISTA","VIGILANTE","AGP"].includes(requestedFunction)?requestedFunction:String(u.data().operationalFunction||"").toUpperCase();
          if(!["BRIGADISTA","VIGILANTE","AGP"].includes(ownerFunction)) throw new HttpsError("failed-precondition","Selecione seu cargo na equipe.");
          if(old.exists) return {teamId:old.data().teamId};
          if((u.data().teamIds||[]).length>=MAX_TEAMS) throw new HttpsError("resource-exhausted","Limite de 20 equipes.");
          if(invite.exists) throw new Error("CODE_COLLISION");
          const expiresAt=Timestamp.fromMillis(Date.now()+7*86400000);
          tx.create(tr,{name,ownerUid:uid,requireApproval:true,enabled:true,active:true,deviceCount:1,pendingCount:0,inviteCode:code,inviteId,createdAt:stamp()});
          tx.create(tr.collection("members").doc(uid),{uid,role:"owner",operationalFunction:ownerFunction,status:"active",enabled:true,joinedAt:stamp()});
          tx.create(ir,{teamId:tr.id,inviteId,active:true,createdAt:stamp(),expiresAt});
          tx.update(ur,{teamIds:FieldValue.arrayUnion(tr.id),updatedAt:stamp()});
          tx.create(requestRef,{teamId:tr.id,createdAt:stamp()});
          return {teamId:tr.id,teamName:name,role:"owner"};
        });
      } catch(e) {if(e.message!=="CODE_COLLISION") throw e;}
    }
    throw new HttpsError("unavailable","Tente criar novamente.");
  }
  async function invitation(tx,code) {
    const invite=await tx.get(db.doc("teamInvites/"+code));
    if(!invite.exists || invite.data().active!==true || millis(invite.data().expiresAt)<=Date.now())
      throw new HttpsError("not-found","Convite inválido ou expirado.");
    const id=validate(()=>teamIdentifier(invite.data().teamId)),team=await tx.get(db.doc("teams/"+id)),t=requireEnabled(team);
    if(t.inviteId!==invite.data().inviteId || t.inviteCode!==code)
      throw new HttpsError("not-found","Convite revogado.");
    return {id,t,invite:invite.data()};
  }
  async function preview(req) {
    const uid=requireUid(req); await rate(req,"invite",6);
    const code=validate(()=>normalizeInvite(req.data?.inviteCode));
    await requireActiveDevice(db,{get:ref=>ref.get()},req,uid);
    return db.runTransaction(async tx=>{
      const {id,t,invite}=await invitation(tx,code);
      return {teamId:id,teamName:teamName(id,t),expiresAt:millis(invite.expiresAt),requireApproval:t.requireApproval!==false}; // preview never enrolls
    });
  }
  async function join(req) {
    const uid=requireUid(req);await rate(req,"invite",6);
    const code=validate(()=>normalizeInvite(req.data?.inviteCode));
    const requestedFunction=String(req.data?.operationalFunction||"").trim().toUpperCase();
    return db.runTransaction(async tx=>{
      const {id,t,invite}=await invitation(tx,code);
      if(req.data?.expectedTeamId!==id) throw new HttpsError("failed-precondition","Confira novamente a equipe.");
      const mr=db.doc("teams/"+id+"/members/"+uid),ur=db.doc("users/"+uid);
      await requireActiveDevice(db,tx,req,uid);
      const [m,u]=await tx.getAll(mr,ur);
      if(!u.exists)throw new HttpsError("failed-precondition","Informe seu nome.");
      const selectedFunction=["BRIGADISTA","VIGILANTE","AGP"].includes(requestedFunction)?requestedFunction:String(u.data().operationalFunction||"").toUpperCase();
      if(!["BRIGADISTA","VIGILANTE","AGP"].includes(selectedFunction))throw new HttpsError("invalid-argument","Selecione seu cargo na equipe.");
      if(m.data()?.status==="active") {
        const member=requireActiveMembership(m);
        return {teamId:id,teamName:teamName(id,t),role:member.role,status:"active",operationalFunction:member.operationalFunction||u.data().operationalFunction||""};
      }
      if(m.data()?.status==="pending" && m.data()?.operationalFunction && m.data().operationalFunction!==selectedFunction)
        throw new HttpsError("failed-precondition","O cargo desta solicitação já foi definido. Aguarde a aprovação do proprietário.");
      if(m.exists && !["pending","rejected"].includes(m.data().status))
        throw new HttpsError("permission-denied","Participação desativada.");
      const occupied=new Set([...(u.data().teamIds||[]),...(u.data().requestedTeamIds||[])]);
      if((!occupied.has(id) && occupied.size>=MAX_TEAMS) || (t.deviceCount||0)>=100)
        throw new HttpsError("resource-exhausted","Limite de equipes ou integrantes.");
      const wasPending=m.data()?.status==="pending",needsApproval=t.requireApproval!==false;
      if(needsApproval && !wasPending && (t.pendingCount||0)>=100)
        throw new HttpsError("resource-exhausted","Limite de solicitações pendentes.");
      // Setting requireApproval is server-only. This version creates all teams with it enabled.
      const status=needsApproval?"pending":"active";
      tx.set(mr,{uid,role:"member",operationalFunction:selectedFunction,status,enabled:!needsApproval,inviteId:invite.inviteId,
        requestedAt:wasPending && m.data().inviteId===invite.inviteId?m.data().requestedAt:stamp(),
        ...(needsApproval?{}:{joinedAt:stamp()})});
      if(needsApproval) {
        if(!wasPending)tx.update(db.doc("teams/"+id),{pendingCount:FieldValue.increment(1)});
        tx.update(ur,{requestedTeamIds:FieldValue.arrayUnion(id),updatedAt:stamp()});
      } else {
        tx.update(db.doc("teams/"+id),{deviceCount:FieldValue.increment(1),...(wasPending?{pendingCount:FieldValue.increment(-1)}:{})});
        tx.update(ur,{teamIds:FieldValue.arrayUnion(id),requestedTeamIds:FieldValue.arrayRemove(id),updatedAt:stamp()});
      }
      return {teamId:id,teamName:teamName(id,t),role:"member",status,operationalFunction:selectedFunction};
    });
  }
  async function list(req) {
    const uid=requireUid(req);
    const user=await db.doc("users/"+uid).get();
    const device=await requireActiveDevice(db,{get:ref=>ref.get()},req,uid);
    const ids=[...new Set([...(user.data()?.teamIds||[]),...(user.data()?.requestedTeamIds||[])])].slice(0,MAX_TEAMS);
    const teams=[],requests=[];
    for(const id of ids) {
      const [t,m]=await db.getAll(db.doc("teams/"+id),db.doc("teams/"+id+"/members/"+uid));
      if(t.data()?.enabled!==true || t.data()?.active===false)continue;
      if(m.data()?.status==="active" && m.data()?.enabled===true)
        teams.push({teamId:id,teamName:teamName(id,t.data()),role:m.data().role,operationalFunction:m.data().operationalFunction||user.data()?.operationalFunction||""});
      else if(["pending","rejected"].includes(m.data()?.status))
        requests.push({teamId:id,teamName:teamName(id,t.data()),status:m.data().status,requestedAt:millis(m.data().requestedAt)});
    }
    return {teams,requests,deviceId:device.id,name:user.data()?.name||"",availability:device.data.availability||"available",
      pauseReason:device.data.pauseReason||null};
  }
  async function review(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId)),target=validate(()=>text(req.data?.uid,1,128,"uid"));
    if(!/^[a-zA-Z0-9_-]+$/.test(target) || target===uid || !["approve","reject"].includes(req.data?.action))
      throw new HttpsError("invalid-argument","Solicitação inválida.");
    await rate(req,"review",30);
    return db.runTransaction(async tx=>{
      const tr=db.doc("teams/"+id),mr=tr.collection("members").doc(target),ur=db.doc("users/"+target);
      const [team,manager,request,user]=await tx.getAll(tr,tr.collection("members").doc(uid),mr,ur);
      const t=requireEnabled(team),m=requireActiveMembership(manager);
      await requireActiveDevice(db,tx,req,uid);
      if(!(m.role==="admin" || (m.role==="owner" && t.ownerUid===uid)))
        throw new HttpsError("permission-denied","Somente a administração da equipe.");
      if(!request.exists)throw new HttpsError("not-found","Solicitação não encontrada.");
      const approve=req.data.action==="approve",finalStatus=approve?"active":"rejected";
      if(request.data().status===finalStatus)return {status:finalStatus};
      if(request.data().status!=="pending")throw new HttpsError("failed-precondition","Solicitação já resolvida.");
      if(approve) {
        if(!user.exists || (user.data().teamIds||[]).length>=MAX_TEAMS || (t.deviceCount||0)>=100)
          throw new HttpsError("resource-exhausted","Limite de equipes ou integrantes.");
        const targetDeviceId=user.data()?.activeDeviceId||target;
        const targetDevice=await tx.get(db.doc("devices/"+targetDeviceId));
        requireEnabled(targetDevice);
        if(targetDeviceId!==target && targetDevice.data().uid!==target)
          throw new HttpsError("permission-denied","Aparelho nao pertence a conta.");
        const invite=await tx.get(db.doc("teamInvites/"+t.inviteCode));
        if(!invite.exists || invite.data().active!==true || millis(invite.data().expiresAt)<=Date.now() ||
          invite.data().inviteId!==request.data().inviteId || invite.data().inviteId!==t.inviteId)
          throw new HttpsError("failed-precondition","Convite expirado ou revogado. Solicite nova entrada com um convite válido.");
      }
      tx.update(mr,{status:finalStatus,enabled:approve,reviewedBy:uid,reviewedAt:stamp(),...(approve?{joinedAt:stamp()}: {})});
      tx.update(tr,{pendingCount:FieldValue.increment(-1),...(approve?{deviceCount:FieldValue.increment(1)}:{})});
      if(approve) {
        tx.update(ur,{teamIds:FieldValue.arrayUnion(id),requestedTeamIds:FieldValue.arrayRemove(id),updatedAt:stamp()});
      } else {
        tx.update(ur,{requestedTeamIds:FieldValue.arrayRemove(id),updatedAt:stamp()});
      }
      return {status:finalStatus};
    });
  }
  async function details(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId));
    const {team,member}=await authorize(uid,id);
    const members=await db.collection("teams/"+id+"/members").where("status","==","active").limit(100).get();
    const now=Date.now(), result=[];
    // Última comunicação detalhada (lastSeenAt) é informação técnica sensível:
    // apenas a administração da equipe recebe o timestamp; membros comuns mantêm
    // somente o status operacional derivado (online/pausa/offline) e as contagens.
    const privileged=member.role==="admin" || (member.role==="owner" && team.ownerUid===uid);
    if(members.size) {
      const users=await db.getAll(...members.docs.map(m=>db.doc("users/"+m.id)));
      const devices=await db.getAll(...members.docs.map((m,i)=>db.doc("devices/"+(users[i].data()?.activeDeviceId||m.id))));
      members.docs.forEach((m,i)=>{
        const u=users[i].data(),d=devices[i].data(),deviceId=users[i].data()?.activeDeviceId||m.id;
        if(!d || !d.enabled || !m.data().enabled || (deviceId!==m.id && d.uid!==m.id)) return;
        const status=operationalStatus(millis(d.lastSeenAt),d.availability,now);
        result.push({uid:m.id,name:u?.name||"Integrante",role:m.data().role,operationalFunction:m.data().operationalFunction||u?.operationalFunction||"",status,
          pauseReason:d.pauseReason||null,...(privileged?{lastSeenAt:millis(d.lastSeenAt),deviceHealth:{notificationsEnabled:d.notificationsEnabled===true,channelReady:d.channelReady===true,audioReady:d.audioReady===true,fullScreenIntentAllowed:d.fullScreenIntentAllowed!==false,microphoneGranted:d.microphoneGranted!==false,batteryOptimizationIgnored:d.batteryOptimizationIgnored===true,doNotDisturb:d.doNotDisturb===true,notificationPolicyAccess:d.notificationPolicyAccess===true}}:{}),
          appReady:deviceReady(d),appVersion:d.appVersion||null});
      });
    }
    let invite=null;
    if(member.role==="owner" && team.ownerUid===uid && team.inviteCode) {
      const snap=await db.doc("teamInvites/"+team.inviteCode).get(),v=snap.data();
      if(v) invite={code:team.inviteCode,active:v.active===true && millis(v.expiresAt)>now,expiresAt:millis(v.expiresAt)};
    }
    const requests=[];
    if(member.role==="admin" || (member.role==="owner" && team.ownerUid===uid)) {
      const pending=await db.collection("teams/"+id+"/members").where("status","==","pending").limit(100).get();
      if(pending.size) {
        const profiles=await db.getAll(...pending.docs.map(m=>db.doc("users/"+m.id)));
        pending.docs.forEach((m,i)=>requests.push({uid:m.id,name:profiles[i].data()?.name||"Solicitante",operationalFunction:m.data().operationalFunction||profiles[i].data()?.operationalFunction||"",requestedAt:millis(m.data().requestedAt)}));
      }
    }
    const emergencyContacts=Array.isArray(team.emergencyContacts)?team.emergencyContacts.slice(0,20).map(c=>({
      id:String(c.id||""),name:String(c.name||""),phone:String(c.phone||"")
    })).filter(c=>c.id && c.name && c.phone):[];
    let auditEntries=[];
    if(privileged){
      const auditCollection=db.collection("teams/"+id+"/audit");
      if(typeof auditCollection.limit==="function") {
        const audit=await auditCollection.limit(100).get();
        auditEntries=audit.docs.map(a=>({auditId:a.id,...a.data(),at:millis(a.data().at)})).sort((a,b)=>(b.at||0)-(a.at||0)).slice(0,30);
      }
    }
    const versionHealth=privileged?Object.entries(result.reduce((acc,m)=>{const v=m.appVersion||"sem versão";acc[v]=(acc[v]||0)+1;return acc;},{})).map(([version,count])=>({version,count})):[];
    return {teamId:id,teamName:teamName(id,team),myRole:member.role,myOperationalFunction:member.operationalFunction||"",invite,requests,emergencyContacts,auditEntries,versionHealth,serverNow:now,totalMembers:result.length,
      onlineCount:result.filter(m=>m.status==="online").length,pausedCount:result.filter(m=>m.status==="paused").length,
      offlineCount:result.filter(m=>m.status==="offline").length,
      readyCount:result.filter(m=>m.status!=="offline" && m.appReady).length,members:result};
  }
  async function setEmergencyContacts(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId));
    const raw=req.data?.contacts;
    if(!Array.isArray(raw) || raw.length>20) throw new HttpsError("invalid-argument","Contatos inválidos.");
    const contacts=raw.map(item=>{
      const value=item&&typeof item==="object"?item:{};
      const contactId=validate(()=>uuid(value.id));
      const name=validate(()=>text(value.name,1,60,"nome")).trim();
      const phone=validate(()=>text(value.phone,3,25,"telefone")).trim();
      if(!/^[0-9+() #*.-]{3,25}$/.test(phone)) throw new HttpsError("invalid-argument","Telefone inválido.");
      return {id:contactId,name,phone};
    });
    const tr=db.doc("teams/"+id),mr=tr.collection("members").doc(uid);
    const [ts,ms]=await db.getAll(tr,mr);
    const t=requireEnabled(ts),m=requireActiveMembership(ms);
    await requireActiveDevice(db,{get:ref=>ref.get()},req,uid);
    if(!(m.role==="admin" || (m.role==="owner" && t.ownerUid===uid)))
      throw new HttpsError("permission-denied","Somente a administração da equipe.");
    await tr.update({emergencyContacts:contacts,emergencyContactsUpdatedAt:stamp(),emergencyContactsUpdatedBy:uid});
    return {contacts};
  }
  function audit(tx,teamId,actorUid,actorName,action,targetUid=null,targetName=null,details={}) {
    const ref=db.collection("teams/"+teamId+"/audit").doc();
    tx.create(ref,{auditId:ref.id,actorUid,actorName,action,targetUid,targetName,details,at:stamp()});
  }
  async function updateMemberFunction(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId)),target=validate(()=>text(req.data?.uid,1,128,"uid"));
    const fn=String(req.data?.operationalFunction||"").trim().toUpperCase();
    if(!/^[a-zA-Z0-9_-]+$/.test(target)||!["BRIGADISTA","VIGILANTE","AGP"].includes(fn))throw new HttpsError("invalid-argument","Cargo inválido.");
    await rate(req,"memberFunction",30);
    return db.runTransaction(async tx=>{
      const tr=db.doc("teams/"+id),managerRef=tr.collection("members").doc(uid),targetRef=tr.collection("members").doc(target);
      const [team,manager,targetSnap,actorUser,targetUser]=await tx.getAll(tr,managerRef,targetRef,db.doc("users/"+uid),db.doc("users/"+target));
      const t=requireEnabled(team),m=requireActiveMembership(manager),member=requireActiveMembership(targetSnap);await requireActiveDevice(db,tx,req,uid);
      const owner=m.role==="owner"&&t.ownerUid===uid;
      if(!owner)throw new HttpsError("permission-denied","Somente o proprietário pode editar o cargo dos integrantes.");
      if(target===t.ownerUid||member.role==="owner")throw new HttpsError("permission-denied","O cargo do proprietário não pode ser alterado por este fluxo.");
      tx.update(targetRef,{operationalFunction:fn,updatedAt:stamp(),updatedBy:uid});
      audit(tx,id,uid,actorUser.data()?.name||"Administrador","MEMBER_FUNCTION_CHANGED",target,targetUser.data()?.name||"Integrante",{from:member.operationalFunction||targetUser.data()?.operationalFunction||"",to:fn});
      return {updated:true,uid:target,operationalFunction:fn};
    });
  }
  async function removeMember(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId));
    const target=validate(()=>text(req.data?.uid,1,128,"uid"));
    if(!/^[a-zA-Z0-9_-]+$/.test(target) || target===uid) throw new HttpsError("invalid-argument","Integrante inválido.");
    await rate(req,"removeMember",30);
    return db.runTransaction(async tx=>{
      const tr=db.doc("teams/"+id),callerRef=tr.collection("members").doc(uid),targetRef=tr.collection("members").doc(target),userRef=db.doc("users/"+target);
      const [team,caller,targetSnap,targetUser]=await tx.getAll(tr,callerRef,targetRef,userRef);
      const t=requireEnabled(team),manager=requireActiveMembership(caller),member=requireActiveMembership(targetSnap);
      await requireActiveDevice(db,tx,req,uid);
      const owner=manager.role==="owner" && t.ownerUid===uid, admin=manager.role==="admin";
      if(!owner && !admin) throw new HttpsError("permission-denied","Somente a administração da equipe.");
      if(target===t.ownerUid || member.role==="owner") throw new HttpsError("failed-precondition","O proprietário não pode ser removido da equipe.");
      if(admin && member.role!=="member") throw new HttpsError("permission-denied","Administrador só pode remover membros comuns.");
      tx.update(targetRef,{status:"removed",enabled:false,removedBy:uid,removedAt:stamp()});
      if(targetUser.exists) {
        const userPatch={teamIds:FieldValue.arrayRemove(id),requestedTeamIds:FieldValue.arrayRemove(id),updatedAt:stamp()};
        if(targetUser.data().lastSelectedTeamId===id) userPatch.lastSelectedTeamId=FieldValue.delete();
        tx.update(userRef,userPatch);
      }
      tx.update(tr,{deviceCount:FieldValue.increment(-1)});
      return {removed:true,uid:target};
    });
  }
  async function updateMemberRole(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId)),target=validate(()=>text(req.data?.uid,1,128,"uid"));
    const role=String(req.data?.role||"").trim().toLowerCase();if(!["member","admin"].includes(role))throw new HttpsError("invalid-argument","Papel inválido.");
    return db.runTransaction(async tx=>{
      const tr=db.doc("teams/"+id),[team,manager,targetSnap,actorUser,targetUser]=await tx.getAll(tr,tr.collection("members").doc(uid),tr.collection("members").doc(target),db.doc("users/"+uid),db.doc("users/"+target));
      const t=requireEnabled(team),m=requireActiveMembership(manager),member=requireActiveMembership(targetSnap);await requireActiveDevice(db,tx,req,uid);
      if(m.role!=="owner"||t.ownerUid!==uid)throw new HttpsError("permission-denied","Somente o proprietário pode promover ou rebaixar administradores.");
      if(target===uid||target===t.ownerUid||member.role==="owner")throw new HttpsError("failed-precondition","O proprietário não pode ter seu papel alterado.");
      tx.update(targetSnap.ref,{role,updatedAt:stamp(),updatedBy:uid});audit(tx,id,uid,actorUser.data()?.name||"Proprietário",role==="admin"?"MEMBER_PROMOTED":"MEMBER_DEMOTED",target,targetUser.data()?.name||"Integrante",{from:member.role,to:role});
      return {updated:true,uid:target,role};
    });
  }
  async function setMemberBlocked(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId)),target=validate(()=>text(req.data?.uid,1,128,"uid")),blocked=req.data?.blocked;
    if(typeof blocked!=="boolean")throw new HttpsError("invalid-argument","Estado inválido.");
    return db.runTransaction(async tx=>{
      const tr=db.doc("teams/"+id),[team,manager,targetSnap,actorUser,targetUser]=await tx.getAll(tr,tr.collection("members").doc(uid),tr.collection("members").doc(target),db.doc("users/"+uid),db.doc("users/"+target));
      const t=requireEnabled(team),m=requireActiveMembership(manager);await requireActiveDevice(db,tx,req,uid);if(!targetSnap.exists)throw new HttpsError("not-found","Integrante não encontrado.");
      const targetData=targetSnap.data(),owner=m.role==="owner"&&t.ownerUid===uid,admin=m.role==="admin";
      if(!owner&&!admin)throw new HttpsError("permission-denied","Somente a administração da equipe.");if(target===t.ownerUid||targetData.role==="owner")throw new HttpsError("failed-precondition","O proprietário não pode ser bloqueado.");
      if(admin&&targetData.role!=="member")throw new HttpsError("permission-denied","Administrador só pode bloquear membros comuns.");
      if(blocked){if(targetData.status!=="active"||targetData.enabled!==true)throw new HttpsError("failed-precondition","Integrante não está ativo.");tx.update(targetSnap.ref,{status:"blocked",enabled:false,blockedAt:stamp(),blockedBy:uid});}
      else {if(targetData.status!=="blocked")throw new HttpsError("failed-precondition","Integrante não está bloqueado.");tx.update(targetSnap.ref,{status:"active",enabled:true,unblockedAt:stamp(),unblockedBy:uid});}
      audit(tx,id,uid,actorUser.data()?.name||"Administrador",blocked?"MEMBER_BLOCKED":"MEMBER_UNBLOCKED",target,targetUser.data()?.name||"Integrante",{});return {blocked,uid:target};
    });
  }
  async function dissolve(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId));
    await rate(req,"dissolveTeam",3,86400000);
    let inviteCode=null;
    await db.runTransaction(async tx=>{
      const tr=db.doc("teams/"+id),mr=tr.collection("members").doc(uid);
      const [ts,ms]=await tx.getAll(tr,mr);
      const t=requireEnabled(ts),m=requireActiveMembership(ms);
      await requireActiveDevice(db,tx,req,uid);
      if(m.role!=="owner" || t.ownerUid!==uid) throw new HttpsError("permission-denied","Somente o proprietário pode desfazer a equipe.");
      inviteCode=t.inviteCode||null;
      tx.update(tr,{enabled:false,active:false,dissolvedAt:stamp(),dissolvedBy:uid});
      if(inviteCode) tx.set(db.doc("teamInvites/"+inviteCode),{active:false,revokedAt:stamp()},{merge:true});
    });
    const members=await db.collection("teams/"+id+"/members").limit(100).get();
    const batch=db.batch();
    members.docs.forEach(member=>{
      batch.set(member.ref,{enabled:false,status:"team_dissolved",dissolvedAt:stamp()},{merge:true});
      batch.set(db.doc("users/"+member.id),{teamIds:FieldValue.arrayRemove(id),requestedTeamIds:FieldValue.arrayRemove(id),updatedAt:stamp()},{merge:true});
    });
    await batch.commit();
    return {dissolved:true,teamId:id};
  }
  async function transferOwnership(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId)),target=validate(()=>text(req.data?.uid,1,128,"uid"));
    if(target===uid)throw new HttpsError("invalid-argument","Escolha outro integrante.");
    return db.runTransaction(async tx=>{
      const tr=db.doc("teams/"+id),ownerRef=tr.collection("members").doc(uid),targetRef=tr.collection("members").doc(target);
      const [team,owner,targetSnap,actorUser,targetUser]=await tx.getAll(tr,ownerRef,targetRef,db.doc("users/"+uid),db.doc("users/"+target));
      const t=requireEnabled(team),m=requireActiveMembership(owner),next=requireActiveMembership(targetSnap);await requireActiveDevice(db,tx,req,uid);
      if(m.role!=="owner"||t.ownerUid!==uid)throw new HttpsError("permission-denied","Somente o proprietário atual pode transferir a propriedade.");
      if(next.role==="owner")throw new HttpsError("failed-precondition","Este integrante já é proprietário.");
      tx.update(ownerRef,{role:"admin",updatedAt:stamp(),updatedBy:uid});tx.update(targetRef,{role:"owner",updatedAt:stamp(),updatedBy:uid});tx.update(tr,{ownerUid:target,updatedAt:stamp()});
      audit(tx,id,uid,actorUser.data()?.name||"Proprietário","OWNERSHIP_TRANSFERRED",target,targetUser.data()?.name||"Integrante",{});return {transferred:true,ownerUid:target};
    });
  }
  async function sync(req) {
    const uid=requireUid(req),token=validate(()=>text(req.data?.token,20,4096,"token"));
    await rate(req,"sync",30,60_000,30);
    return db.runTransaction(async tx=>{
      const ur=db.doc("users/"+uid);
      const device=await requireActiveDevice(db,tx,req,uid);
      const u=await tx.get(ur);
      const devData=device.data||{};
      const tokenChanged=devData.fcmToken!==token;
      const fields={};
      let needsUpdate=false;
      if(tokenChanged) {
        fields.fcmToken=token;
        fields.lastTokenSyncAt=stamp();
        needsUpdate=true;
      }
      if(req.data?.diagnostics!=null) {
        const report=req.data.diagnostics;
        if(["notificationsEnabled","channelReady","audioReady"].some(k=>typeof report[k]!=="boolean") ||
          ["fullScreenIntentAllowed","microphoneGranted","batteryOptimizationIgnored","doNotDisturb","notificationPolicyAccess"].some(k=>report[k]!=null&&typeof report[k]!=="boolean"))
          throw new HttpsError("invalid-argument","Diagnóstico inválido.");
        const appVersion=validate(()=>text(report.appVersion,1,32,"version"));
        const diagKeys=["notificationsEnabled","channelReady","audioReady","fullScreenIntentAllowed","microphoneGranted","batteryOptimizationIgnored","doNotDisturb","notificationPolicyAccess"].filter(k=>report[k]!=null);
        const diagChanged=diagKeys.some(k=>report[k]!==devData[k]);
        const versionChanged=appVersion!==devData.appVersion;
        const recently=Date.now()-(millis(devData.lastSeenAt)||0)<45_000;
        if(!recently || diagChanged || versionChanged) {
          Object.assign(fields,{lastSeenAt:stamp(),notificationsEnabled:report.notificationsEnabled,
            channelReady:report.channelReady,audioReady:report.audioReady,appVersion,
            ...(typeof report.fullScreenIntentAllowed==="boolean"?{fullScreenIntentAllowed:report.fullScreenIntentAllowed}:{}),...(typeof report.microphoneGranted==="boolean"?{microphoneGranted:report.microphoneGranted}:{}),...(typeof report.batteryOptimizationIgnored==="boolean"?{batteryOptimizationIgnored:report.batteryOptimizationIgnored}:{}),...(typeof report.doNotDisturb==="boolean"?{doNotDisturb:report.doNotDisturb}:{}),...(typeof report.notificationPolicyAccess==="boolean"?{notificationPolicyAccess:report.notificationPolicyAccess}:{})});
          needsUpdate=true;
        }
      }
      if(needsUpdate) {
        tx.update(device.ref,fields);
      }
      return {deviceId:device.id,teamId:u.data()?.teamIds?.[0] || "",name:u.data()?.name||""};
    });
  }
  async function setStatus(req) {
    const uid=requireUid(req),status=validate(()=>availability(req.data?.availability,req.data?.pauseReason));
    await rate(req,"status",12);
    const device=await requireActiveDevice(db,{get:ref=>ref.get()},req,uid);
    await device.ref.update({...status,statusUpdatedAt:stamp()});
    return status;
  }
  async function deactivate(req) {
    const uid=requireUid(req), device=await requireActiveDevice(db,{get:ref=>ref.get()},req,uid);
    await device.ref.update({enabled:false,deactivatedAt:stamp(),fcmToken:FieldValue.delete()});
    return {deactivated:true};
  }
  async function selectTeam(req) {
    const uid=requireUid(req), id=validate(()=>teamIdentifier(req.data?.teamId));
    await db.runTransaction(async tx=>{
      const member=await tx.get(db.doc("teams/"+id+"/members/"+uid));
      requireActiveMembership(member);
      await requireActiveDevice(db,tx,req,uid);
      tx.set(db.doc("users/"+uid),{lastSelectedTeamId:id,updatedAt:stamp()},{merge:true});
    });
    return {teamId:id};
  }
  async function manageInvite(req) {
    const uid=requireUid(req),id=validate(()=>teamIdentifier(req.data?.teamId)),rotate=req.data?.action==="rotate";
    if(!rotate && req.data?.action!=="revoke") throw new HttpsError("invalid-argument","Ação inválida.");
    await rate(req,"manageInvite",6);
    for(let attempt=0;attempt<5;attempt++){
      const code=generateInvite(),inviteId=randomUUID();
      try {
        return await db.runTransaction(async tx=>{
          const tr=db.doc("teams/"+id),[ts,ms]=await tx.getAll(tr,db.doc("teams/"+id+"/members/"+uid));
          const t=requireEnabled(ts),m=requireActiveMembership(ms);
          await requireActiveDevice(db,tx,req,uid);
          if(m.role!=="owner"||t.ownerUid!==uid) throw new HttpsError("permission-denied","Somente o proprietário.");
          const ir=db.doc("teamInvites/"+code),collision=rotate?await tx.get(ir):null;
          if(collision?.exists) throw new Error("CODE_COLLISION");
          if(t.inviteCode) tx.set(db.doc("teamInvites/"+t.inviteCode),{active:false},{merge:true});
          if(rotate) {
            tx.create(ir,{teamId:id,inviteId,active:true,createdAt:stamp(),expiresAt:Timestamp.fromMillis(Date.now()+7*86400000)});
            tx.update(tr,{inviteCode:code,inviteId});
          }
          return {ok:true};
        });
      } catch(e){if(e.message!=="CODE_COLLISION")throw e;}
    }
    throw new HttpsError("unavailable","Tente novamente.");
  }
  return {profile,create,preview,join,list,details,setEmergencyContacts,updateMemberFunction,updateMemberRole,setMemberBlocked,transferOwnership,removeMember,dissolve,sync,setStatus,deactivate,selectTeam,manageInvite,review,authorize};
}

// Only active, approved memberships can receive. No legacy-team lookup or token replication.
export async function alertTargets(db,teamId,sender) {
  const members=await db.collection("teams/"+teamId+"/members").where("status","==","active").limit(100).get();
  const ids=members.docs.filter(m=>m.id!==sender && m.data().enabled===true).map(m=>m.id);
  if(!ids.length)return [];
  const docs=await Promise.all(ids.map(async uid=>{
    const active=await db.collection("devices").where("uid","==",uid).get();
    return active.docs.find(d=>d.data().enabled===true) || await db.doc("devices/"+uid).get();
  }));
  const result=[],tokens=new Set();
  docs.forEach(d=>{
    const data=d.data();
    if(!data?.enabled || !data.fcmToken || tokens.has(data.fcmToken))return;
    tokens.add(data.fcmToken);result.push({id:d.id,fcmToken:data.fcmToken,ref:d.ref});
  });
  return result;
}
