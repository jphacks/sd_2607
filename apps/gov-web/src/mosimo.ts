// Mosimo feed parsing. Reads the current wire (protocol/mosimo/README.md §4.1, §5: dev.jisaku.mosimo.post with `ext`)
// and the pre-cutover old wire (dev.oriom.mosimo.post with `extensions` of dev.oriom.mosimo.defs#*).
// No time is read from the TID rkey: core §6.2 forbids using it as the posting time.
// Extension shapes follow protocol/mosimo/{geo,validity,annotation,priority,hazard,public/bsaf}/.
const OLD = 'dev.oriom.mosimo.defs#', NEW = 'dev.jisaku.mosimo.';
export const APPVIEW: string = import.meta.env.VITE_APPVIEW_URL || 'https://appview.oriom.dev';
// Public HappyView client key: an app identifier checked against allowed origins, not a secret.
export const CLIENT_KEY: string = import.meta.env.VITE_CLIENT_KEY ?? '';

export type LatLng = [number, number];
export type Area = { shape: 'circle'; center: LatLng; radiusMeters: number } | { shape: 'rect'; bounds: [LatLng, LatLng]; hash: string };
export type Relation = 'correction' | 'supplement' | 'endorsement';
export type Validity = { startsAt?: string; endsAt?: string };
export type ValidityState = 'valid' | 'expired' | 'pending' | 'unknown';

export type Post = {
  uri: string;
  did: string;
  text: string;
  createdAt?: string;
  receivedAt?: string;
  firstSeenAt?: string;
  cid?: string;
  bsaf: boolean;
  bsafTags: string[];
  repostOf?: string;
  repostCid?: string;
  origin?: LatLng;
  areas: Area[];
  areaIncomplete?: boolean;
  validity?: Validity;
  annotation?: { relation: Relation; target?: string; nostr?: boolean };
  priority?: number;
  hazardStatus?: string;
  conflicts: string[];
  unknownTypes: string[];
};

const obj = (v: unknown): Record<string, unknown> | undefined =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
const int = (v: unknown) => (Number.isInteger(v) ? (v as number) : undefined);
const str = (v: unknown) => (typeof v === 'string' ? v : undefined);
const strs = (v: unknown) => (Array.isArray(v) ? v.filter((s): s is string => typeof s === 'string') : []);
// Out-of-range coordinates violate the geo lexicon bounds and are dropped, not clamped.
const micro = (lat: unknown, lon: unknown): LatLng | undefined => {
  const a = int(lat), b = int(lon);
  return a == null || b == null || Math.abs(a) > 90e6 || Math.abs(b) > 180e6 ? undefined : [a / 1e6, b / 1e6];
};

const BASE32 = '0123456789bcdefghjkmnpqrstuvwxyz';
export function decodeGeohash(hash: string): [LatLng, LatLng] | undefined {
  const lat = [-90, 90], lon = [-180, 180];
  let even = true;
  for (const c of hash.toLowerCase()) {
    const n = BASE32.indexOf(c);
    if (n < 0) return undefined;
    for (let bit = 4; bit >= 0; bit--) {
      const range = even ? lon : lat;
      const mid = (range[0] + range[1]) / 2;
      range[(n >> bit) & 1 ? 0 : 1] = mid;
      even = !even;
    }
  }
  return hash ? [[lat[0], lon[0]], [lat[1], lon[1]]] : undefined;
}

// geo#region: the lexicon's properties (lat/lon/r) win over its stale description.
// The lexicon sets no maximum radius; past half the Earth's circumference a circle covers it all, so clamp there and keep map bounds finite.
const MAX_RADIUS_MM = 2e10;
function parseArea(region: unknown, isNew: boolean): Area | undefined {
  const r = obj(region);
  if (r?.shape === 'circle') {
    const center = isNew ? micro(r.lat, r.lon) : micro(r.latitudeMicrodegrees, r.longitudeMicrodegrees);
    const mm = int(isNew ? r.r : r.radiusMillimeters);
    return center && mm != null && mm >= 0 ? { shape: 'circle', center, radiusMeters: Math.min(mm, MAX_RADIUS_MM) / 1000 } : undefined;
  }
  if (r?.shape === 'geohash' && typeof r.hash === 'string') {
    const bounds = decodeGeohash(r.hash);
    return bounds && { shape: 'rect', bounds, hash: r.hash };
  }
}

// Both wires map onto one kind per extension; the kind is what `conflicts` reports.
const KIND = new Map([
  [OLD + 'origin', 'origin'], [NEW + 'geo#origin', 'origin'],
  [OLD + 'area', 'area'], [NEW + 'geo#area', 'area'],
  [OLD + 'validity', 'validity'], [NEW + 'validity', 'validity'],
  [OLD + 'annotation', 'annotation'], [NEW + 'annotation', 'annotation'],
  [OLD + 'priority', 'priority'], [NEW + 'priority', 'priority'],
  [NEW + 'hazard', 'hazard'],
  [OLD + 'bsafV1', 'bsaf'], [NEW + 'public.bsaf', 'bsaf'],
]);

