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

test('public client key and override survive every page and refresh without leaking to PLC', async () => {
  for (const [key, search, apiOrigin] of [
    ['hvc_c15d9393de6ace328d65633d07d00278', `?did=${did}`, 'https://appview.oriom.dev'],
    ['hvc_regression_override', `?did=${did}&client_key=hvc_regression_override&appview=https://custom-appview.invalid`, 'https://custom-appview.invalid'],
  ]) {
    const calls = [];
    const client = await runWeb(async input => {
      const url = new URL(input);
      calls.push(url);
      if (url.hostname === 'plc.directory') return json({});
      return json(url.searchParams.has('cursor')
        ? { records: [post('older')] }
        : { records: [post('one')], cursor: '1' });
    }, search);
    await client.morePosts();
    await client.refresh();
    assert.equal(client.articles().length, 2);
    const apiCalls = calls.filter(url => url.pathname.startsWith('/xrpc/'));
    assert.equal(apiCalls.length, 5, 'initial page, two-page load and two-page refresh');
    assert.deepEqual(apiCalls.map(url => url.searchParams.get('cursor')), [null, null, '1', null, '1']);
    for (const url of apiCalls) {
      assert.equal(url.origin, apiOrigin);
      assert.equal(url.pathname, '/xrpc/dev.oriom.mosimo.getFeed');
      assert.deepEqual(url.searchParams.getAll('client_key'), [key]);
      assert.equal(url.searchParams.get('did'), did);
      assert.equal(url.searchParams.get('limit'), '50');
    }
    const plcCalls = calls.filter(url => url.hostname === 'plc.directory');
    assert.ok(plcCalls.length, 'exercise the optional identity lookup too');
    assert.ok(plcCalls.every(url => !url.searchParams.has('client_key')));
  }
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
