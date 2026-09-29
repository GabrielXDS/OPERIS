import {defineString} from "firebase-functions/params";

// Trusted deployment configuration only. Missing/unknown values fail closed.
// Never read a build mode, role or attestation switch from callable request data.
export const appEnvironment = defineString("ALERTA_ENV", {default:"release"});
export const callableOptions = Object.freeze({
  enforceAppCheck: appEnvironment.equals("pilot").thenElse(false, true),
});