export function parsePost(record: Record<string, unknown>, provenance?: Record<string, unknown>): Post | undefined {
  const uri = str(record.uri), text = str(record.text);
  if (!uri || text == null) return undefined;
  const bsaf = record.$type === 'app.bsky.feed.post';
  const post: Post = {
    uri,
    did: uri.split('/')[2] ?? '',
    text,
    // The new wire's canonical time is the Nostr created_at, which the AppView does not serve yet; never inferred from the TID (README §6.2).
    createdAt: str(record.created) ?? str(record.createdAt),
    // firstSeenAt is the AppView's first arrival; indexedAt (rewritten by backfills) only stands in until it is served.
    receivedAt: str(provenance?.firstSeenAt) ?? str(provenance?.indexedAt),
    firstSeenAt: str(provenance?.firstSeenAt),
    cid: str(provenance?.cid),
    bsaf,
    bsafTags: bsaf ? strs(record.tags) : [],
    areas: [],
    conflicts: [],
    unknownTypes: [],
  };
  const byKind = new Map<string, Record<string, unknown>[]>();
  const exts = record.ext ?? record.extensions;
  for (const ext of Array.isArray(exts) ? exts : []) {
    const e = obj(ext), type = str(e?.$type);
    if (!e || !type) continue;
    const kind = KIND.get(type);
    if (kind) byKind.set(kind, [...(byKind.get(kind) ?? []), e]);
    else post.unknownTypes.push(type);
  }
  for (const [kind, [e, ...rest]] of byKind) {
    // Repeats are neither merged nor last-wins (README §5.3, priority §2, hazard §1): none is adopted.
    if (rest.length) {
      post.conflicts.push(kind);
      if (kind === 'area') post.areaIncomplete = true;
      continue;
    }
    const isNew = (e.$type as string).startsWith(NEW);
    switch (kind) {
      case 'origin':
        post.origin = isNew ? micro(e.lat, e.lon) : micro(e.latitudeMicrodegrees, e.longitudeMicrodegrees);
        break;
      case 'area':
        // A region we cannot read leaves containment 判定不明, never 範囲外 (geo §4.2).
        if (!Array.isArray(e.regions)) post.areaIncomplete = true;
        for (const region of Array.isArray(e.regions) ? e.regions : []) {
          const area = parseArea(region, isNew);
          if (area) post.areas.push(area);
          else post.areaIncomplete = true;
        }
        break;
      case 'validity':
        post.validity = isNew ? { startsAt: str(e.from), endsAt: str(e.until) } : { startsAt: str(e.startsAt), endsAt: str(e.endsAt) };
        break;
      case 'annotation': {
        const relation = e.relation, t = obj(e.target);
        if (relation === 'correction' || relation === 'supplement' || relation === 'endorsement') {
          post.annotation = { relation, target: str(t?.uri) };
          // Matching a Nostr eventId needs the AppView's atproto↔Nostr index (annotation §4), which it does not serve yet.
          if (t?.network === 'nostr' && !post.annotation.target) post.annotation.nostr = true;
        }
        break;
      }
      case 'priority': {
        const level = int(e.level);
        if (level != null && level >= 0 && level <= 3) post.priority = level;
        break;
      }
      case 'hazard':
        // A missing or unknown status must never read as a real event (hazard §2.1).
        if (e.status !== 'Actual') post.hazardStatus = str(e.status) ?? 'Unknown';
        break;
      case 'bsaf':
        // Tags stay verbatim (public/bsaf §3.1); `original` marks an import of an existing BSAF post.
        post.bsafTags = strs(e.tags);
        post.repostOf = str(obj(e.original)?.uri);
        post.repostCid = str(obj(e.original)?.cid);
        break;
    }
  }
  return post;
}

// A missing end means 不明 (validity §3), but a future start alone is enough for 開始前; the period is from <= t < until.
export function validityState(v: Validity | undefined, now = Date.now()): ValidityState {
  const start = Date.parse(v?.startsAt ?? ''), end = Date.parse(v?.endsAt ?? '');
  if (!(end >= 0)) return start > now ? 'pending' : 'unknown';
  if (now >= end) return 'expired';
  return start > now ? 'pending' : 'valid';
}

// Needs both the author's creation time and the first arrival; indexedAt is never used (backfills rewrite it).
export function delayText(post: Post): string | undefined {
  const min = (Date.parse(post.firstSeenAt ?? '') - Date.parse(post.createdAt ?? '')) / 60000;
  if (!(min >= 5)) return undefined;
  if (min < 60) return `約${Math.round(min)}分遅れで到着`;
  if (min < 2880) return `約${Math.round(min / 60)}時間遅れで到着`;
  return `約${Math.round(min / 1440)}日遅れで到着`;
}

