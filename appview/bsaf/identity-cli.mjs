import { mkdir, readFile, writeFile, rename, stat, open, unlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRoot, createImporter, issueCertificate, createSnapshot, canonical,
  timestamp, keyPublic, rootId, JMA_SELECTION, verifyImporterGenesis, verifyBinding,
  verifyChain, verifySnapshot, certId, validateScope } from '../../pds/public-information.mjs';

// All private outputs are confined to this ignored directory; no key material is printed.
const directory = fileURLToPath(new URL('./private/', import.meta.url));
const orgId = 'mosimo-bsaf-importer';
const scope = { orgIds: [orgId], bsaf: JMA_SELECTION };
const load = async name => JSON.parse(await readFile(join(directory, name), 'utf8'));
async function writeDurable(path, data, flag = 'wx') {
  const file = await open(path, flag, 0o600);
  try { await file.writeFile(data); await file.sync(); } finally { await file.close(); }
}
async function syncDirectory() {
  // Windows has no POSIX directory fsync; files themselves are flushed on both platforms.
  if (process.platform === 'win32') return;
  const handle = await open(directory, 'r');
  try { await handle.sync(); } finally { await handle.close(); }
}
async function save(name, value) {
  await writeDurable(join(directory, name), `${JSON.stringify(value, null, 2)}\n`);
  await syncDirectory();
}
// Public export only: explicitly select known fields so a caller cannot export private-key fields.
export function receiverConfigurations(root, snapshotSequence) {
  validateScope(root.scope);
  const id = rootId(root.rootPubkey);
  const url = new URL(root.snapshotUrl);
  if (url.protocol !== 'https:' || url.username || url.password || url.hash
    || typeof root.enabled !== 'boolean' || !Number.isSafeInteger(root.minSequence) || root.minSequence < 1
    || !Number.isSafeInteger(snapshotSequence) || snapshotSequence < root.minSequence) throw new Error('Invalid public export root/sequence');
  const publicRoot = { rootPubkey: root.rootPubkey, enabled: root.enabled, scope: root.scope,
    snapshotUrl: root.snapshotUrl, minSequence: snapshotSequence };
  const selected = JMA_SELECTION.flatMap(e => e.types.map(type => ({ botDid: e.botDid, type })));
  if (!root.scope.orgIds.includes(orgId) || !JMA_SELECTION.every(e => e.types.every(type =>
    root.scope.bsaf.some(s => s.botDid === e.botDid && s.types.includes(type))))) throw new Error('Root does not permit fixed importer scope');
  const hex = value => Buffer.from(canonical(value)).toString('hex');
  return {
    android: { roots: [{ rootId: id, ...publicRoot }], selected },
    pds: { publicInformation: { roots: [publicRoot], selection: JMA_SELECTION } },
    esp32: `#pragma once\n// PUBLIC, operator-provisioned export. Not installed by this CLI.\n// Root fingerprint (SHA-256 of compressed public key): ${id}\n#define MESH_INGRESS_ROOT_PUBKEY ${JSON.stringify(publicRoot.rootPubkey)}\n#define MESH_INGRESS_SCOPE_CBOR_HEX "${hex(publicRoot.scope)}"\n#define MESH_INGRESS_SELECTED_CBOR_HEX "${hex(JMA_SELECTION)}"\n#define MESH_INGRESS_SNAPSHOT_URL ${JSON.stringify(publicRoot.snapshotUrl)}\n#define MESH_INGRESS_MIN_SEQUENCE ${publicRoot.minSequence}ULL\n// Supply the verified public TLS CA before snapshot fetching; empty is fail-closed.\n#define MESH_INGRESS_SNAPSHOT_CA R"PEM()PEM"\n// No MESH_INGRESS_ENABLED or MESH_INGRESS_TRUST_CLOCK override.\n`,
  };
}
export async function run(args) {
  const [command, argument] = args;
  await mkdir(directory, { recursive: true, mode: 0o700 });
  if (process.platform !== 'win32' && ((await stat(directory)).mode & 0o077)) throw new Error('Private directory must be mode 0700');
  const now = Math.floor(Date.now() / 1000);
  if (command === 'root-create') {
    if (!argument || new URL(argument).protocol !== 'https:') throw new Error('Absolute HTTPS snapshot URL required');
    const result = createRoot({ scope, snapshotUrl: argument });
    await save('root-secret.json', result);
    await save('roots.json', [result.root]);
  } else if (command === 'importer-create') {
    const importer = createImporter({ endpoint: argument ?? 'https://pds.invalid' });
    await save('importer-secret.json', importer);
    // Offline public request is the only file that needs to cross to the root environment.
    await save('importer-request.json', { genesis: importer.genesis, binding: importer.binding, plcPubkey: keyPublic(importer.plcSecret) });
  } else if (command === 'issue-genesis') {
    // Explicit experimental genesis-only issuance, not PLC Directory authentication.
    if (argument !== 'I-reviewed-organization-and-genesis') throw new Error('Explicit out-of-band organization/genesis review required');
    const { secretKey, root } = await load('root-secret.json');
    const request = await load('importer-request.json');
    const verified = verifyImporterGenesis(request);
    const certificate = issueCertificate(secretKey, { subjectPubkey: verified.atprotoPubkey, subjectDid: verified.did,
      orgId, orgName: 'Mosimo BSAF importer (not JMA)', scope, notBefore: timestamp(now), notAfter: timestamp(now + 30 * 86400) });
    verifyChain([certificate], { roots: [root], nowSeconds: now, botDid: JMA_SELECTION[0].botDid, type: 'earthquake', selection: JMA_SELECTION });
    await save('certificate.json', certificate);
    await save('issuance.json', { certId: certId(certificate), subjectDid: verified.did,
      identityStatus: verified.status, organizationReview: 'operator-confirmed-out-of-band', issuedAt: timestamp(now) });
  } else if (command === 'assemble') {
    const importer = await load('importer-secret.json');
    const certificate = await load('certificate.json');
    const roots = await load('roots.json');
    const b = certificate.body;
    if (b.subjectDid !== importer.did || b.subjectPubkey !== keyPublic(importer.atprotoSecret)) throw new Error('Leaf mismatch');
    verifyBinding(importer.binding, { did: importer.did, atprotoPubkey: b.subjectPubkey, nostrPubkey: importer.binding.declaration.nostrPubkey });
    verifyChain([certificate], { roots, nowSeconds: now, botDid: JMA_SELECTION[0].botDid, type: 'earthquake', selection: JMA_SELECTION });
    // Online worker gets neither PLC rotation secret nor root secret.
    await save('credentials.json', { importer: { did: importer.did, atprotoSecret: importer.atprotoSecret,
      nostrSecret: importer.nostrSecret, binding: importer.binding }, chain: [certificate] });
  } else if (command === 'snapshot') {
    const { secretKey, root } = await load('root-secret.json');
    let previous = null;
    try { previous = await load('snapshot-state.json'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    let revokedCertIds = previous?.body.revokedCertIds ?? [];
    let revokedPubkeys = previous?.body.revokedPubkeys ?? [];
    if (argument) {
      // A complete explicit CRL file; verifySnapshot forbids forgetting old revocations.
      const crl = JSON.parse(await readFile(resolve(argument), 'utf8'));
      revokedCertIds = crl.revokedCertIds; revokedPubkeys = crl.revokedPubkeys;
    }
    const sequence = Math.max(root.minSequence, (previous?.body.sequence ?? 0) + 1);
    const snapshot = createSnapshot(secretKey, { sequence, issuedAt: timestamp(now), validUntil: timestamp(now + 3600), revokedCertIds, revokedPubkeys });
    await verifySnapshot(snapshot, { root, nowSeconds: now, previous });
    // Exclusive lock prevents two scheduled CLI jobs from reusing sequence.
    const lock = join(directory, 'snapshot.lock');
    await writeFile(lock, '', { flag: 'wx', mode: 0o600 });
    try {
      const current = await load('snapshot-state.json').catch(error => { if (error.code === 'ENOENT') return null; throw error; });
      if ((current?.body.sequence ?? 0) !== (previous?.body.sequence ?? 0)) throw new Error('Concurrent snapshot update; retry');
      await writeDurable(join(directory, 'snapshot-state.tmp'), JSON.stringify(snapshot), 'w');
      await rename(join(directory, 'snapshot-state.tmp'), join(directory, 'snapshot-state.json'));
      await syncDirectory();
      await writeDurable(join(directory, 'snapshot.cbor.tmp'), canonical(snapshot), 'w');
      await rename(join(directory, 'snapshot.cbor.tmp'), join(directory, 'snapshot.cbor'));
      await syncDirectory();
    } finally { await unlink(lock); }
  } else if (command === 'receiver-export') {
    const roots = await load('roots.json');
    if (!Array.isArray(roots) || roots.length !== 1) throw new Error('One provisioned root required');
    const snapshot = await load('snapshot-state.json');
    await verifySnapshot(snapshot, { root: roots[0], nowSeconds: now });
    const files = receiverConfigurations(roots[0], snapshot.body.sequence);
    await save('mosimo-public-trust.json', files.android);
    await save('pds-public-information.json', files.pds);
    await writeDurable(join(directory, 'IngressTrust.generated.h'), files.esp32);
    await syncDirectory();
  } else throw new Error('Use root-create HTTPS_URL | importer-create HTTPS_PDS | issue-genesis I-reviewed-organization-and-genesis | assemble | snapshot [COMPLETE_CRL_JSON] | receiver-export');
  return { status: 'written-to-private-directory', command };
}
if (import.meta.main) run(process.argv.slice(2)).then(result => console.error(JSON.stringify(result))).catch(() => {
  console.error('{"status":"identity-cli-failed","hint":"Check command, private files, review acknowledgement, and permissions; existing keys are never overwritten"}');
  process.exitCode = 1;
});
