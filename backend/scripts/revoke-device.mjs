import {initializeApp,applicationDefault} from "firebase-admin/app";
import {getFirestore,FieldValue} from "firebase-admin/firestore";
const [uid]=process.argv.slice(2);
if (!uid || !/^[a-zA-Z0-9_-]{1,128}$/.test(uid)) throw new Error("Uso: node scripts/revoke-device.mjs UID");
initializeApp({credential:applicationDefault()});
const db=getFirestore();
await db.runTransaction(async tx=>{
 const ref=db.doc("members/"+uid), member=await tx.get(ref);
 if (!member.exists) throw new Error("Dispositivo não encontrado.");
 const deviceRef=db.doc("teams/"+member.data().teamId+"/devices/"+uid);
 const device=await tx.get(deviceRef);
 tx.update(ref,{enabled:false});
 if(device.exists) tx.update(deviceRef,{enabled:false,fcmToken:FieldValue.delete()});
});
console.log("Dispositivo revogado. O cadastro permanece reservado no limite da equipe.");

