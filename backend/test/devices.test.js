import test from "node:test";
import assert from "node:assert/strict";
import {inmem} from "./helpers/inmem.js";
import {requestedDeviceId, requireActiveDevice} from "../src/devices.js";

const request = (deviceId, uid = "person") => ({auth:{uid},data:{deviceId}});

test("deviceId é independente do uid e pertence à sessão autenticada", async () => {
  const db=inmem(new Map([["devices/install-1",{uid:"person",enabled:true}]]));
  const tx={get:async ref=>(await db.getAll(ref))[0]};
  const device=await requireActiveDevice(db,tx,request("install-1"),"person");
  assert.equal(device.id,"install-1");
  assert.notEqual(device.id,"person");
  await assert.rejects(requireActiveDevice(db,tx,request("install-1","other"),"other"),e=>e.code==="permission-denied");
});

test("instalação desabilitada é rejeitada e clientes legados continuam sem deviceId", async () => {
  const db=inmem(new Map([["devices/person",{enabled:true}],["devices/old-install",{uid:"person",enabled:false}]]));
  const tx={get:async ref=>(await db.getAll(ref))[0]};
  assert.equal((await requireActiveDevice(db,tx,{auth:{uid:"person"},data:{}},"person")).legacy,true);
  await assert.rejects(requireActiveDevice(db,tx,request("old-install"),"person"),e=>e.code==="permission-denied");
  assert.equal(requestedDeviceId({data:{}}),null);
});

test("cliente 4.1 funciona até a conta migrar; depois recebe DEVICE_REPLACED", async () => {
  const store=new Map([
    ["devices/person",{enabled:true}],
    ["users/person",{name:"Pessoa"}],
  ]);
  const db=inmem(store);
  const tx={get:async ref=>(await db.getAll(ref))[0]};
  assert.equal((await requireActiveDevice(db,tx,{auth:{uid:"person"},data:{}},"person")).legacy,true);
  store.set("users/person",{name:"Pessoa",activeDeviceId:"install-2"});
  await assert.rejects(requireActiveDevice(db,tx,{auth:{uid:"person"},data:{}},"person"),
    e=>e.code==="failed-precondition" && e.details==="DEVICE_REPLACED");
});

test("instalação 4.2 ativa é aceita e a anterior desabilitada é recusada", async () => {
  const db=inmem(new Map([
    ["users/person",{activeDeviceId:"install-new"}],
    ["devices/install-new",{uid:"person",enabled:true}],
    ["devices/install-old",{uid:"person",enabled:false}],
  ]));
  const tx={get:async ref=>(await db.getAll(ref))[0]};
  assert.equal((await requireActiveDevice(db,tx,request("install-new"),"person")).id,"install-new");
  await assert.rejects(requireActiveDevice(db,tx,request("install-old"),"person"),e=>e.code==="permission-denied");
});
