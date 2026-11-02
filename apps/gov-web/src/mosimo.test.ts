import { expect, test } from 'vitest';
import { buildThreads, decodeGeohash, delayText, parsePost, validityState, type Post } from './mosimo';

const D = 'dev.oriom.mosimo.defs#';
const rec = (uri: string, extensions: unknown[] = [], extra = {}) => ({ uri, text: 't', createdAt: '2026-11-01T00:00:00Z', extensions, ...extra });
const post = (uri: string, extensions: unknown[] = []) => parsePost(rec(uri, extensions))!;
const ann = (relation: string, target: string) => ({ $type: D + 'annotation', relation, target: { network: 'atproto', uri: target } });

test('parsePost reads known extensions and keeps unknown $types', () => {
  const p = parsePost(
    rec('at://did:plc:a/dev.oriom.mosimo.post/1', [
      { $type: D + 'origin', latitudeMicrodegrees: 38268200, longitudeMicrodegrees: 140869400 },
      { $type: D + 'area', regions: [{ shape: 'circle', latitudeMicrodegrees: 1e6, longitudeMicrodegrees: 2e6, radiusMillimeters: 500000 }, { shape: 'geohash', hash: 'xn' }, { shape: 'polygon' }] },
      { $type: D + 'priority', version: 1, level: 7 },
      { $type: D + 'jmaEew' },
    ]),
    { indexedAt: '2026-11-01T03:00:00Z' },
  )!;
  expect(p.did).toBe('did:plc:a');
  expect(p.origin).toEqual([38.2682, 140.8694]);
  expect(p.areas.map(a => a.shape)).toEqual(['circle', 'rect']);
  expect(p.areas[0]).toMatchObject({ center: [1, 2], radiusMeters: 500 });
  expect(p.priority).toBeUndefined();
  expect(p.unknownTypes).toEqual([D + 'jmaEew']);
  expect(delayText(p)).toBe('約3時間遅れで到着');
  expect(parsePost(rec('at://x', [], { $type: 'app.bsky.feed.post', extensions: undefined }))!.bsaf).toBe(true);
  expect(parsePost({ uri: 'at://x' })).toBeUndefined();
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
  expect(validityState({ startsAt: '2026-11-01T00:00:00Z' }, now)).toBe('unknown');
  expect(validityState({ endsAt: '2026-11-01T13:00:00Z' }, now)).toBe('valid');
  expect(validityState({ endsAt: '2026-11-01T11:00:00Z' }, now)).toBe('expired');
  expect(validityState({ startsAt: '2026-11-01T12:30:00Z', endsAt: '2026-11-01T13:00:00Z' }, now)).toBe('unknown');
});

test('buildThreads folds annotation chains under the root', () => {
  const a = 'at://did:plc:a/p/1';
  const posts: Post[] = [
    { ...post('at://did:plc:a/p/2', [ann('correction', a)]), did: 'did:plc:a' },
    { ...post('at://did:plc:b/p/3', [ann('endorsement', a)]), did: 'did:plc:b' },
    { ...post('at://did:plc:c/p/4', [ann('endorsement', a)]), did: 'did:plc:b' },
    { ...post('at://did:plc:b/p/5', [ann('supplement', 'at://did:plc:a/p/2')]), did: 'did:plc:b' },
    post('at://did:plc:b/p/6', [ann('supplement', 'at://missing')]),
    post(a),
  ];
  const threads = buildThreads(posts);
  expect(threads.map(t => t.post.uri)).toEqual(['at://did:plc:b/p/6', a]);
  expect(threads[0].orphan).toBe(true);
  expect(threads[1]).toMatchObject({ corrected: true, endorsers: 1, orphan: false });
  expect(threads[1].replies).toHaveLength(4);
});

test('buildThreads ignores corrections by other authors and survives cycles', () => {
  const x = 'at://did:plc:x/p/1', y = 'at://did:plc:y/p/2';
  const threads = buildThreads([post(x, [ann('correction', y)]), post(y, [ann('correction', x)]), post('at://did:plc:z/p/3', [ann('correction', x)])]);
  expect(threads.map(t => t.post.uri)).toEqual([x, y]);
  expect(threads.every(t => !t.corrected && !t.orphan)).toBe(true);
  expect(threads[1].replies.map(r => r.uri)).toEqual(['at://did:plc:z/p/3']);
});
