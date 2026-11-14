import { afterEach, expect, test, vi } from 'vitest';
import protocolCovers from '../../../protocol/mosimo/public/bsaf/hazard/prefectures.json';
import { APPVIEW, buildThreads, CLIENT_KEY, decodeGeohash, delayText, geohashOutline, parsePost, resolveHandle, validityState, type Post } from './mosimo';
import covers from './prefectures.json';

test('arrival delay uses firstSeenAt only, never the backfill-rewritten indexedAt', () => {
  const rec = { uri: 'at://did:plc:a/app.bsky.feed.post/1', $type: 'app.bsky.feed.post', text: 't', createdAt: '2026-11-01T00:00:00Z' };
  const late = parsePost(rec, { indexedAt: '2026-11-03T00:00:00Z', firstSeenAt: '2026-11-01T03:00:00Z' })!;
  expect(late.receivedAt).toBe('2026-11-01T03:00:00Z');
  expect(delayText(late)).toBe('約3時間遅れで到着');
  expect(delayText(parsePost(rec, { firstSeenAt: '2026-11-01T00:04:00Z' })!)).toBeUndefined();
  const legacy = parsePost(rec, { indexedAt: '2026-11-03T00:00:00Z' })!;
  expect(legacy.receivedAt).toBe('2026-11-03T00:00:00Z');
  expect(delayText(legacy)).toBeUndefined();
  // No author time (new wire without `created`): shown as 受信, never as a delay.
  expect(delayText(parsePost({ uri: 'at://did:plc:a/dev.jisaku.mosimo.post/3n2a', text: 't', exp: '2026-11-02T00:00:00Z', ext: [] }, { firstSeenAt: '2026-11-03T00:00:00Z' })!)).toBeUndefined();
});

afterEach(() => { vi.unstubAllGlobals(); });

const D = 'dev.oriom.mosimo.defs#', N = 'dev.jisaku.mosimo.';
const rec = (uri: string, extensions: unknown[] = [], extra = {}) => ({ uri, text: 't', createdAt: '2026-11-01T00:00:00Z', extensions, ...extra });
const post = (uri: string, extensions: unknown[] = []) => parsePost(rec(uri, extensions))!;
const ann = (relation: string, target: string) => ({ $type: D + 'annotation', relation, target: { network: 'atproto', uri: target } });
// protocol/mosimo/post.json: text, exp, created?, ext — no createdAt. Same shape apps/ios emits.
const newWire = (ext: unknown[], extra: Record<string, unknown> = {}) =>
  ({ $type: N + 'post', uri: 'at://did:plc:a/dev.jisaku.mosimo.post/3mzz4soyrtc3q', text: '近所で火災', exp: '2026-11-15T00:00:00Z', ext, ...extra });

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
  // R9: the polygon is unreadable, so containment is 判定不明, not 範囲外 (geo §4.2).
  expect(p.areaIncomplete).toBe(true);
  expect(p.priority).toBeUndefined();
  expect(p.conflicts).toEqual([]);
  expect(p.unknownTypes).toEqual([D + 'jmaEew']);
  expect(p).toMatchObject({ createdAt: '2026-11-01T00:00:00Z', receivedAt: '2026-11-01T03:00:00Z' });
  expect(parsePost(rec('at://x', [], { $type: 'app.bsky.feed.post', extensions: undefined }))!.bsaf).toBe(true);
  expect(parsePost({ uri: 'at://x' })).toBeUndefined();
});

test('B1 a new-wire post without a creation time is kept, not dated from its TID', () => {
  const p = parsePost(newWire([]), { indexedAt: '2026-11-14T00:00:00Z' })!;
  expect(p).toMatchObject({ text: '近所で火災', receivedAt: '2026-11-14T00:00:00Z', bsafTags: [], conflicts: [] });
  expect(p.createdAt).toBeUndefined();
  expect(parsePost(newWire([], { created: '2026-11-13T23:59:59.5Z' }))!.createdAt).toBe('2026-11-13T23:59:59.5Z');
});

