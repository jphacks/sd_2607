// Shared import fixture for the server and network tests: root -> CA -> importer chain and one
// authorized BSAF import post. Running this file as a test is a no-op.
import * as cbor from '@ipld/dag-cbor';
import { CID } from 'multiformats/cid';
import { sha256 } from 'multiformats/hashes/sha2';
import { BLOCK, IMPORTER, BSAF, randomSecret, nostrPublicKey, hex, unhex, timestamp, createAttestation, createImporter, createImportPost } from './attest.mjs';

export const SOURCES = [{ botDid: 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi', types: ['earthquake'] }];
export async function importFixture({ now = 1794000000, priority = 3 } = {}) {
  const rootSecret = randomSecret(), caSecret = randomSecret();
  const root = nostrPublicKey(rootSecret), ca = nostrPublicKey(caSecret);
  const importer = createImporter({ endpoint: 'https://pds.example.org' });
  const leafKey = unhex(importer.nostrPubkey);
  const caAttestation = createAttestation(rootSecret, root, { subject: ca, owner: { id: 'ca', name: 'Example CA' }, roles: ['attest', 'import'],
    blocks: [{ $type: BLOCK.delegation, maxDepth: 1 }, { $type: BLOCK.issuerDomain, domain: 'pds.example.org' }] });
  const leaf = createAttestation(caSecret, ca, { subject: leafKey, owner: { id: 'importer', name: 'Example importer' }, roles: ['import'],
    blocks: [{ $type: BLOCK.keyValidity, until: timestamp(now + 86400) }, { $type: BLOCK.organization, orgId: 'example.org:importer', orgName: 'Example importer' },
      { $type: IMPORTER, sources: SOURCES }] });
  const original = { uri: 'at://did:plc:vwi7zhfyamyvrg5v6ycjgzxi/app.bsky.feed.post/3lxyzabcdefgh', createdAt: '2026-11-06T00:00:00.000Z',
    cid: CID.createV1(cbor.code, await sha256.digest(cbor.encode({ text: 'quake' }))).toString() };
  const bsaf = { $type: BSAF, tags: ['bsaf:v1', 'type:earthquake', 'value:5+', 'time:2026-11-06T00:00:00Z', 'target:jp-kanto', 'source:jma'], langs: ['ja'], original };
  const exp = now + 3600;
  const { event } = await createImportPost({ importer, attestations: [caAttestation, leaf], text: '【地震情報】震度5強', ext: [bsaf], createdAt: now - 60, exp: timestamp(exp), priority });
  return { event, now, exp, roots: [{ pubkey: hex(root), roles: ['attest', 'import'], maxDepth: 2 }], selection: SOURCES,
    rootSecret, root, caSecret, ca, leafKey, importer, chain: [caAttestation, leaf] };
}
