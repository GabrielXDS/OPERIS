import test from "node:test";
import assert from "node:assert/strict";
import {recipients,cooling,uuid,text,expired} from "../src/policy.js";
test("A nunca recebe; B/C ativos recebem; desativados e tokens ausentes excluídos",()=>{
 assert.deepEqual(recipients([{id:"A",enabled:true,fcmToken:"a"},{id:"B",enabled:true,fcmToken:"b"},{id:"C",enabled:true,fcmToken:"c"},{id:"D",enabled:false,fcmToken:"d"},{id:"E",enabled:true}],"A").map(d=>d.id),["B","C"]);
});
test("limite exato de cinco segundos",()=>{assert.equal(cooling(1000,5999),true);assert.equal(cooling(1000,6000),false);assert.equal(cooling(undefined,1),false);});
test("rejeita identificadores arbitrários",()=>{assert.throws(()=>uuid("../abc"));assert.equal(uuid("12345678-1234-4234-8234-123456789abc"),"12345678-1234-4234-8234-123456789abc");});
test("valida tipo e tamanho",()=>{assert.throws(()=>text({},1,40,"name"));assert.throws(()=>text(" ",1,40,"name"));assert.equal(text(" Portaria ",1,40,"name"),"Portaria");});
test("alertas expiram em 60 segundos",()=>{assert.equal(expired(1000,60999),false);assert.equal(expired(1000,61000),true);});