test('B2 current-protocol extensions are read from ext', () => {
  const p = parsePost(newWire([
    { $type: N + 'geo#origin', lat: 38268200, lon: 140869400 },
    { $type: N + 'geo#area', regions: [{ shape: 'circle', lat: 1000000, lon: 2000000, r: 500000 }, { shape: 'geohash', hash: 'xn76' }] },
    { $type: N + 'validity', from: '2026-11-01T00:00:00Z', until: '2026-11-02T00:00:00Z' },
    { $type: N + 'annotation', relation: 'correction', target: { network: 'atproto', uri: 'at://did:plc:a/dev.jisaku.mosimo.post/3mzz37wog7k3q' } },
    { $type: N + 'priority', level: 3 },
  ]))!;
  expect(p.origin).toEqual([38.2682, 140.8694]);
  expect(p.areas.map(a => a.shape)).toEqual(['circle', 'rect']);
  expect(p.areas[0]).toMatchObject({ center: [1, 2], radiusMeters: 500 });
  expect(p.areas[1]).toMatchObject({ hash: 'xn76' });
  expect(p.areaIncomplete).toBeUndefined();
  expect(validityState(p.validity, Date.parse('2026-11-01T12:00:00Z'))).toBe('valid');
  expect(p.annotation).toEqual({ relation: 'correction', target: 'at://did:plc:a/dev.jisaku.mosimo.post/3mzz37wog7k3q' });
  expect(p.priority).toBe(3);
  expect(p.unknownTypes).toEqual([]);
});

test('B9 repeated extensions adopt none and report the kind; regions within one area are not repeats', () => {
  const p = parsePost(newWire([
    { $type: N + 'priority', level: 1 }, { $type: N + 'priority', level: 3 },
    { $type: N + 'geo#origin', lat: 1, lon: 1 }, { $type: D + 'origin', latitudeMicrodegrees: 2, longitudeMicrodegrees: 2 },
    { $type: N + 'geo#area', regions: [{ shape: 'geohash', hash: 'xn' }, { shape: 'geohash', hash: 'xp' }] },
    { $type: N + 'validity', until: '2026-11-02T00:00:00Z' }, { $type: N + 'validity', until: '2026-11-03T00:00:00Z' },
  ]))!;
  expect(p.priority).toBeUndefined();
  expect(p.origin).toBeUndefined();
  expect(p.validity).toBeUndefined();
  expect(p.areas).toHaveLength(2);
  expect(p.conflicts).toEqual(['priority', 'origin', 'validity']);
});

test('B14 out-of-range coordinates are rejected', () => {
  expect(post('at://x', [{ $type: D + 'origin', latitudeMicrodegrees: 135000000, longitudeMicrodegrees: 400000000 }]).origin).toBeUndefined();
  expect(parsePost(newWire([{ $type: N + 'geo#area', regions: [{ shape: 'circle', lat: 0, lon: 180000001, r: 1 }] }]))!.areas).toEqual([]);
});

test('B26 hazardStatus is set only when the status is not Actual', () => {
  const hz = (status?: string) => parsePost(newWire([{ $type: N + 'hazard', status, category: ['Met'], severity: 'Minor', urgency: 'Future', certainty: 'Likely' }]))!.hazardStatus;
  expect(hz('Actual')).toBeUndefined();
  expect(hz('Exercise')).toBe('Exercise');
  expect(hz()).toBe('Unknown');
});

test('B7 BSAF tags survive verbatim', () => {
  // Trimmed copy of a live record (appview.oriom.dev getFeed, 2026-11-14).
  const tags = ['bsaf:v1', 'type:wind-warning', 'value:cancelled', 'time:2026-11-14T01:23:00.000Z', 'target:jp-fukuoka', 'source:jma'];
  const p = parsePost({ $type: 'app.bsky.feed.post', uri: 'at://did:plc:k/app.bsky.feed.post/3n2ae4xq2ca2r', createdAt: '2026-11-14T01:32:50.551Z', langs: ['ja'], tags, text: '【暴風警報・注意報】解除' })!;
  expect(p).toMatchObject({ bsaf: true, bsafTags: tags, createdAt: '2026-11-14T01:32:50.551Z' });
  expect(post('at://did:plc:a/p/1').bsafTags).toEqual([]);
});

