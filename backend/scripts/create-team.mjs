import {initializeApp,applicationDefault} from "firebase-admin/app";
import {getFirestore,Timestamp} from "firebase-admin/firestore";
import {randomBytes,createHash} from "node:crypto";
const [teamId] = process.argv.slice(2);
if (!teamId || !/^[a-zA-Z0-9_-]{1,64}$/.test(teamId)) throw new Error("Uso: node scripts/create-team.mjs equipe_unip_01");
initializeApp({credential:applicationDefault()});
const db=getFirestore(), code=randomBytes(24).toString("hex").toUpperCase();
await db.runTransaction(async tx=>{
 const ref=db.doc("teams/"+teamId), team=await tx.get(ref);
 if (!team.exists) tx.create(ref,{enabled:true,deviceCount:0});
 tx.create(db.doc("invites/"+createHash("sha256").update(code).digest("hex")),{teamId,enabled:true,expiresAt:Timestamp.fromMillis(Date.now()+7*86400000)});
});
console.log("Código de ingresso (válido por 7 dias, compartilhe somente com a equipe):\n"+code);

