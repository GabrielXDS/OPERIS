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

test("releases: missing doc returns shipped defaults (8 fields)", async () => {
  const {ops} = fixture();
  const out = await ops.getRelease(req());
  assert.deepEqual(Object.keys(out).sort(), SHIPPED_KEYS);
  assert.equal(out.latestVersionCode, 15);
  assert.equal(out.latestVersionName, "4.0");
  assert.equal(out.minSupportedVersionCode, 9);
  assert.equal(out.status, "DRAFT");
  assert.equal(out.downloadUrl, null);
  assert.equal(out.sha256, null);
  assert.equal(out.apkSize, null);
  assert.ok(out.releaseNotes.length > 0);
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
  assert.equal((await ops.getRelease(req())).apkSize, null);
  const {ops: floatOps} = fixture({...valid, apkSize: 12.5});
  assert.equal((await floatOps.getRelease(req())).apkSize, null);
  const {ops: strOps} = fixture({...valid, apkSize: "100"});
  assert.equal((await strOps.getRelease(req())).apkSize, null);
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
  const out = await ops.getRelease(req());
  assert.equal(out.latestVersionCode, 15);
  assert.equal(out.latestVersionName, "4.0");
  assert.equal(out.minSupportedVersionCode, 15);
  assert.equal(out.downloadUrl, null);
  assert.equal(out.sha256, null);
  assert.ok(out.releaseNotes.length > 0);
});

test("releases: https download URL forbids credentials, query and fragment", async () => {
  for (const bad of ["https://user@example.com/a", "https://example.com/a?b=c", "https://example.com/a#f", "ftp://example.com/a", "https://"]) {
    const {ops} = fixture({...valid, downloadUrl: bad});
    assert.equal((await ops.getRelease(req())).downloadUrl, null, bad);
  }
  const {ops: okOps} = fixture({...valid, downloadUrl: "https://example.com/operis-0.3.7.apk"});
  assert.notEqual((await okOps.getRelease(req())).downloadUrl, null);
});

test("releases: sha256 normalized to lowercase hex or null", async () => {
  const {ops} = fixture({...valid, sha256: "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789"});
  assert.equal((await ops.getRelease(req())).sha256, "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
  const {ops: nullOps} = fixture({...valid, sha256: "short"});
  assert.equal((await nullOps.getRelease(req())).sha256, null);
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