// The Nix build sees only apps/gov-web, so the protocol's prefecture covers are copied; the copy must not drift.
test('the bundled prefecture covers equal protocol/mosimo/public/bsaf/hazard/prefectures.json', () => {
  expect(covers).toEqual(protocolCovers);
});

// public/bsaf/hazard §1, §2.3: what the publisher would add as geo#area, derived from the raw record.
test('a Kikikuru per-prefecture post gets its prefecture cover as an approximate area; nothing else does', () => {
  const K = 'did:plc:kxwz5cz7o6g4jlmxh6doyfsm', J = 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi';
  const bsaf = (did: string, tags: string[]) => parsePost({ $type: 'app.bsky.feed.post', uri: `at://${did}/app.bsky.feed.post/1`, text: 't', tags })!;
  const tags = (target: string, type = 'tornado-warning', value = 'warning') => ['bsaf:v1', `type:${type}`, `value:${value}`, 'time:2026-11-14T01:23:00.000Z', target, 'source:jma'];
  const chiba = bsaf(K, tags('target:jp-chiba'));
  expect(chiba.areaApprox).toBe(true);
  expect(chiba.areas.map(a => a.shape === 'rect' && a.hash)).toEqual(covers['jp-chiba']);
  expect(chiba.areaIncomplete).toBeUndefined();
  for (const p of [
    bsaf(J, tags('target:jp-kanto')), bsaf(J, tags('target:jp-chiba')), // §2.3: JMA Bot's target is only the first region
    bsaf(K, tags('target:jp')), // national typhoon information
    bsaf(K, [...tags('target:jp-chiba'), 'target:jp-tokyo']), // §3.1: repeated core tags are not resolved
    bsaf(K, tags('target:jp-chiba', 'new-kind-warning')), bsaf(K, tags('target:jp-chiba', 'wind-warning', 'level9')), // §1: not in the tables
    bsaf(K, tags('target:__proto__')), bsaf(K, tags('target:constructor')),
    // A repost by someone else carries the tags but is not the Bot's own post.
    parsePost(newWire([{ $type: N + 'public.bsaf', tags: tags('target:jp-chiba'), original: { uri: `at://${K}/app.bsky.feed.post/1` } }]))!,
  ]) expect([p.areas, p.areaApprox]).toEqual([[], undefined]);
  // A converted post's own geo#area keeps working, flag included (geo §4.3).
  const own = parsePost(newWire([{ $type: N + 'geo#area', regions: [{ shape: 'geohash', hash: 'xn5q' }], approximation: true }]))!;
  expect(own).toMatchObject({ areas: [{ hash: 'xn5q' }], areaApprox: true });
});

test('connectivity is read when online is a boolean, and repeats adopt none', () => {
  const c = (online: unknown) => ({ $type: 'dev.jisaku.bosai.connectivity', online });
  const on = (...ext: unknown[]) => parsePost(newWire(ext))!;
  expect(on().online).toBeUndefined();
  expect(on(c(true))).toMatchObject({ online: true, unknownTypes: [], conflicts: [] });
  expect(on(c(false)).online).toBe(false);
  expect([on(c('false')).online, on(c('false')).unknownTypes]).toEqual([undefined, []]);
  expect(on({ $type: 'dev.jisaku.bosai.connectivity' }).online).toBeUndefined();
  expect([on(c(true), c(false)).online, on(c(true), c(false)).conflicts]).toEqual([undefined, ['connectivity']]);
});

