import assert from 'node:assert/strict';
import test from 'node:test';
import { validateConfig } from './config.mjs';
import { createRoot, JMA_SELECTION } from './public-information.mjs';

test('public priority defaults grant no authority; provisioned roots and selection are bounded and explicit', () => {
  assert.deepEqual(validateConfig(input()).publicInformation, { roots: [], selection: [] });
  const { root } = createRoot({ scope: { orgIds: ['test'], bsaf: JMA_SELECTION }, snapshotUrl: 'https://trust.invalid/snapshot.cbor' });
  assert.doesNotThrow(() => validateConfig(input({ publicInformation: { roots: [root], selection: JMA_SELECTION } })));
  for (const patch of [{ enabled: undefined }, { minSequence: 0 }, { snapshotUrl: 'http://trust.invalid/snapshot' },
    { snapshotUrl: 'https://user:secret@trust.invalid/snapshot' }, { snapshotUrl: 'https://trust.invalid/#fragment' }, { scope: {} }]) {
    assert.throws(() => validateConfig(input({ publicInformation: { roots: [{ ...root, ...patch }], selection: [] } })));
  }
  for (const roots of [[root, root], Array(65).fill(root)]) {
    assert.throws(() => validateConfig(input({ publicInformation: { roots, selection: [] } })));
  }
});

const input = extra => ({ groups: [{ id: 'alpha', endpoint: 'https://PDS.example:443/' }], ...extra });

test('normalizes origins, supplies limits, and does not mutate input', () => {
  const original = input({ relays: ['wss://relay.example/path?subscription=1'],
    peers: [{ url: 'https://PEER.example:443/', tokenEnv: 'OTHER_TOKEN' }] });
  const before = structuredClone(original);
  const config = validateConfig(original, { PEER_TOKEN: 'inbound', OTHER_TOKEN: 'outbound' });
  assert.deepEqual(original, before);
  assert.deepEqual(config.groups, [{ id: 'alpha', endpoint: 'https://pds.example' }]);
  assert.deepEqual(config.peers, [{ url: 'https://peer.example', tokenEnv: 'OTHER_TOKEN' }]);
  assert.deepEqual(config.relays, original.relays);
  assert.equal(config.plcUrl, 'https://plc.directory');
  assert.deepEqual([config.maxEventBytes, config.maxSubscriptions, config.maxResults,
    config.maxBufferedBytes, config.maxEvents], [131072, 20, 500, 4194304, 1000000]);
});

test('requires nonempty groups with unique IDs and normalized endpoints', () => {
  for (const groups of [undefined, null, [], {}, [{ id: '', endpoint: 'https://one.example' }],
    [{ id: 1, endpoint: 'https://one.example' }],
    [{ id: 'a', endpoint: 'https://one.example' }, { id: 'a', endpoint: 'https://two.example' }],
    [{ id: 'a', endpoint: 'https://ONE.example:443/' }, { id: 'b', endpoint: 'https://one.example' }]]) {
    assert.throws(() => validateConfig(input({ groups }), {}));
  }
});

for (const field of ['group', 'plcUrl', 'peer']) {
  test(`${field} requires a credential-free HTTPS origin`, () => {
    for (const url of ['not a URL', '//example.com', 'http://example.com', 'file:///tmp/data',
      'wss://example.com', 'https://user:secret@example.com', 'https://example.com/path',
      'https://example.com/?query=1', 'https://example.com/#fragment']) {
      const extra = field === 'group' ? { groups: [{ id: 'a', endpoint: url }] }
        : field === 'peer' ? { peers: [{ url }] } : { plcUrl: url };
      assert.throws(() => validateConfig(input(extra), { PEER_TOKEN: 'secret' }), url);
    }
  });
}

test('relays require WSS without credentials or fragments, but may have paths and queries', () => {
  for (const url of ['ws://relay.example', 'https://relay.example', 'wss://user:pass@relay.example',
    'wss://relay.example/#fragment', 'invalid']) {
    assert.throws(() => validateConfig(input({ relays: [url] }), {}));
  }
  for (const field of ['relays', 'peers']) {
    for (const value of [null, {}, 'https://example.com']) {
      assert.throws(() => validateConfig(input({ [field]: value }), {}));
    }
  }
});

test('peer authentication requires inbound and each outbound environment token', () => {
  const peers = [{ url: 'https://peer.example', tokenEnv: 'OUTBOUND' }];
  for (const env of [{}, { PEER_TOKEN: ' ' }, { OUTBOUND: 'secret' },
    { PEER_TOKEN: 'secret' }, { PEER_TOKEN: 'secret', OUTBOUND: '\t' }]) {
    assert.throws(() => validateConfig(input({ peers }), env), /token|PEER_TOKEN/i);
  }
  assert.equal(validateConfig(input({ peers: [{ url: 'https://peer.example' }] }),
    { PEER_TOKEN: 'secret' }).peers[0].tokenEnv, 'PEER_TOKEN');
  assert.doesNotThrow(() => validateConfig(input(), {}));
});

for (const key of ['maxEventBytes', 'maxSubscriptions', 'maxResults', 'maxBufferedBytes', 'maxEvents']) {
  test(`${key} must be a positive safe integer`, () => {
    for (const value of [0, -1, 1.5, '10', null, undefined, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
      assert.throws(() => validateConfig(input({ [key]: value }), {}), new RegExp(key));
    }
    for (const value of [1, Number.MAX_SAFE_INTEGER]) {
      assert.equal(validateConfig(input({ [key]: value }), {})[key], value);
    }
  });
}
