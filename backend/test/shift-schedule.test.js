import test from "node:test";
import assert from "node:assert/strict";
import {isOfficialShiftWindow,officialShiftWindow,localParts,isPilotScheduledDate,nextScheduledStart,pilotBoundary} from "../src/shift-schedule.js";
const at=iso=>Date.parse(iso);

test("pilot 12x36 starts on 26/09/2026 and repeats every 48h",()=>{
  assert.equal(isPilotScheduledDate({year:2026,month:9,day:26}),true);
  assert.equal(isPilotScheduledDate({year:2026,month:9,day:27}),false);
  assert.equal(isPilotScheduledDate({year:2026,month:9,day:28}),true);
  assert.equal(isPilotScheduledDate({year:2026,month:9,day:30}),true);
  assert.equal(isPilotScheduledDate({year:2026,month:10,day:2}),true);
});

test("parity changes naturally after a 31-day month",()=>{
  assert.equal(isPilotScheduledDate({year:2026,month:10,day:30}),true);
  assert.equal(isPilotScheduledDate({year:2026,month:11,day:1}),true);
  assert.equal(isPilotScheduledDate({year:2026,month:11,day:2}),false);
  assert.equal(isPilotScheduledDate({year:2026,month:11,day:3}),true);
});

test("scheduled shift opens 19:00 and closes exactly 07:00",()=>{
  assert.equal(isOfficialShiftWindow(at("2026-09-26T21:59:59Z")),false);
  assert.equal(isOfficialShiftWindow(at("2026-09-26T22:00:00Z")),true);
  assert.equal(isOfficialShiftWindow(at("2026-09-27T09:59:59Z")),true);
  assert.equal(isOfficialShiftWindow(at("2026-09-27T10:00:00Z")),false);
  assert.equal(isOfficialShiftWindow(at("2026-09-27T22:00:00Z")),false);
  assert.equal(isOfficialShiftWindow(at("2026-09-28T22:00:00Z")),true);
});

test("window stores exact Brasilia boundaries and next shift",()=>{
  const w=officialShiftWindow(at("2026-09-27T00:00:00Z"));
  assert.deepEqual(localParts(w.startMs),{year:2026,month:9,day:26,hour:19,minute:0,second:0});
  assert.deepEqual(localParts(w.endMs),{year:2026,month:9,day:27,hour:7,minute:0,second:0});
  const next=nextScheduledStart(at("2026-09-27T12:00:00Z"));
  assert.deepEqual(localParts(next),{year:2026,month:9,day:28,hour:19,minute:0,second:0});
});


test("boundary notifications follow the 12x36 calendar",()=>{
  assert.deepEqual(pilotBoundary("START",at("2026-09-26T22:00:00Z")),{active:true,dateLabel:"26/09/2026",key:"2026-09-26",scheduledDate:{year:2026,month:9,day:26}});
  assert.equal(pilotBoundary("START",at("2026-09-27T22:00:00Z")).active,false);
  assert.deepEqual(pilotBoundary("END",at("2026-09-27T10:00:00Z")).active,true);
  assert.equal(pilotBoundary("END",at("2026-09-28T10:00:00Z")).active,false);
});
