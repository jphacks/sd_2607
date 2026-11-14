import { expect, test } from 'vitest';
import { buildThreads, decodeGeohash, delayText, parsePost, validityState, type Post } from './mosimo';

const NS = 'dev.jisaku.mosimo';
// Deterministic 13-char TID per id so parsePost can recover createdAt from the rkey.
const tidFor = (id: string) => '3' + (id + 'aaaaaaaaaaaa').slice(0, 12).toLowerCase().replace(/[^234567a-z]/g, 'a');
const uriOf = (did: string, id: string, collection = `${NS}.post`) => `at://${did}/${collection}/${tidFor(id)}`;
const rec = (uri: string, ext: unknown[] = [], extra: Record<string, unknown> = {}) => ({ uri, text: 't', $type: `${NS}.post`, exp: '2026-11-01T06:00:00Z', ext, ...extra });
const post = (uri: string, ext: unknown[] = []) => parsePost(rec(uri, ext))!;
const ann = (relation: string, target: string) => ({ $type: `${NS}.annotation`, relation, target: { network: 'atproto', uri: target } });

test('parsePost reads known extensions and keeps unknown $types', () => {
  const p = parsePost(
    rec(uriOf('did:plc:a', '1'), [
      { $type: `${NS}.geo#origin`, lat: 38268200, lon: 140869400 },
      { $type: `${NS}.geo#area`, regions: [{ shape: 'circle', lat: 1e6, lon: 2e6, r: 500000 }, { shape: 'geohash', hash: 'xn' }, { shape: 'polygon' }] },
      { $type: `${NS}.priority`, level: 7 },
      { $type: `${NS}.hazard` },
    ]),
    { indexedAt: '2026-11-01T03:00:00Z' },
  )!;
  expect(p.did).toBe('did:plc:a');
  expect(p.origin).toEqual([38.2682, 140.8694]);
  expect(p.areas.map(a => a.shape)).toEqual(['circle', 'rect']);
  expect(p.areas[0]).toMatchObject({ center: [1, 2], radiusMeters: 500 });
  expect(p.priority).toBeUndefined();
  expect(p.unknownTypes).toEqual([`${NS}.hazard`]);
  // BSAF posts keep their native createdAt and are not parsed through ext.
  const bsaf = parsePost({ uri: 'at://x/app.bsky.feed.post/one', text: 't', $type: 'app.bsky.feed.post', createdAt: '2026-11-01T00:00:00Z' }, { indexedAt: '2026-11-01T03:00:00Z' })!;
  expect(bsaf.bsaf).toBe(true);
  expect(bsaf.createdAt).toBe('2026-11-01T00:00:00Z');
  expect(delayText(bsaf)).toBe('約3時間遅れで到着');
  expect(parsePost({ uri: 'at://x' })).toBeUndefined();
});

