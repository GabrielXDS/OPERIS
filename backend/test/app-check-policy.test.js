import test from "node:test";
import assert from "node:assert/strict";
import {callableOptions} from "../src/app-check-policy.js";

test("somente configuração pilot do servidor dispensa App Check; padrão protegido", () => {
  const previous=process.env.ALERTA_ENV;
  try {
    for (const mode of [undefined,"","release","debug","PILOT","invalid","pilot"]) {
      if(mode===undefined)delete process.env.ALERTA_ENV;else process.env.ALERTA_ENV=mode;
      assert.equal(callableOptions.enforceAppCheck.value(),mode!=="pilot",String(mode));
    }
  } finally {
    if(previous===undefined)delete process.env.ALERTA_ENV;else process.env.ALERTA_ENV=previous;
  }
});
