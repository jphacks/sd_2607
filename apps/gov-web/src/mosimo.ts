// Mosimo feed parsing. Extension shapes follow protocol/0004, 0011 and 0020.
const DEFS = 'dev.oriom.mosimo.defs#';

export type LatLng = [number, number];
export type Area = { shape: 'circle'; center: LatLng; radiusMeters: number } | { shape: 'rect'; bounds: [LatLng, LatLng] };
export type Relation = 'correction' | 'supplement' | 'endorsement';
export type Validity = { startsAt?: string; endsAt?: string };
export type ValidityState = 'valid' | 'expired' | 'unknown';

export type Post = {
  uri: string;
  did: string;
  text: string;
  createdAt: string;
  indexedAt?: string;
  bsaf: boolean;
  origin?: LatLng;
  areas: Area[];
  validity?: Validity;
  annotation?: { relation: Relation; target?: string };
  priority?: number;
  unknownTypes: string[];
};

const obj = (v: unknown): Record<string, unknown> | undefined =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
const int = (v: unknown) => (Number.isInteger(v) ? (v as number) : undefined);
const str = (v: unknown) => (typeof v === 'string' ? v : undefined);
const micro = (lat: unknown, lon: unknown): LatLng | undefined => {
  const a = int(lat), b = int(lon);
  return a == null || b == null ? undefined : [a / 1e6, b / 1e6];
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

function parseArea(region: unknown): Area | undefined {
  const r = obj(region);
  if (r?.shape === 'circle') {
    const center = micro(r.latitudeMicrodegrees, r.longitudeMicrodegrees);
    const mm = int(r.radiusMillimeters);
    return center && mm != null && mm >= 0 ? { shape: 'circle', center, radiusMeters: mm / 1000 } : undefined;
  }
  if (r?.shape === 'geohash' && typeof r.hash === 'string') {
    const bounds = decodeGeohash(r.hash);
    return bounds && { shape: 'rect', bounds };
  }
}

export function parsePost(record: Record<string, unknown>, provenance?: Record<string, unknown>): Post | undefined {
  const uri = str(record.uri), text = str(record.text), createdAt = str(record.createdAt);
  if (!uri || text == null || !createdAt) return undefined;
  const post: Post = {
    uri,
    did: uri.split('/')[2] ?? '',
    text,
    createdAt,
    indexedAt: str(provenance?.indexedAt),
    bsaf: record.$type === 'app.bsky.feed.post',
    areas: [],
    unknownTypes: [],
  };
  for (const ext of Array.isArray(record.extensions) ? record.extensions : []) {
    const e = obj(ext);
    const type = str(e?.$type);
    if (!e || !type) continue;
    switch (type) {
      case DEFS + 'origin':
        post.origin = micro(e.latitudeMicrodegrees, e.longitudeMicrodegrees);
        break;
      case DEFS + 'area':
        for (const region of Array.isArray(e.regions) ? e.regions : []) {
          const area = parseArea(region);
          if (area) post.areas.push(area);
        }
        break;
      case DEFS + 'validity':
        post.validity = { startsAt: str(e.startsAt), endsAt: str(e.endsAt) };
        break;
      case DEFS + 'annotation': {
        const relation = e.relation;
        if (relation === 'correction' || relation === 'supplement' || relation === 'endorsement') {
          // Nostr targets have no AppView URI, so they always read as 参照先未取得.
          post.annotation = { relation, target: str(obj(e.target)?.uri) };
        }
        break;
      }
      case DEFS + 'priority': {
        const level = int(e.level);
        if (level != null && level >= 0 && level <= 3) post.priority = level;
        break;
      }
      default:
        post.unknownTypes.push(type);
    }
  }
  return post;
}

// A missing end means 不明 (0011), and a not-yet-started period is not 有効 either.
export function validityState(v: Validity | undefined, now = Date.now()): ValidityState {
  const end = Date.parse(v?.endsAt ?? '');
  if (!(end >= 0)) return 'unknown';
  if (now > end) return 'expired';
  const start = Date.parse(v?.startsAt ?? '');
  return start > now ? 'unknown' : 'valid';
}

export type Thread = {
  post: Post;
  replies: Post[];
  corrected: boolean;
  endorsers: number;
  orphan: boolean;
};

// Feed order is kept; annotations whose target is loaded fold under it.
export function buildThreads(posts: Post[]): Thread[] {
  const byUri = new Map(posts.map(p => [p.uri, p]));
  // Walk annotation chains up to the root so a supplement to a correction is not lost.
  // Posts on a cycle stay roots; a chain that runs into a cycle folds under the cycle member it reached.
  const targetOf = (p: Post) => {
    const seen = new Set([p.uri]);
    let root: Post | undefined;
    for (let t = p.annotation?.target; t && byUri.has(t); t = root.annotation?.target) {
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
        endorsers: new Set(direct('endorsement').map(r => r.did)).size,
        orphan: !!post.annotation && !byUri.has(post.annotation.target ?? ''),
      };
    });
}

export function delayText(post: Post): string | undefined {
  const min = (Date.parse(post.indexedAt ?? '') - Date.parse(post.createdAt)) / 60000;
  if (!(min >= 5)) return undefined;
  if (min < 60) return `約${Math.round(min)}分遅れで到着`;
  if (min < 2880) return `約${Math.round(min / 60)}時間遅れで到着`;
  return `約${Math.round(min / 1440)}日遅れで到着`;
}

export type FeedPage = { posts: Post[]; cursor?: string };

export async function fetchFeed(appview: string, clientKey: string, q: { cursor?: string; did?: string; uri?: string }): Promise<FeedPage> {
  const url = new URL('/xrpc/dev.oriom.mosimo.getFeed', appview);
  url.searchParams.set('limit', '50');
  // Public HappyView client key: an app identifier checked against allowed origins, not a secret.
  if (clientKey) url.searchParams.set('client_key', clientKey);
  for (const [k, v] of Object.entries(q)) if (v) url.searchParams.set(k, v);
  const res = await fetch(url, { signal: AbortSignal.timeout(10000), cache: 'no-store' });
  if (!res.ok) throw new Error(String(res.status));
  const data = await res.json();
  const rows: unknown = data.record ? [data.record] : data.records;
  if (!Array.isArray(rows)) throw new Error('Invalid feed response');
  const provenance = obj(data.provenance) ?? {};
  const posts = rows.flatMap(r => {
    const rec = obj(r);
    const post = rec && parsePost(rec, obj(provenance[str(rec.uri) ?? '']));
    return post ? [post] : [];
  });
  const cursor = data.cursor;
  if (cursor != null && (typeof cursor !== 'string' || cursor === q.cursor)) throw new Error('Invalid cursor');
  return { posts, cursor: cursor ?? undefined };
}

const handles = new Map<string, Promise<string | undefined>>();
// ponytail: shows the PLC alsoKnownAs handle without the bidirectional DNS/well-known check; add it if handles must be trusted.
export function resolveHandle(did: string): Promise<string | undefined> {
  if (!did.startsWith('did:plc:')) return Promise.resolve(undefined);
  let p = handles.get(did);
  if (!p) {
    p = fetch(`https://plc.directory/${encodeURIComponent(did)}`, { signal: AbortSignal.timeout(10000) })
      .then(r => (r.ok ? r.json() : null))
      .then(doc => {
        const aka = (doc?.alsoKnownAs as string[] | undefined)?.find(a => a.startsWith('at://'));
        return aka && '@' + aka.slice(5);
      })
      .catch(() => undefined);
    handles.set(did, p);
  }
  return p;
}
