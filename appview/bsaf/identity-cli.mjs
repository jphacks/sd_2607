// BSAF importer identity and receiver configuration helpers.
//
// Key attestations, revocations and the publisher's key material are handled by the CA tool
// (ca/, `mosimo-ca`); this script only covers what is specific to the BSAF importer:
//
//   importer-create HTTPS_PDS --out FILE
//       Creates a new did:plc identity for an importer node (PLC genesis operation, PLC rotation
//       key, atproto signing key, Nostr key) and writes it to FILE (new file, mode 0600). Hand the
//       file to `mosimo-ca issue PARENT NAME --importer-identity FILE`, then delete it: it holds
//       the PLC rotation secret in plain text.
//   receiver-export --anchor ANCHOR_JSON --domain DOMAIN... [--jma] [--kikikuru] --out DIR
//       Writes the receivers' public configuration from a root setting (`mosimo-ca trust-export`
//       or the root's anchor.json) and the importer selection: pds-public-information.json and
//       mosimo-public-trust.json. Never reads or writes secrets.
//
// No key material is printed and nothing is written to a fixed directory.
import { mkdir, open, readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { IMPORTER, JMA_SELECTION, KIKIKURU_SELECTION, createImporter, validateImporterBlock } from '../../pds/attest.mjs';

export function presetSelection(flags = []) {
  const known = flags.filter(f => ['--jma', '--kikikuru'].includes(f));
  if (new Set(known).size !== known.length) throw new Error('Duplicate preset flag');
  // No preset flags retains the historical five-type JMA scope, never the combined scope.
  return [...(known.length === 0 || known.includes('--jma') ? JMA_SELECTION : []),
    ...(known.includes('--kikikuru') ? KIKIKURU_SELECTION : [])].sort((a, b) => a.botDid < b.botDid ? -1 : 1)
    .map(e => ({ botDid: e.botDid, types: [...e.types] }));
}
function options(flags, name) {
  return flags.filter(f => f.startsWith(`${name}=`)).map(f => f.slice(name.length + 1));
}
function option(flags, name) {
  const values = options(flags, name);
  if (values.length > 1) throw new Error(`${name} given twice`);
  return values[0];
}
async function writeNew(path, data) {
  const file = await open(path, 'wx', 0o600);
  try { await file.writeFile(data); await file.sync(); } finally { await file.close(); }
}

/** Public receiver exports. Only named public fields are copied, so a caller cannot leak secrets. */
export function receiverConfigurations(root, domains, selection) {
  validateImporterBlock({ $type: IMPORTER, sources: selection });
  const revocationDomains = Array.isArray(domains) ? domains : [domains];
  if (!revocationDomains.length || !revocationDomains.every(d => typeof d === 'string' && /^[a-z0-9.-]+$/.test(d))) throw new Error('Invalid revocation domains');
  const publicRoot = { pubkey: root.pubkey, roles: root.roles, maxDepth: root.maxDepth, enabled: root.enabled ?? true, ...(root.name ? { name: root.name } : {}) };
  if (!/^[0-9a-f]{64,66}$/.test(publicRoot.pubkey) || !Array.isArray(publicRoot.roles)) throw new Error('Invalid root settings');
  return {
    pds: { publicInformation: { roots: [publicRoot], selection, revocationDomains } },
    // The apps' trust asset (PublicIngress.config on Android and iOS) takes exactly {roots, selected}, each root with a name;
    // issuer domains for revocation lists come from the chains themselves.
    android: { roots: [{ pubkey: publicRoot.pubkey, name: publicRoot.name ?? 'Mosimo BSAF trust root', roles: publicRoot.roles,
      maxDepth: publicRoot.maxDepth, enabled: publicRoot.enabled }], selected: selection.flatMap(e => e.types.map(type => ({ botDid: e.botDid, type }))) },
  };
}

export async function run(args) {
  const [command, ...rest] = args;
  const flags = rest.filter(a => a.startsWith('--'));
  const positional = rest.filter(a => !a.startsWith('--'));
  if (command === 'importer-create') {
    const out = option(flags, '--out');
    if (!out || positional.length !== 1) throw new Error('importer-create HTTPS_PDS --out=FILE');
    const importer = createImporter({ endpoint: positional[0] });
    await writeNew(resolve(out), `${JSON.stringify(importer, null, 2)}\n`);
    return { status: 'written', command, did: importer.did, nostrPubkey: importer.nostrPubkey,
      next: 'mosimo-ca issue PARENT NAME --importer-identity FILE, then delete FILE (it holds the PLC rotation secret)' };
  }
  if (command === 'receiver-export') {
    const anchorPath = option(flags, '--anchor'), out = option(flags, '--out'), domains = options(flags, '--domain');
    if (!anchorPath || !out || !domains.length || positional.length) throw new Error('receiver-export --anchor=FILE --domain=DOMAIN... [--jma] [--kikikuru] --out=DIR');
    const anchor = JSON.parse(await readFile(anchorPath, 'utf8'));
    if (Object.keys(anchor).some(key => /secret/i.test(key))) throw new Error('Root settings must not carry secrets');
    const files = receiverConfigurations(anchor, domains, presetSelection(flags));
    await mkdir(resolve(out), { recursive: true, mode: 0o700 });
    await writeNew(join(resolve(out), 'pds-public-information.json'), `${JSON.stringify(files.pds, null, 2)}\n`);
    await writeNew(join(resolve(out), 'mosimo-public-trust.json'), `${JSON.stringify(files.android, null, 2)}\n`);
    return { status: 'written', command, pairs: files.android.selected.length };
  }
  throw new Error('Commands: importer-create HTTPS_PDS --out=FILE | receiver-export --anchor=FILE --domain=DOMAIN... [--jma] [--kikikuru] --out=DIR '
    + '(key attestations and revocations: ca/ mosimo-ca)');
}
if (import.meta.main) run(process.argv.slice(2)).then(result => console.log(JSON.stringify(result))).catch(error => {
  console.error(JSON.stringify({ status: 'identity-cli-failed', reason: error.message }));
  process.exitCode = 1;
});