export type Thread = {
  post: Post;
  replies: Post[];
  corrected: boolean;
  endorsers: number;
  orphan: boolean;
};

// Feed order is kept; annotations and BSAF reposts whose target is loaded fold under it.
export function buildThreads(posts: Post[]): Thread[] {
  const byUri = new Map(posts.map(p => [p.uri, p]));
  // public/bsaf §3.2, §10: a repost belongs to its original only when both URI and CID match.
  const parent = (p: Post) => p.annotation?.target ?? (p.repostCid && byUri.get(p.repostOf ?? '')?.cid === p.repostCid ? p.repostOf : undefined);
  // Walk annotation chains up to the root so a supplement to a correction is not lost.
  // Posts on a cycle stay roots; a chain that runs into a cycle folds under the cycle member it reached.
  const targetOf = (p: Post) => {
    const seen = new Set([p.uri]);
    let root: Post | undefined;
    for (let t = parent(p); t && byUri.has(t); t = parent(root)) {
      if (t === p.uri) return undefined;
      if (seen.has(t)) break;
      seen.add(t);
      root = byUri.get(t)!;
    }
    return root?.uri;
  };
  const replies = new Map<string, Post[]>();
  for (const p of posts) {
    const t = targetOf(p);
    if (t) replies.set(t, [...(replies.get(t) ?? []), p]);
  }
  return posts
    .filter(p => !targetOf(p))
    .map(post => {
      const rs = replies.get(post.uri) ?? [];
      const direct = (relation: Relation) => rs.filter(r => r.annotation?.relation === relation && r.annotation.target === post.uri);
      return {
        post,
        replies: rs,
        // Only the original author can correct a post; others' corrections are listed but do not dim it.
        corrected: direct('correction').some(r => r.did === post.did),
        // Self-endorsement is no corroboration.
        endorsers: new Set(direct('endorsement').map(r => r.did).filter(d => d !== post.did)).size,
        orphan: !!post.annotation && !byUri.has(post.annotation.target ?? ''),
      };
    });
}

export type FeedPage = { posts: Post[]; cursor?: string };

export async function fetchFeed(appview: string, clientKey: string, q: { cursor?: string; did?: string; uri?: string }): Promise<FeedPage> {
  const url = new URL('/xrpc/dev.jisaku.mosimo.getFeed', appview);
  url.searchParams.set('limit', '50');
  if (clientKey) url.searchParams.set('client_key', clientKey);
  for (const [k, v] of Object.entries(q)) if (v) url.searchParams.set(k, v);
  const res = await fetch(url, { signal: AbortSignal.timeout(10000), cache: 'no-store' });
  if (!res.ok) throw new Error(String(res.status));
  const data = await res.json();
  const rows: unknown = data.record ? [data.record] : data.records;
  if (!Array.isArray(rows)) throw new Error('Invalid feed response');
  const provenance = obj(data.provenance) ?? {};
  const posts = rows.flatMap(r => {
    const rec = obj(r), uri = str(rec?.uri) ?? '';
    // Own keys only: a record URI must not reach Object.prototype.
    const post = rec && parsePost(rec, obj(Object.hasOwn(provenance, uri) ? provenance[uri] : undefined));
    return post ? [post] : [];
  });
  const cursor = data.cursor;
  if (cursor != null && (typeof cursor !== 'string' || cursor === q.cursor)) throw new Error('Invalid cursor');
  return { posts, cursor: cursor ?? undefined };
}

// 4xx is a definite answer and may be cached; 5xx, 429 and network errors are transient and throw.
const getJson = (url: string | URL) =>
  fetch(url, { signal: AbortSignal.timeout(10000) }).then(r => {
    if (r.ok) return r.json();
    if (r.status >= 500 || r.status === 429) throw new Error(String(r.status));
    return null;
  });

const handles = new Map<string, Promise<string | undefined>>();
// The PLC alsoKnownAs claim is shown only when that handle resolves back to the same DID (bidirectional check).
// The AppView resolves it, so no third-party origin is added; transient failures leave the cache so they retry.
export function resolveHandle(did: string): Promise<string | undefined> {
  if (!did.startsWith('did:plc:')) return Promise.resolve(undefined);
  let p = handles.get(did);
  if (!p) {
    p = (async () => {
      const doc = await getJson(`https://plc.directory/${encodeURIComponent(did)}`);
      const handle = strs(doc?.alsoKnownAs).find(a => a.startsWith('at://'))?.slice(5);
      if (!handle) return undefined;
      const url = new URL('/xrpc/com.atproto.identity.resolveHandle', APPVIEW);
      url.searchParams.set('handle', handle);
      if (CLIENT_KEY) url.searchParams.set('client_key', CLIENT_KEY);
      return (await getJson(url))?.did === did ? '@' + handle : undefined;
    })().catch(() => {
      handles.delete(did);
      return undefined;
    });
    handles.set(did, p);
  }
  return p;
}