test('parsePost prefers record.created, else derives createdAt from the TID in the uri', () => {
  const ns = `${NS}.post`;
  const u = (rkey: string) => `at://did:plc:a/${ns}/${rkey}`;
  const fromTid = parsePost({ uri: u('3laaaaaaaaaaa'), text: 't', $type: ns, exp: '2026-11-01T06:00:00Z', ext: [] })!;
  expect(fromTid.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  const explicit = parsePost({ uri: u('3laaaaaaaaaaa'), text: 't', $type: ns, exp: '2026-11-01T06:00:00Z', created: '2026-11-01T00:00:00.123Z', ext: [] })!;
  expect(explicit.createdAt).toBe('2026-11-01T00:00:00.123Z');
  expect(parsePost({ uri: u('short'), text: 't', $type: ns, exp: '2026-11-01T06:00:00Z', ext: [] })).toBeUndefined();
});

test('decodeGeohash matches the reference cell', () => {
  const [[s, w], [n, e]] = decodeGeohash('xn76')!;
  expect([s, w, n, e].map(v => +v.toFixed(4))).toEqual([35.5078, 139.5703, 35.6836, 139.9219]);
  expect(decodeGeohash('a')).toBeUndefined();
  expect(decodeGeohash('')).toBeUndefined();
});

test('validityState treats a missing or future period as unknown', () => {
  const now = Date.parse('2026-11-01T12:00:00Z');
  expect(validityState(undefined, now)).toBe('unknown');
  expect(validityState({ from: '2026-11-01T00:00:00Z' }, now)).toBe('unknown');
  expect(validityState({ until: '2026-11-01T13:00:00Z' }, now)).toBe('valid');
  expect(validityState({ until: '2026-11-01T11:00:00Z' }, now)).toBe('expired');
  expect(validityState({ from: '2026-11-01T12:30:00Z', until: '2026-11-01T13:00:00Z' }, now)).toBe('unknown');
});

test('buildThreads folds annotation chains under the root', () => {
  const a = uriOf('did:plc:a', '1');
  const posts: Post[] = [
    { ...post(uriOf('did:plc:a', '2'), [ann('correction', a)]), did: 'did:plc:a' },
    { ...post(uriOf('did:plc:b', '3'), [ann('endorsement', a)]), did: 'did:plc:b' },
    { ...post(uriOf('did:plc:c', '4'), [ann('endorsement', a)]), did: 'did:plc:b' },
    { ...post(uriOf('did:plc:b', '5'), [ann('supplement', uriOf('did:plc:a', '2'))]), did: 'did:plc:b' },
    post(uriOf('did:plc:b', '6'), [ann('supplement', 'at://missing')]),
    post(a),
  ];
  const threads = buildThreads(posts);
  expect(threads.map(t => t.post.uri)).toEqual([uriOf('did:plc:b', '6'), a]);
  expect(threads[0].orphan).toBe(true);
  expect(threads[1]).toMatchObject({ corrected: true, endorsers: 1, orphan: false });
  expect(threads[1].replies).toHaveLength(4);
});

test('buildThreads ignores corrections by other authors and survives cycles', () => {
  const x = uriOf('did:plc:x', '1'), y = uriOf('did:plc:y', '2');
  const threads = buildThreads([post(x, [ann('correction', y)]), post(y, [ann('correction', x)]), post(uriOf('did:plc:z', '3'), [ann('correction', x)])]);
  expect(threads.map(t => t.post.uri)).toEqual([x, y]);
  expect(threads.every(t => !t.corrected && !t.orphan)).toBe(true);
  expect(threads[1].replies.map(r => r.uri)).toEqual([uriOf('did:plc:z', '3')]);
});

test('parsePost reads Nostr annotation targets as 参照先未取得 (undefined target)', () => {
  const uri = uriOf('did:plc:a', 'n1');
  const bytes = new Uint8Array(32);
  const nostr = { $type: `${NS}.annotation`, relation: 'correction', target: { network: 'nostr', eventId: bytes } };
  const p = parsePost(rec(uri, [nostr]))!;
  expect(p.annotation).toEqual({ relation: 'correction', target: undefined });
});

test('alert flags BSAF originals and posts carrying hazard or official-source extensions', () => {
  const u = uriOf('did:plc:a', 'alert');
  expect(post(u).alert).toBe(false);
  expect(post(u, [{ $type: `${NS}.geo#origin`, lat: 1, lon: 2 }, { $type: `${NS}.priority`, level: 1 }]).alert).toBe(false);
  expect(post(u, [{ $type: `${NS}.hazard`, type: 'earthquake' }]).alert).toBe(true);
  expect(post(u, [{ $type: `${NS}.hazard.weather` }]).alert).toBe(true);
  expect(post(u, [{ $type: `${NS}.public.bsaf` }]).alert).toBe(true);
  expect(post(u, [{ $type: `${NS}.public#source` }]).alert).toBe(true);
  expect(post(u, [{ $type: `${NS}.hazardous.other` }]).alert).toBe(false);
  expect(parsePost({ uri: 'at://x/app.bsky.feed.post/one', text: 't', $type: 'app.bsky.feed.post', createdAt: '2026-11-01T00:00:00Z' })!.alert).toBe(true);
});
