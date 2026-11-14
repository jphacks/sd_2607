import { createElement } from 'react';
import { renderToString } from 'react-dom/server';
import { afterEach, expect, test, vi } from 'vitest';

// App reads document.title at import and location in a state initialiser; MapView needs a Leaflet DOM, so it is mocked.
vi.hoisted(() => {
  Object.assign(globalThis, { document: { title: 'Mosimo' }, location: { search: '', href: 'http://localhost/' } });
});
const h = vi.hoisted(() => ({ threads: undefined as unknown, store: {} as Record<string, string> }));
vi.mock('./MapView', () => ({ default: () => null, postParts: () => [], insetFocus: '' }));
// SSR runs no effects, so the feed never loads; inject the threads App would build from the loaded posts.
vi.mock('./mosimo', async orig => {
  const actual = await orig<typeof import('./mosimo')>();
  return { ...actual, buildThreads: (p: never) => (h.threads as ReturnType<typeof actual.buildThreads>) ?? actual.buildThreads(p) };
});

import App from './App';
import { buildThreads, parsePost } from './mosimo';

afterEach(() => { h.threads = undefined; h.store = {}; vi.unstubAllGlobals(); });
const render = () => {
  vi.stubGlobal('localStorage', { getItem: (k: string) => h.store[k] ?? null, setItem: () => {} });
  // SSR separates adjacent text nodes with <!-- -->; drop them so the text reads as on screen.
  return renderToString(createElement(App)).replaceAll('<!-- -->', '');
};
const N = 'dev.jisaku.mosimo.', D = 'dev.oriom.mosimo.defs#';

// HAZARD_STATUS['__proto__'] used to be Object.prototype, which React refuses as a child and which blanked the dashboard.
test('R1 a hazard status of "__proto__" renders as an unknown status instead of crashing the app', () => {
  const p = parsePost({ $type: N + 'post', uri: 'at://did:plc:a/dev.jisaku.mosimo.post/proto', text: '津波避難訓練', exp: '2026-11-15T00:00:00Z',
    ext: [{ $type: N + 'hazard', status: '__proto__', category: ['Geo'], severity: 'Extreme', urgency: 'Immediate', certainty: 'Observed' }] })!;
  h.threads = [{ post: p, replies: [], corrected: false, endorsers: 0, orphan: false }];
  expect(render()).toContain('状態 __proto__');
});

// A correction loaded before its target is a root and gets marked; once the target loads, the thread re-roots under it.
// D6-A: the status is the root's own record, so the target's incident stays 未対応 and nothing is inherited.
test('R2 a status recorded on a post while it was a root does not mark the thread it later folds into', () => {
  const target = parsePost({ uri: 'at://did:plc:a/dev.oriom.mosimo.post/root', text: '片平小学校の避難所が定員超過', createdAt: '2026-11-14T00:00:00Z', extensions: [] })!;
  const fix = parsePost({ uri: 'at://did:plc:a/dev.oriom.mosimo.post/fix', text: '訂正: 五橋中学校です', createdAt: '2026-11-14T00:10:00Z',
    extensions: [{ $type: D + 'annotation', relation: 'correction', target: { network: 'atproto', uri: target.uri } }] })!;
  expect(buildThreads([fix])[0].post.uri).toBe(fix.uri);
  h.store = { 'mosimo-gov-web:status': JSON.stringify({ [fix.uri]: 'done' }) };
  h.threads = buildThreads([fix, target]);
  const html = render();
  expect(html).toContain('未対応（1）');
  expect(html).toContain('対応済（0）');
  expect(html).not.toContain('関連投稿に記録あり');
});

// public/bsaf §5.1: a value's meaning is the bot's own, so only a mapped bot gets a label; BSAF rows carry no 位置情報なし (D6-D).
test('BSAF rows show a Japanese value label for a mapped bot only, never the raw tag or 位置情報なし', () => {
  const bsaf = (did: string) => parsePost({ $type: 'app.bsky.feed.post', uri: `at://${did}/app.bsky.feed.post/1`, text: '【濃霧警報・注意報】',
    tags: ['bsaf:v1', 'type:dense-fog-warning', 'value:cancelled', 'target:jp-akita', 'source:jma'] })!;
  const row = (p: ReturnType<typeof bsaf>) => ({ post: p, replies: [], corrected: false, endorsers: 0, orphan: false });
  h.threads = [row(bsaf('did:plc:kxwz5cz7o6g4jlmxh6doyfsm'))];
  let html = render();
  expect(html).toContain('>解除</span>');
  expect(html).not.toContain('value:');
  expect(html).not.toContain('位置情報なし');
  h.threads = [row(bsaf('did:plc:someotherbot0000000000'))];
  html = render();
  expect(html).not.toContain('解除');
  expect(html).not.toContain('value:');
});

// Live feed: 14 Kikikuru posts are type:landslide-warning value:warning, headed 【土砂災害警戒情報】.
const kikikuru = (tags: string[], text = '🟪【土砂災害警戒情報】') => ({ post: parsePost({ $type: 'app.bsky.feed.post',
  uri: 'at://did:plc:kxwz5cz7o6g4jlmxh6doyfsm/app.bsky.feed.post/3n2a', text, createdAt: '2026-11-14T00:00:00Z', tags })!, replies: [], corrected: false, endorsers: 0, orphan: false });

test('L1 a Kikikuru 土砂災害警戒情報 (landslide-warning + warning) is labelled as itself, not 警報', () => {
  h.threads = [kikikuru(['bsaf:v1', 'type:landslide-warning', 'value:warning', 'target:jp-hiroshima', 'source:jma'])];
  const html = render();
  expect(html).not.toContain('>警報</span>');
  expect(html).toContain('>土砂災害警戒情報</span>');
});

// public/bsaf/hazard §1: only a listed (type, value) pair is interpreted; §3.1: conflicting core tags are not resolved.
test('L2 a Kikikuru post without exactly one known type: tag gets no value label', () => {
  for (const tags of [['bsaf:v1', 'value:warning'], ['bsaf:v1', 'type:tornado-warning', 'type:wind-warning', 'value:warning'], ['bsaf:v1', 'type:new-kind-warning', 'value:warning']]) {
    h.threads = [kikikuru(tags, '【テスト】')];
    const html = render();
    expect(html, tags.join(' ')).not.toContain('>警報</span>');
    expect(html, tags.join(' ')).not.toContain('>竜巻注意情報</span>');
  }
});

// D11: the apps' connectivity extension in the user's wording; mesh relays stand out, nothing when the post has none.
test('connectivity shows 直接投稿 or mesh経由で投稿, and nothing when absent', () => {
  const p = (k: string, ext: unknown[]) => ({ post: parsePost({ $type: N + 'post', uri: `at://did:plc:a/dev.jisaku.mosimo.post/${k}`, text: k, exp: '2026-11-15T00:00:00Z', ext })!,
    replies: [], corrected: false, endorsers: 0, orphan: false });
  const c = (online: unknown) => ({ $type: 'dev.jisaku.bosai.connectivity', online });
  h.threads = [p('on', [c(true)]), p('off', [c(false)])];
  const html = render();
  expect(html).toContain('>直接投稿</span>');
  expect(html).toContain('>mesh経由で投稿</span>');
  h.threads = [p('none', []), p('bad', [c('yes')])];
  expect(render()).not.toMatch(/直接投稿|mesh経由/);
});
