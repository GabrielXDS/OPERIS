import {initializeApp,applicationDefault} from "firebase-admin/app";
import {getFirestore,FieldValue,Timestamp} from "firebase-admin/firestore";
import {randomUUID} from "node:crypto";
import {migrateLegacyDevice} from "../src/legacy-migration.js";
import {generateInvite} from "../src/teams.js";

const args=process.argv.slice(2);
const value=k=>args[args.indexOf(k)+1];
const project=args.includes("--project")?value("--project"):null;
const owner=args.includes("--owner-uid")?value("--owner-uid"):null;
const apply=args.includes("--apply");
if(!project || !owner || !/^[a-zA-Z0-9_-]{1,128}$/.test(owner))
  throw new Error("Uso: node scripts/migrate-v020.mjs --project ID --owner-uid UID [--apply]");
if(process.env.FIRESTORE_EMULATOR_HOST && project!=="demo-alerta-equipe") throw new Error("Projeto de emulador inválido.");
initializeApp({projectId:project,...(!process.env.FIRESTORE_EMULATOR_HOST?{credential:applicationDefault()}: {})});
const db=getFirestore(),teamRef=db.doc("teams/brigada_01"),team=await teamRef.get();
if(!team.exists || team.data().enabled!==true)throw new Error("Brigada 01 existente/ativa é obrigatória.");
if(team.data().ownerUid && team.data().ownerUid!==owner)throw new Error("Outro proprietário já definido; migração não transfere administração.");
const devices=await teamRef.collection("devices").get();
const eligible=[];
for(const device of devices.docs) {
  const member=await db.doc("members/"+device.id).get();
  if(device.data().enabled===true && member.data()?.enabled===true && member.data().teamId==="brigada_01")eligible.push(device.id);
}
if(!eligible.includes(owner))throw new Error("Owner deve ser um integrante legado ativo da Brigada 01.");
console.log(JSON.stringify({project,teamId:"brigada_01",mode:apply?"APPLY":"DRY_RUN",eligibleDevices:eligible.length,
  actions:["Adicionar users/devices/memberships ausentes sem apagar dados","Definir owner explícito apenas se ainda não definido","Criar convite curto apenas se ausente","Preservar documentos legados e tokens"]}));
if(!apply)process.exit(0);
for(const uid of eligible)await migrateLegacyDevice(db,uid);
for(let i=0;i<5;i++){
  const code=generateInvite(),inviteId=randomUUID();
  try {
    await db.runTransaction(async tx=>{
      const [t,m]=await tx.getAll(teamRef,teamRef.collection("members").doc(owner));
      if(t.data().ownerUid && t.data().ownerUid!==owner)throw new Error("Owner alterado; operação cancelada.");
      if(!m.exists || !m.data().enabled)throw new Error("Owner não está ativo.");
      const needsInvite=!t.data().inviteCode,ir=db.doc("teamInvites/"+code);
      const collision=needsInvite?await tx.get(ir):null;
      if(collision?.exists)throw new Error("CODE_COLLISION");
      tx.update(teamRef,{name:t.data().name||"Brigada 01",ownerUid:owner,active:t.data().active!==false,
        schemaVersion:2,...(needsInvite?{inviteCode:code,inviteId}: {})});
      tx.update(m.ref,{role:"owner"});
      if(needsInvite)tx.create(ir,{teamId:"brigada_01",inviteId,active:true,
        createdAt:FieldValue.serverTimestamp(),expiresAt:Timestamp.fromMillis(Date.now()+7*86400000)});
    });
    break;
  }catch(e){if(e.message!=="CODE_COLLISION" || i===4)throw e;}
}
console.log("Migração concluída. O proprietário consulta o convite no aplicativo. Nenhum segredo foi impresso.");
