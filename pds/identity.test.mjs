import assert from 'node:assert/strict';
import test from 'node:test';
import { Secp256k1Keypair } from '@atproto/crypto';
import { createIdentityResolver } from './identity.mjs';

const did = 'did:plc:abcdefghijklmnopqrstuvwx';
const key = await Secp256k1Keypair.create();
function fixture({ data = {}, doc = {}, config = {} } = {}) {
  const calls = [];
  const didDoc = { id: did, service: [{ serviceEndpoint: 'http://169.254.169.254/latest/meta-data/' }], ...doc };
  const plcData = { did, verificationMethods: { atproto: key.did() },
    services: { atproto_pds: { type: 'AtprotoPersonalDataServer', endpoint: 'https://PDS.example:443/' } },
    alsoKnownAs: ['https://ignored.example', 'at://alice.example'], ...data };
  const resolve = createIdentityResolver({ plcUrl: 'https://PLC.example:443/ignored?ignored=1', ...config }, async url => {
    calls.push(url);
    if (url === `https://plc.example/${did}/data` || url === `https://plc.directory/${did}/data`) return plcData;
    if (url === `https://plc.example/${did}` || url === `https://plc.directory/${did}`) return didDoc;
    throw new Error(`Unexpected outbound request: ${url}`);
  });
  return { resolve, calls, didDoc, plcData };
}

test('resolves the signing key, normalized canonical endpoint, document, and first valid handle', async () => {
  const { resolve, calls, didDoc } = fixture();
  assert.deepEqual(await resolve(did), {
    did, signingKey: key.did(), endpoint: 'https://pds.example', didDoc, handle: 'alice.example',
  });
  assert.deepEqual(calls.sort(), [`https://plc.example/${did}`, `https://plc.example/${did}/data`]);
});

test('uses the default PLC directory and tolerates no handle', async () => {
  const { resolve, calls } = fixture({ config: { plcUrl: undefined }, data: { alsoKnownAs: [] } });
  assert.equal((await resolve(did)).handle, undefined);
  assert.ok(calls.every(url => url.startsWith('https://plc.directory/')));
});

test('rejects unsupported or URL-shaped identifiers before making any request', async () => {
  const { resolve, calls } = fixture();
  for (const value of ['', 'alice.example', 'did:web:example.com', did.toUpperCase(), `${did}/../data`,
    `${did}?url=http://127.0.0.1`, 'http://169.254.169.254', 'did:plc:aaaaaaaaaaaaaaaaaaaaaaa1',
    'did:plc:aaaaaaaaaaaaaaaaaaaaaaa', null, undefined]) {
    await assert.rejects(resolve(value), /Only did:plc/);
  }
  assert.deepEqual(calls, []);
});

test('rejects mismatched identity, absent signing key, and wrong PDS service type', async () => {
  for (const options of [
    { data: { did: 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa' } }, { doc: { id: 'did:web:other.example' } },
    { data: { verificationMethods: {} } }, { data: { verificationMethods: { atproto: 'not-a-did-key' } } },
        { data: { verificationMethods: undefined, signingKeys: { atproto: key.did() } } },
    { data: { services: {} } },
    { data: { services: { atproto_pds: { type: 'OtherService', endpoint: 'https://pds.example' } } } },
  ]) await assert.rejects(fixture(options).resolve(did), /Invalid PLC identity/);
});

test('rejects non-origin, credentialed, and non-HTTPS canonical service endpoints', async () => {
  for (const endpoint of ['invalid', '//pds.example', 'http://pds.example', 'file:///etc/passwd',
    'https://user:pass@pds.example', 'https://pds.example/x', 'https://pds.example/?x=1',
    'https://pds.example/#fragment', 'https://pds.example@127.0.0.1/path']) {
    const { resolve, calls } = fixture({ data: {
      services: { atproto_pds: { type: 'AtprotoPersonalDataServer', endpoint } },
    } });
    await assert.rejects(resolve(did));
    assert.equal(calls.length, 2);
    assert.ok(calls.every(url => url.startsWith(`https://plc.example/${did}`)));
  }
});

test('DID document URLs and private service origins never become fetch targets', async () => {
  // Service endpoints are identity metadata, not URLs this resolver dereferences.
  for (const endpoint of ['https://127.0.0.1', 'https://[::1]', 'https://169.254.169.254',
    'https://localhost', 'https://private.example:8443']) {
    const { resolve, calls } = fixture({ data: {
      services: { atproto_pds: { type: 'AtprotoPersonalDataServer', endpoint } },
      alsoKnownAs: ['at://bad.example/path', 'https://attacker.example', 'at://valid.example'],
    } });
    assert.equal((await resolve(did)).endpoint, new URL(endpoint).origin);
    assert.deepEqual(calls.sort(), [`https://plc.example/${did}`, `https://plc.example/${did}/data`]);
  }
});

test('propagates directory failures without fallback requests to DID-controlled URLs', async () => {
  const calls = [];
  const error = new Error('directory unavailable');
  const resolve = createIdentityResolver({ plcUrl: 'https://plc.example' }, async url => {
    calls.push(url);
    throw error;
  });
  await assert.rejects(resolve(did), value => value === error);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(url => url.startsWith('https://plc.example/')));
});