test('validityState: unknown without an end, pending before the start, end exclusive (B13)', () => {
  const now = Date.parse('2026-11-01T12:00:00Z');
  expect(validityState(undefined, now)).toBe('unknown');
  expect(validityState({ startsAt: '2026-11-01T00:00:00Z' }, now)).toBe('unknown');
  expect(validityState({ endsAt: '2026-11-01T13:00:00Z' }, now)).toBe('valid');
  expect(validityState({ endsAt: '2026-11-01T11:00:00Z' }, now)).toBe('expired');
  expect(validityState({ endsAt: '2026-11-01T12:00:00Z' }, now)).toBe('expired');
  expect(validityState({ startsAt: '2026-11-01T12:30:00Z', endsAt: '2026-11-01T13:00:00Z' }, now)).toBe('pending');
});

test('R3 a period that has not started yet is 開始前 even when its end is unknown', () => {
  const now = Date.parse('2026-11-01T12:00:00Z');
  expect(validityState({ startsAt: '2026-11-02T01:00:00Z' }, now)).toBe('pending');
});

test('R5 an absurd circle radius is clamped so map bounds stay finite', () => {
  const [a] = parsePost(rec('at://x', [{ $type: D + 'area', regions: [{ shape: 'circle', latitudeMicrodegrees: 90000000, longitudeMicrodegrees: 0, radiusMillimeters: 2e300 }] }]))!.areas;
  expect(a).toMatchObject({ shape: 'circle', radiusMeters: 2e7 });
});

test('decodeGeohash matches the reference cell', () => {
  const [[s, w], [n, e]] = decodeGeohash('xn76')!;
  expect([s, w, n, e].map(v => +v.toFixed(4))).toEqual([35.5078, 139.5703, 35.6836, 139.9219]);
  expect(decodeGeohash('a')).toBeUndefined();
  expect(decodeGeohash('')).toBeUndefined();
});

