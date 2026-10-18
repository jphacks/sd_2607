// Read-only: GET requests only. No PLC registration, posting, crawl request or DB access.
// Node 24: node appview/happyview/verify-production.mjs [DID [rkey ...]]
import assert from 'node:assert/strict';
import { runWeb } from '../../apps/web/web-harness.mjs';

const did = process.argv[2] ?? 'did:plc:22enadmulnb2e7urhi4hcvny';
assert.match(did, /^did:[a-z]+:[A-Za-z0-9._:%-]+$/);
const rkeys = process.argv.slice(3);
if (!process.argv[2]) rkeys.push('3my4mwtyipcmx', '3my4mwu34osmx');
const pds = process.env.PDS_URL ?? 'https://pds.oriom.dev';
const relay = process.env.RELAY_URL ?? 'https://bsky.network';
const appview = process.env.APPVIEW_URL ?? 'https://appview.oriom.dev';
const collection = 'dev.oriom.mosimo.post';
const origin = 'http://localhost:8080';
async function get(base, method, params) {
  const url = new URL(`/xrpc/${method}`, base);
  url.search = new URLSearchParams(params).toString();
  const response = await fetch(url, { signal: AbortSignal.timeout(15000), headers: { Origin: origin } });
  assert.equal(response.status, 200, `${url.origin}${url.pathname} must return 200`);
  if (base === appview) assert.ok(['*', origin].includes(response.headers.get('access-control-allow-origin')), 'AppView must allow browser CORS');
  return response.json();
}

try {
  const head = await get(pds, 'com.atproto.sync.getLatestCommit', { did });
  const relayHead = await get(relay, 'com.atproto.sync.getLatestCommit', { did });
  assert.deepEqual(relayHead, head, 'Relay head must match PDS (retry after ingestion delay)');
  const indexed = new Map();
  let cursor;
  for (let page = 0; page < 100; page++) {
    const data = await get(appview, 'dev.oriom.mosimo.getFeed', { did, limit: '100', ...(cursor ? { cursor } : {}) });
    assert.ok(Array.isArray(data.records));
    for (const record of data.records) {
      if (record.$type === collection) indexed.set(record.uri, { record, provenance: data.provenance?.[record.uri] });
    }
    if (!data.cursor) break;
    assert.notEqual(data.cursor, cursor, 'Feed cursor must advance');
    cursor = data.cursor;
    assert.ok(page < 99, 'Verification capped at 10,000 indexed rows');
  }
  assert.ok(indexed.size, 'DID has no indexed Mosimo records');
  const uris = rkeys.map(key => `at://${did}/${collection}/${key}`);
  if (!rkeys.length) {
    // Enumerate the PDS, not only AppView: otherwise missing indexed posts pass.
    let pdsCursor;
    for (let page = 0; page < 100; page++) {
      const data = await get(pds, 'com.atproto.repo.listRecords', { repo: did, collection, limit: '100',
        ...(pdsCursor ? { cursor: pdsCursor } : {}) });
      assert.ok(Array.isArray(data.records));
      uris.push(...data.records.map(record => record.uri));
      if (!data.cursor) break;
      assert.notEqual(data.cursor, pdsCursor, 'PDS cursor must advance');
      pdsCursor = data.cursor;
      assert.ok(page < 99, 'Verification capped at 10,000 published rows');
    }
    assert.ok(uris.length, 'PDS has no published Mosimo records');
  }
  for (const uri of uris) {
    const entry = indexed.get(uri);
    assert.ok(entry, `Missing AppView record: ${uri}`);
    const published = await get(pds, 'com.atproto.repo.getRecord', { repo: did, collection, rkey: uri.split('/').at(-1) });
    const { uri: indexedUri, ...record } = entry.record;
    assert.equal(published.uri, indexedUri);
    assert.deepEqual(record, published.value, 'Full record including unknown extensions must remain unchanged');
    assert.equal(entry.provenance?.cid, published.cid, 'AppView CID must match canonical PDS record');
    assert.equal(entry.provenance?.did, did);
    assert.equal(entry.provenance?.collection, collection);
    // Execute the actual web JS with a tiny DOM and real API responses, not a browser/CSS test.
    const client = await runWeb((url, options) => fetch(url, options),
      `?appview=${encodeURIComponent(appview)}&uri=${encodeURIComponent(uri)}`);
    assert.equal(client.articles().length, 1, 'Web must render the exact indexed URI');
    assert.equal(client.articles()[0].querySelector('.text').textContent, published.value.text);
    assert.match(client.status.textContent, /最終取得/);
  }
  assert.deepEqual(await get(pds, 'com.atproto.sync.getLatestCommit', { did }), head, 'PDS changed during verification; rerun');
  console.log(JSON.stringify({ checkedAt: new Date().toISOString(), readOnly: true, did,
    endpoints: { pds, relay, appview }, head, matchedRecords: uris.length,
    cors: true, webJavaScriptRendering: true,
    boundaries: 'Existing public records only; no BLE/live firehose/restart/independent signature or browser-layout verification' }, null, 2));
} catch (error) {
  console.error(error);
  process.exitCode = 1;
}
