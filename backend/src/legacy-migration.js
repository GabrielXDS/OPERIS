import {FieldValue} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {requireEnabled,validate} from "./teams.js";
import {teamIdentifier,MAX_TEAMS} from "./policy.js";
const stamp=()=>FieldValue.serverTimestamp();
// Historical opt-in utility; never invoked by v0.2.1 application endpoints.
// Idempotent, additive bridge. Existing denials are never turned into new memberships.
export async function migrateLegacyDevice(db, uid) {
  return db.runTransaction(async tx => {
    const legacy = await tx.get(db.doc("members/"+uid));
    if (!legacy.exists) return null;
    const old = requireEnabled(legacy), id = validate(()=>teamIdentifier(old.teamId));
    const refs = [db.doc("teams/"+id),db.doc("teams/"+id+"/devices/"+uid),
      db.doc("teams/"+id+"/members/"+uid),db.doc("users/"+uid),db.doc("devices/"+uid)];
    const [team, device, membership, user, privateDevice] = await tx.getAll(...refs);
    requireEnabled(team); const d = requireEnabled(device);
    if (membership.exists) requireEnabled(membership);
    if (privateDevice.exists) requireEnabled(privateDevice);
    if (!membership.exists) tx.create(refs[2],{uid,role:"member",enabled:true,joinedAt:stamp(),legacy:true});
    if (!user.exists) tx.create(refs[3],{name:d.name,teamIds:[id],createdAt:stamp(),updatedAt:stamp()});
    else if (!(user.data().teamIds || []).includes(id)) {
      if ((user.data().teamIds || []).length >= MAX_TEAMS) throw new HttpsError("resource-exhausted","Limite de equipes.");
      tx.update(refs[3],{teamIds:FieldValue.arrayUnion(id),updatedAt:stamp()});
    }
    if (!privateDevice.exists) tx.create(refs[4],{
      enabled:true,fcmToken:d.fcmToken || "",legacyTeamId:id,availability:"available",pauseReason:null,
      createdAt:stamp(),lastSeenAt:d.lastSeen || null,lastAlertAt:d.lastAlertAt || null,appReady:false
    });
    return {teamId:id,name:d.name,deviceId:uid};
  });
}