test('geohashOutline keeps only the outer sides of a union (same cases as the apps\' GeohashTests)', () => {
  const sides = (...h: string[]) => geohashOutline(h.map(x => decodeGeohash(x)!));
  expect(sides('xn7')).toHaveLength(4);
  expect(sides('xn76', 'xn77')).toHaveLength(6);
  expect(sides('xn76', 'xn7d')).toHaveLength(6);
  expect(sides('xn76', 'xn7m')).toHaveLength(8);
  // A 2×2 block is a square of 8 cell sides, a column of 4 has 10; a prefecture cover loses its inner sides.
  expect(sides('xn76', 'xn77', 'xn7d', 'xn7e')).toHaveLength(8);
  expect(sides('xn76', 'xn77', 'xn7k', 'xn7m')).toHaveLength(10);
  expect(sides(...covers['jp-chiba']).length).toBeLessThan(covers['jp-chiba'].length * 4);
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

test('buildThreads does not count the author endorsing their own post (D3)', () => {
  const a = 'at://did:plc:a/p/1';
  const [t] = buildThreads([post(a), post('at://did:plc:a/p/2', [ann('endorsement', a)]), post('at://did:plc:b/p/3', [ann('endorsement', a)])]);
  expect(t).toMatchObject({ endorsers: 1 });
  expect(t.replies).toHaveLength(2);
});

test('B25 a #bsafV1 repost folds under its loaded original', () => {
  const o = 'at://did:plc:jma/app.bsky.feed.post/3my6flj3wax2j';
  const tags = ['bsaf:v1', 'type:ashfall', 'value:info'];
  const wrap = post('at://did:plc:m/dev.oriom.mosimo.post/1', [{ $type: D + 'bsafV1', tags, langs: ['ja'], original: { uri: o, cid: 'bafy', createdAt: '2026-10-18T20:03:09.229Z' } }]);
  expect(wrap).toMatchObject({ bsaf: false, repostOf: o, bsafTags: tags });
  const orig = parsePost({ $type: 'app.bsky.feed.post', uri: o, text: '降灰予報', tags }, { cid: 'bafy' })!;
  expect(buildThreads([wrap, orig]).map(t => [t.post.uri, t.replies.map(r => r.uri), t.orphan])).toEqual([[o, [wrap.uri], false]]);
  expect(buildThreads([wrap])).toMatchObject([{ post: wrap, orphan: false }]);
});

// public/bsaf §3.2, §10: the same URI with a different (or unknown) CID is different content, so it is not shown as its 転載.
test('R4 a #bsafV1 repost whose original.cid differs from the loaded record does not fold under it', () => {
  const o = 'at://did:plc:jma/app.bsky.feed.post/3my6flj3wax2j';
  const tags = ['bsaf:v1', 'type:ashfall', 'value:info'];
  const wrap = post('at://did:plc:m/dev.oriom.mosimo.post/1', [{ $type: D + 'bsafV1', tags, original: { uri: o, cid: 'bafyOLD' } }]);
  const orig = (cid?: string) => parsePost({ $type: 'app.bsky.feed.post', uri: o, text: '降灰予報（訂正版）', tags }, cid ? { cid } : undefined)!;
  expect(buildThreads([wrap, orig('bafyNEW')])).toHaveLength(2);
  expect(buildThreads([wrap, orig()])).toHaveLength(2);
});

test('B10 a Nostr annotation target is flagged and stays unresolved', () => {
  const p = post('at://did:plc:a/p/1', [{ $type: D + 'annotation', relation: 'endorsement', target: { network: 'nostr', eventId: 'c677' } }]);
  expect(p.annotation).toEqual({ relation: 'endorsement', target: undefined, nostr: true });
  expect(buildThreads([p])[0].orphan).toBe(true);
});

const did = (s: string) => `did:plc:${s.padEnd(24, 'x')}`;
const identity = (resolvesTo: string) => vi.fn(async (u: string | URL) =>
  String(u).startsWith('https://plc.directory/') ? Response.json({ alsoKnownAs: ['at://sendai.example'] }) : Response.json({ did: resolvesTo }));

test('B8 a claimed handle is shown only when it resolves back to the same DID', async () => {
  const liar = did('impersonatorb8'), owner = did('ownerb8');
  const fetchMock = identity(owner);
  vi.stubGlobal('fetch', fetchMock);
  expect(await resolveHandle(liar)).toBeUndefined();
  const sent = new URL('/xrpc/com.atproto.identity.resolveHandle?handle=sendai.example', APPVIEW);
  if (CLIENT_KEY) sent.searchParams.set('client_key', CLIENT_KEY);
  expect(String(fetchMock.mock.calls[1][0])).toBe(sent.href);
  expect(await resolveHandle(owner)).toBe('@sendai.example');
});

test('B12 a transient failure is not cached', async () => {
  const d = did('transientb12');
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('network down'); }));
  expect(await resolveHandle(d)).toBeUndefined();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 503 })));
  expect(await resolveHandle(d)).toBeUndefined();
  vi.stubGlobal('fetch', identity(d));
  expect(await resolveHandle(d)).toBe('@sendai.example');
});

test('alert flags BSAF originals and posts carrying hazard or official-source extensions', () => {
  const alert = (ext: unknown[]) => parsePost(newWire(ext))!.alert;
  expect(alert([])).toBe(false);
  expect(alert([{ $type: `${N}geo#origin`, lat: 1, lon: 2 }, { $type: `${N}priority`, level: 1 }])).toBe(false);
  expect(alert([{ $type: `${N}hazard`, type: 'earthquake' }])).toBe(true);
  expect(alert([{ $type: `${N}hazard.weather` }])).toBe(true);
  expect(alert([{ $type: `${N}public.bsaf` }])).toBe(true);
  expect(alert([{ $type: `${N}public#source` }])).toBe(true);
  expect(alert([{ $type: `${N}hazardous.other` }])).toBe(false);
  expect(parsePost({ uri: 'at://x/app.bsky.feed.post/one', text: 't', $type: 'app.bsky.feed.post', createdAt: '2026-11-01T00:00:00Z' })!.alert).toBe(true);
});
