import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const artifactPath = process.argv[2];
assert.ok(artifactPath, "compiled Kotoba artifact path is required");

const generated = await import(pathToFileURL(artifactPath).href);
assert.match(generated.kotobaArtifact.moduleGraphDigest, /^[0-9a-f]{64}$/);
assert.match(generated.kotobaArtifact.packageLockDigest, /^[0-9a-f]{64}$/);
assert.match(generated.kotobaArtifact.trustPolicyDigest, /^[0-9a-f]{64}$/);
assert.match(generated.kotobaArtifact.packageReceiptDigest, /^[0-9a-f]{64}$/);
assert.deepEqual(Object.keys(generated.kotobaArtifact.moduleSourceDigests), [
  "kotoba.etzhayyim.tashikame.phase-defaults",
  "kotoba.etzhayyim.tashikame.publish-gate",
]);

const api = generated.instantiateKotoba({});
assert.equal(api["publish-allowed"](0n), 0n);
assert.equal(api["publish-allowed"](1n), 1n);
assert.equal(api["publish-allowed"](2n), 1n);
assert.equal(api["publish-allowed"](-1n), 1n);

console.log("tashikame Kotoba closed-project pilot passed");
