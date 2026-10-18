import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runWeb } from './web-harness.mjs';

const did = 'did:plc:synthetic';
const post = (id, extra = {}) => ({
  $type: 'dev.oriom.mosimo.post', uri: `at://${did}/dev.oriom.mosimo.post/${id}`,
  text: `<script>not HTML ${id}</script>`, createdAt: '2020-01-01T00:00:00Z',
  deliveryExpiresAt: '2020-01-02T00:00:00Z', extensions: [{ $type: 'example.unknown.ext', future: true }], ...extra,
});
const json = data => ({ ok: true, json: async () => data });

test('actual client paginates, keeps arrival order and expired/unknown-extension text, deduplicates and refreshes removals', async () => {
  const calls = [];
  let removed = false;
  let fail = false;
  const client = await runWeb(async (input, options) => {
    const url = new URL(input);
    if (url.hostname === 'plc.directory') return json({});
    calls.push(url);
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.cache, 'no-store');
    if (fail) throw new Error('offline');
    if (url.searchParams.has('cursor')) return json({ records: [post('one'), post('old')] });
    return json({ records: removed ? [post('new')] : [post('one'), post('two', { createdAt: '2026-01-01T00:00:00Z' })], cursor: '2' });
  });
  assert.equal(client.articles().length, 2);
  assert.match(client.articles()[0].querySelector('.text').textContent, /not HTML one/);
  assert.equal(client.more.hidden, false);
  await client.morePosts();
  assert.equal(client.articles().length, 3);
  assert.equal(calls.at(-1).searchParams.get('cursor'), '2');
  assert.equal(client.more.hidden, true);
  removed = true;
  await client.refresh();
  assert.equal(client.articles().length, 3);
  assert.match(client.articles()[0].querySelector('.text').textContent, /not HTML new/);
  assert.ok(!client.articles().some(a => a.querySelector('.text').textContent.includes('not HTML two')));
  fail = true;
  await client.refresh();
  assert.equal(client.articles().length, 3, 'failed refresh keeps last successful view');
  assert.match(client.status.textContent, /更新できませんでした/);
  fail = false;
  await client.refresh();
  assert.match(client.status.textContent, /最終取得/);
  assert.equal(client.more.disabled, false);
});

test('DID/URI diagnostic filters, single record response, and BSAF is not presented as Mosimo', async () => {
  const uri = `at://${did}/app.bsky.feed.post/one`;
  const client = await runWeb(async input => {
    const url = new URL(input);
    if (url.hostname === 'plc.directory') return json({});
    assert.equal(url.searchParams.get('did'), did);
    assert.equal(url.searchParams.get('uri'), uri);
    return json({ record: post('one', { uri, $type: 'app.bsky.feed.post' }) });
  }, `?did=${did}&uri=${encodeURIComponent(uri)}`);
  assert.equal(client.articles().length, 1);
  assert.ok(client.articles()[0].querySelectorAll('.time').some(e => e.textContent.includes('BSAF 原投稿')));
  assert.equal(client.more.hidden, true);
});

test('stalled fetch times out and the next poll can recover', { timeout: 15000 }, async () => {
  let stalled = true;
  const client = await runWeb(async (_url, { signal }) => {
    if (!stalled) return json({ records: [] });
    return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  });
  assert.match(client.status.textContent, /更新できませんでした/);
  assert.equal(client.more.disabled, false);
  stalled = false;
  await client.refresh();
  assert.match(client.status.textContent, /最終取得/);
});

test('invalid API response is an error, not a false empty-feed success; next refresh recovers', async () => {
  let valid = false;
  const client = await runWeb(async () => json(valid ? { records: [] } : {}));
  assert.match(client.status.textContent, /更新できませんでした/);
  valid = true;
  await client.refresh();
  assert.match(client.status.textContent, /最終取得/);
  assert.match(client.feed.children[0].textContent, /投稿はまだありません/);
});
