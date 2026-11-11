// Offline issuance for the BSAF importer under dev.jisaku.mosimo.attest.
//
//   root (stratum 0, offline) --unlimited--> CA (stratum 1, issuerDomain) --keyValidity--> importer leaf (Nostr key)
//
// Nothing here needs a periodic refresh: the CA attestation has no expiry, the leaf is issued for
// years, and revocation is a signed fact published only when something is revoked. All private
// output stays in the private directory (default ./private/, override with MOSIMO_PRIVATE_DIR),
// which must be ignored by Git and mode 0700. No key material is printed.
import { mkdir, readFile, stat, open, rename } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BLOCK, IMPORTER, REVOCATION, JMA_SELECTION, KIKIKURU_SELECTION, canonical, decodeCanonical, b64, unb64, hex, unhex, timestamp,
  randomSecret, nostrPublicKey, createAttestation, validateAttestation, attestationId, verifyChain, createNotice, validateRevocation,
  createRevocationList, decodeRevocationList, createImporter, validateImporterBlock, blockOf } from '../../pds/attest.mjs';
import { encodeChain, loadCredentials } from './publisher.mts';

const directory = process.env.MOSIMO_PRIVATE_DIR ? resolve(process.env.MOSIMO_PRIVATE_DIR) + '/' : fileURLToPath(new URL('./private/', import.meta.url));
const DEFAULT_ORG = { orgId: 'oriom.dev:mosimo-bsaf-importer', orgName: 'Mosimo BSAF importer (not JMA)' };
const DEFAULT_DAYS = 730;

export function presetSelection(flags = []) {
  const known = flags.filter(f => ['--jma', '--kikikuru'].includes(f));
  if (new Set(known).size !== known.length) throw new Error('Duplicate preset flag');
  // No preset flags retains the historical five-type JMA scope, never the combined scope.
  return [...(known.length === 0 || known.includes('--jma') ? JMA_SELECTION : []),
    ...(known.includes('--kikikuru') ? KIKIKURU_SELECTION : [])].sort((a, b) => a.botDid < b.botDid ? -1 : 1)
    .map(e => ({ botDid: e.botDid, types: [...e.types] }));
}
function option(flags, name, fallback) {
  const entry = flags.find(f => f.startsWith(`${name}=`));
  return entry ? entry.slice(name.length + 1) : fallback;
}
const load = async name => JSON.parse(await readFile(join(directory, name), 'utf8'));
const loadOptional = name => load(name).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
async function writeDurable(path, data, flag = 'wx') {
  const file = await open(path, flag, 0o600);
  try { await file.writeFile(data); await file.sync(); } finally { await file.close(); }
}
async function syncDirectory() {
  if (process.platform === 'win32') return;
  const handle = await open(directory, 'r');
  try { await handle.sync(); } finally { await handle.close(); }
}
async function save(name, value, { replace = false } = {}) {
  const data = typeof value === 'string' || value instanceof Uint8Array ? value : `${JSON.stringify(value, null, 2)}\n`;
  if (!replace) { await writeDurable(join(directory, name), data); }
  else {
    await writeDurable(join(directory, `${name}.tmp`), data, 'w');
    await rename(join(directory, `${name}.tmp`), join(directory, name));
  }
  await syncDirectory();
}
const encodeSigned = value => b64(canonical(value, { maxBytes: 65536 }));
const decodeSigned = value => decodeCanonical(unb64(value), { maxBytes: 65536 });
const summary = attestation => ({ id: attestationId(attestation), issuer: hex(attestation.body.issuer), subject: hex(attestation.body.subject),
  roles: attestation.body.roles, blocks: attestation.body.blocks.map(block => block.$type) });

/** Public receiver exports. Only named public fields are copied, so a caller cannot leak secrets. */
export function receiverConfigurations(root, domain, selection) {
  validateImporterBlock({ $type: IMPORTER, sources: selection });
  const publicRoot = { pubkey: root.pubkey, roles: root.roles, maxDepth: root.maxDepth, enabled: root.enabled ?? true, ...(root.name ? { name: root.name } : {}) };
  if (!/^[0-9a-f]{64,66}$/.test(publicRoot.pubkey) || !Array.isArray(publicRoot.roles)) throw new Error('Invalid root settings');
  return {
    pds: { publicInformation: { roots: [publicRoot], selection, revocationDomains: [domain] } },
    android: { roots: [publicRoot], selected: selection.flatMap(e => e.types.map(type => ({ botDid: e.botDid, type }))), revocationDomains: [domain] },
  };
}

