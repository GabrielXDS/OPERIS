import test from "node:test";
import assert from "node:assert/strict";
import {normalizeInvite,availability,operationalStatus,deviceReady,cleanName,ONLINE_MS,INVITE_ALPHABET} from "../src/policy.js";
import {generateInvite} from "../src/teams.js";

test("convite normaliza sem permitir caracteres ambíguos",()=>{
 assert.equal(normalizeInvite(" ab7k-92qd "),"AB7K92QD");
 for(const value of ["A".repeat(9),"AB0K92QD","AB1K92QD","ABLK92QD","../teams",null])assert.throws(()=>normalizeInvite(value));
});
test("códigos usam fonte criptográfica, oito caracteres do alfabeto permitido",()=>{
 const codes=Array.from({length:1000},generateInvite);
 assert.equal(new Set(codes).size,1000);
 assert.ok(codes.every(c=>c.length===8 && [...c].every(x=>INVITE_ALPHABET.includes(x))));
});
test("status limita enums e motivo obrigatório de pausa",()=>{
 assert.deepEqual(availability("available",null),{availability:"available",pauseReason:null});
 for(const reason of ["lunch","dinner","break","other"])assert.equal(availability("paused",reason).pauseReason,reason);
 assert.throws(()=>availability("owner",null));assert.throws(()=>availability("paused","arbitrary"));assert.throws(()=>availability("available","lunch"));
});
test("presença expirada prevalece sobre pausa e usa limite exato",()=>{
 assert.equal(operationalStatus(1000,"available",1000+ONLINE_MS),"online");
 assert.equal(operationalStatus(1000,"paused",1001+ONLINE_MS),"offline");
 assert.equal(operationalStatus(null,"available",1000),"offline");
 assert.equal(operationalStatus(1000,"paused",1100),"paused");
 assert.equal(operationalStatus(10000,"available",0),"offline");
});
test("prontidão requer token e diagnóstico, pausa não a altera",()=>{
 const d={enabled:true,fcmToken:"t".repeat(80),notificationsEnabled:true,channelReady:true,audioReady:true,availability:"paused"};
 assert.equal(deviceReady(d),true); // clientes antigos sem o novo campo continuam compatíveis
 assert.equal(deviceReady({...d,fullScreenIntentAllowed:true}),true);
 assert.equal(deviceReady({...d,fullScreenIntentAllowed:false}),false);
 for(const key of ["enabled","notificationsEnabled","channelReady","audioReady"])assert.equal(deviceReady({...d,[key]:false}),false);
 assert.equal(deviceReady({...d,fcmToken:""}),false);
});
test("nomes saneados e limitados",()=>{
 assert.equal(cleanName("  Brigada \n UNIP  ",60),"Brigada UNIP");
 assert.throws(()=>cleanName("\u0001"));assert.throws(()=>cleanName("x".repeat(61),60));
});

