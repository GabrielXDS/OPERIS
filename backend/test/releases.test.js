import test from "node:test";
import assert from "node:assert/strict";
import {releaseOperations, RELEASE_POLICY_DOC} from "../src/releases.js";

function fixture(record = undefined) {
  const data = new Map();
  if (record !== undefined) data.set(RELEASE_POLICY_DOC, record);
  const snap = path => ({exists: data.has(path), data: () => data.get(path)});
  const db = {doc: path => ({get: async () => snap(path)})};
  return {ops: releaseOperations(db), data};
}
const req = data => ({auth: {uid: "u"}, data});

const valid = {
  latestVersionCode: 12,
  latestVersionName: "0.3.7",
  minSupportedVersionCode: 10,
  status: "PUBLISHED",
  downloadUrl: "https://example.com/operis.apk",
  releaseNotes: [" Primeira nota ", "Segunda nota"],
  sha256: "a".repeat(64),
  apkSize: 1024,
};

const SHIPPED_KEYS = ["apkSize", "downloadUrl", "latestVersionCode", "latestVersionName", "minSupportedVersionCode", "releaseNotes", "sha256", "status"];

test("releases: missing document never reports an obsolete shipped version", async () => {
  const {ops} = fixture();
  await assert.rejects(ops.getRelease(req()), e => e.code === "not-found" && e.details.reason === "RELEASE_NOT_FOUND");
});

test("releases: uses admin-edited doc and normalizes fields", async () => {
  const {ops} = fixture(valid);
  const out = await ops.getRelease(req());
  assert.equal(out.latestVersionCode, 12);
  assert.equal(out.latestVersionName, "0.3.7");
  assert.equal(out.minSupportedVersionCode, 10);
  assert.equal(out.status, "PUBLISHED");
  assert.match(out.downloadUrl, /^https:\/\//);
  assert.deepEqual(out.releaseNotes, ["Primeira nota", "Segunda nota"]);
  assert.equal(out.sha256, "a".repeat(64));
  assert.equal(out.apkSize, 1024);
});

test("releases: defaults to PUBLISHED for an edited doc without status", async () => {
  const {ops} = fixture({...valid, status: undefined});
  const out = await ops.getRelease(req());
  assert.equal(out.status, "PUBLISHED");
});

test("releases: non-PUBLISHED statuses pass through and unknown status falls back to PUBLISHED", async () => {
  const {ops: draftOps} = fixture({...valid, status: "DRAFT"});
  assert.equal((await draftOps.getRelease(req())).status, "DRAFT");
  const {ops: annOps} = fixture({...valid, status: "ANNOUNCED"});
  assert.equal((await annOps.getRelease(req())).status, "ANNOUNCED");
  const {ops: badOps} = fixture({...valid, status: "dev"});
  assert.equal((await badOps.getRelease(req())).status, "PUBLISHED");
});

test("releases: apkSize is a positive integer or null", async () => {
  const {ops} = fixture({...valid, apkSize: 0});
  await assert.rejects(ops.getRelease(req()), e => e.code === "failed-precondition");
  const {ops: floatOps} = fixture({...valid, apkSize: 12.5});
  await assert.rejects(floatOps.getRelease(req()), e => e.code === "failed-precondition");
  const {ops: strOps} = fixture({...valid, apkSize: "100"});
  await assert.rejects(strOps.getRelease(req()), e => e.code === "failed-precondition");
});

test("releases: clamps minSupportedVersionCode above latest", async () => {
  const {ops} = fixture({...valid, minSupportedVersionCode: 99});
  const out = await ops.getRelease(req());
  assert.equal(out.minSupportedVersionCode, out.latestVersionCode);
});

test("releases: rejects incoherent fields field-by-field", async () => {
  const {ops} = fixture({
    latestVersionCode: "12",
    latestVersionName: "   ",
    minSupportedVersionCode: -3,
    downloadUrl: "http://insecure.example.com/apk",
    releaseNotes: [],
    sha256: "zz:.!",
  });
  await assert.rejects(ops.getRelease(req()), e => e.code === "failed-precondition" && e.details.reason === "RELEASE_INVALID");
});

test("releases: https download URL forbids credentials, query and fragment", async () => {
  for (const bad of ["https://user@example.com/a", "https://example.com/a?b=c", "https://example.com/a#f", "ftp://example.com/a", "https://"]) {
    const {ops} = fixture({...valid, downloadUrl: bad});
    await assert.rejects(ops.getRelease(req()), e => e.code === "failed-precondition", bad);
  }
  const {ops: okOps} = fixture({...valid, downloadUrl: "https://example.com/operis-0.3.7.apk"});
  assert.notEqual((await okOps.getRelease(req())).downloadUrl, null);
});

test("releases: sha256 normalized to lowercase hex or null", async () => {
  const {ops} = fixture({...valid, sha256: "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789"});
  assert.equal((await ops.getRelease(req())).sha256, "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
  const {ops: nullOps} = fixture({...valid, sha256: "short"});
  await assert.rejects(nullOps.getRelease(req()), e => e.code === "failed-precondition");
});

test("releases: notes are trimmed, non-empty and bounded", async () => {
  const {ops} = fixture({...valid, releaseNotes: ["  a  ", "b", " ", 7, "x".repeat(500)]});
  const out = await ops.getRelease(req());
  assert.deepEqual(out.releaseNotes, ["a", "b"]);
});

test("releases: requires authentication", async () => {
  const {ops} = fixture();
  await assert.rejects(ops.getRelease({data: {}}), e => e.code === "unauthenticated");
});