export async function run(args) {
  const [command, ...rest] = args;
  const flags = rest.filter(a => a.startsWith('--'));
  const positional = rest.filter(a => !a.startsWith('--'));
  const [argument] = positional;
  const now = Math.floor(Date.now() / 1000);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  if (process.platform !== 'win32' && ((await stat(directory)).mode & 0o077)) throw new Error('Private directory must be mode 0700');
  if (command === 'root-create') {
    if (positional.length) throw new Error('root-create takes only --name=');
    const secret = randomSecret(), pubkey = nostrPublicKey(secret);
    const root = { pubkey: hex(pubkey), roles: ['attest', 'import'], maxDepth: 2, enabled: true, name: option(flags, '--name', 'Mosimo BSAF trust root') };
    await save('root-secret.json', { secretKey: hex(secret), pubkey: hex(pubkey) });
    await save('roots.json', [root]);
    return { status: 'written', command, rootPubkey: root.pubkey };
  }
  if (command === 'ca-create') {
    if (!argument || !/^[a-z0-9.-]+$/.test(argument)) throw new Error('ca-create needs the lowercase issuer DNS domain');
    const { secretKey } = await load('root-secret.json');
    const [root] = await load('roots.json');
    const sources = presetSelection(flags);
    const caSecret = randomSecret(), ca = nostrPublicKey(caSecret);
    const attestation = createAttestation(unhex(secretKey), unhex(root.pubkey), {
      subject: ca, owner: { id: option(flags, '--owner-id', 'oriom-mosimo-ca'), name: option(flags, '--owner-name', 'Oriom Mosimo CA') },
      roles: ['attest', 'import'],
      blocks: [{ $type: BLOCK.delegation, maxDepth: 1 }, { $type: BLOCK.issuerDomain, domain: argument }, { $type: IMPORTER, sources }],
    });
    // Pre-issued notice: the CA can revoke itself later without the offline root (revocation §3).
    const notice = createNotice(unhex(secretKey), unhex(root.pubkey), { subject: ca, issuedAt: timestamp(now) });
    await save('ca-secret.json', { secretKey: hex(caSecret), pubkey: hex(ca), domain: argument });
    await save('ca-attestation.json', { attestation: encodeSigned(attestation), domain: argument, ...summary(attestation) });
    await save('ca-revocation-notice.json', { notice: encodeSigned(notice), subject: hex(ca), form: 'pre-issued' });
    return { status: 'written', command, caPubkey: hex(ca), attestationId: attestationId(attestation) };
  }
  if (command === 'importer-create') {
    const importer = createImporter({ endpoint: argument ?? 'https://pds.invalid' });
    await save('importer-secret.json', importer);
    await save('importer-request.json', { did: importer.did, nostrPubkey: importer.nostrPubkey, atprotoPubkey: importer.atprotoPubkey, genesis: importer.genesis });
    return { status: 'written', command, did: importer.did, nostrPubkey: importer.nostrPubkey };
  }
  if (command === 'issue') {
    const { secretKey, pubkey } = await load('ca-secret.json');
    const ca = await load('ca-attestation.json');
    const request = await load('importer-request.json');
    const sources = presetSelection(flags);
    const days = Number(option(flags, '--days', String(DEFAULT_DAYS)));
    if (!Number.isInteger(days) || days < 1 || days > 3660) throw new Error('--days must be 1..3660');
    const subject = unhex(request.nostrPubkey);
    const attestation = createAttestation(unhex(secretKey), unhex(pubkey), {
      subject, owner: { id: option(flags, '--owner-id', request.did), name: option(flags, '--owner-name', 'Mosimo BSAF importer') },
      roles: ['import'],
      blocks: [
        { $type: BLOCK.keyValidity, from: timestamp(now - 300), until: timestamp(now + days * 86400) },
        { $type: BLOCK.organization, orgId: option(flags, '--org-id', DEFAULT_ORG.orgId), orgName: option(flags, '--org-name', DEFAULT_ORG.orgName) },
        { $type: IMPORTER, sources },
        { $type: BLOCK.trustLevel, level: 2, method: 'manual' },
      ],
    });
    const roots = await load('roots.json');
    const verified = verifyChain([decodeSigned(ca.attestation), attestation], { roots, at: now, now });
    if (verified.status !== 'verified') throw new Error(`Issued chain does not verify: ${verified.status} ${verified.reason}`);
    const notice = createNotice(unhex(secretKey), unhex(pubkey), { subject, issuedAt: timestamp(now) });
    await save('leaf-attestation.json', { attestation: encodeSigned(attestation), validUntil: timestamp(now + days * 86400), ...summary(attestation) }, { replace: true });
    await save('leaf-revocation-notice.json', { notice: encodeSigned(notice), subject: hex(subject), form: 'pre-issued' }, { replace: true });
    return { status: 'written', command, attestationId: attestationId(attestation), validUntil: timestamp(now + days * 86400) };
  }
  if (command === 'assemble') {
    const importer = await load('importer-secret.json');
    const ca = decodeSigned((await load('ca-attestation.json')).attestation);
    const leaf = decodeSigned((await load('leaf-attestation.json')).attestation);
    const roots = await load('roots.json');
    const verified = verifyChain([ca, leaf], { roots, at: now, now });
    if (verified.status !== 'verified' || verified.nowStatus !== 'valid') throw new Error(`Chain does not verify: ${verified.status} ${verified.reason}`);
    if (hex(leaf.body.subject) !== importer.nostrPubkey) throw new Error('Leaf subject is not this importer');
    // Online worker gets neither the PLC rotation secret, the CA secret nor the root secret.
    const credentials = { importer: { did: importer.did, atprotoSecret: importer.atprotoSecret, nostrSecret: importer.nostrSecret }, chain: encodeChain([ca, leaf]) };
    loadCredentials(credentials);
    await save('credentials.json', credentials, { replace: true });
    return { status: 'written', command, did: importer.did };
  }
  if (command === 'revoke') {
    if (!argument || !/^[0-9a-f]{64,66}$/.test(argument)) throw new Error('revoke needs the subject public key (hex)');
    const code = option(flags, '--reason', 'unspecified');
    const ca = await load('ca-secret.json');
    const root = await loadOptional('root-secret.json');
    // The CA revokes its leaves; only the root can revoke the CA.
    const issuer = argument === ca.pubkey ? root : ca;
    if (!issuer) throw new Error('Revoking the CA requires root-secret.json');
    const notice = createNotice(unhex(issuer.secretKey), unhex(issuer.pubkey), { subject: unhex(argument), issuedAt: timestamp(now), effectiveAt: timestamp(now), reason: { code } });
    const revocation = { $type: REVOCATION, notice };
    const facts = validateRevocation(revocation);
    const list = (await loadOptional('revocations.json')) ?? [];
    list.push({ revocation: encodeSigned(revocation), ...facts, effectiveAt: timestamp(now) });
    await save('revocations.json', list, { replace: true });
    return { status: 'written', command, revocationId: facts.id, next: 'run revocation-list and publish revocations.cbor' };
  }
  if (command === 'revocation-list') {
    const ca = await load('ca-secret.json');
    const entries = (await loadOptional('revocations.json')) ?? [];
    const list = createRevocationList(unhex(ca.secretKey), unhex(ca.pubkey), { generatedAt: timestamp(now),
      revocations: entries.map(entry => decodeSigned(entry.revocation)) });
    const bytes = canonical(list, { maxBytes: 1048576 });
    const decoded = decodeRevocationList(bytes);
    if (!decoded.listSignatureValid || decoded.rejected.length) throw new Error('Generated list does not verify');
    await save('revocations.cbor', bytes, { replace: true });
    return { status: 'written', command, notices: entries.length, publishAt: `https://${ca.domain}/.well-known/mosimo/attest/revocations` };
  }
  if (command === 'receiver-export') {
    const [root] = await load('roots.json');
    const ca = await load('ca-attestation.json');
    const selection = presetSelection(flags);
    const caSources = blockOf(decodeSigned(ca.attestation), IMPORTER)?.sources ?? [];
    for (const entry of selection) for (const type of entry.types) {
      if (!caSources.some(s => s.botDid === entry.botDid && s.types.includes(type))) throw new Error(`CA scope does not permit ${entry.botDid} ${type}`);
    }
    const files = receiverConfigurations(root, ca.domain, selection);
    await save('pds-public-information.json', files.pds, { replace: true });
    await save('mosimo-public-trust.json', files.android, { replace: true });
    return { status: 'written', command, pairs: files.android.selected.length };
  }
  if (command === 'verify') {
    const credentials = loadCredentials(await load('credentials.json'));
    const roots = await load('roots.json');
    const verified = verifyChain(credentials.attestations, { roots, at: now, now });
    const leaf = credentials.attestations[credentials.attestations.length - 1];
    return { status: verified.status, nowStatus: verified.nowStatus, reason: verified.reason, leaf: summary(leaf),
      validUntil: blockOf(leaf, BLOCK.keyValidity)?.until ?? null };
  }
  throw new Error('Commands: root-create [--name=] | ca-create DOMAIN [--jma] [--kikikuru] | importer-create HTTPS_PDS | issue [--jma] [--kikikuru] [--days=N] [--org-id=] [--org-name=] | assemble | revoke PUBKEY_HEX [--reason=code] | revocation-list | receiver-export [--jma] [--kikikuru] | verify');
}
if (import.meta.main) run(process.argv.slice(2)).then(result => console.log(JSON.stringify(result))).catch(error => {
  console.error(JSON.stringify({ status: 'identity-cli-failed', reason: error.message }));
  process.exitCode = 1;
});
