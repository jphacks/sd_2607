// Map Nostr event IDs to atproto post URIs (protocol §7.2), so annotations from apps that
// target the Nostr form of a post (Android) fold under the atproto post the feed shows.
// The event is fetched from a Mosimo relay by ID; the URI is at://<commit did>/<collection>/<mosimo:rkey>.

const POST_COLLECTION = 'dev.jisaku.mosimo.post';
const HEX64 = /^[0-9a-f]{64}$/;

export function base64ToBytes(text: string): Uint8Array | undefined {
  try {
    const bin = atob(text);
    return Uint8Array.from(bin, c => c.charCodeAt(0));
  } catch {
    return undefined;
  }
}

export const toHex = (bytes: Uint8Array) => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');

// Minimal DAG-CBOR reader: walks a top-level map and returns the text value of `key`.
export function cborMapText(bytes: Uint8Array, key: string): string | undefined {
  let i = 0;
  const dec = new TextDecoder('utf-8', { fatal: true });
  const head = () => {
    if (i >= bytes.length) throw new Error('eof');
    const b = bytes[i++], major = b >> 5, info = b & 31;
    let n: number;
    if (info < 24) n = info;
    else if (info === 24) n = bytes[i++];
    else if (info === 25) { n = (bytes[i] << 8) | bytes[i + 1]; i += 2; }
    else if (info === 26) { n = ((bytes[i] << 24) >>> 0) + ((bytes[i + 1] << 16) | (bytes[i + 2] << 8) | bytes[i + 3]); i += 4; }
    else if (info === 27) { n = 0; for (let k = 0; k < 8; k++) n = n * 256 + bytes[i++]; }
    else throw new Error('indefinite length');
    if (i > bytes.length) throw new Error('eof');
    return { major, n };
  };
  const text = (n: number) => {
    if (i + n > bytes.length) throw new Error('eof');
    return dec.decode(bytes.subarray(i, (i += n)));
  };
  const skip = (): void => {
    const { major, n } = head();
    if (major === 2 || major === 3) { if ((i += n) > bytes.length) throw new Error('eof'); }
    else if (major === 4) for (let k = 0; k < n; k++) skip();
    else if (major === 5) for (let k = 0; k < 2 * n; k++) skip();
    else if (major === 6) skip();
  };
  try {
    const top = head();
    if (top.major !== 5) return undefined;
    for (let k = 0; k < top.n; k++) {
      const h = head();
      if (h.major !== 3) return undefined;
      if (text(h.n) === key) {
        const v = head();
        return v.major === 3 ? text(v.n) : undefined;
      }
      skip();
    }
  } catch {
    return undefined;
  }
}

export type NostrEvent = { id: string; pubkey: string; created_at: number; kind: number; tags: string[][]; content: string; sig: string };

// NIP-01: the ID is the SHA-256 of the canonical serialization, so a relay cannot substitute another event.
export async function eventIdMatches(e: NostrEvent): Promise<boolean> {
  const body = new TextEncoder().encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]));
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', body));
  return toHex(digest) === e.id;
}

// Fixed tag order of protocol §7.2: t, mosimo:v, mosimo:commit, mosimo:base, mosimo:rkey.
export function postUriOf(e: NostrEvent): string | undefined {
  const t = e.tags;
  if (e.kind !== 1 || !Array.isArray(t) || t.length < 5) return undefined;
  if (t[0]?.[0] !== 't' || t[1]?.[0] !== 'mosimo:v' || t[1][1] !== '1' || t[2]?.[0] !== 'mosimo:commit' || t[4]?.[0] !== 'mosimo:rkey') return undefined;
  const commit = base64ToBytes(t[2][1] ?? '');
  const did = commit && cborMapText(commit, 'did');
  const rkey = t[4][1];
  if (!did || !/^did:[a-z]+:[A-Za-z0-9._:%-]+$/.test(did) || !/^[234567a-z]{13}$/.test(rkey ?? '')) return undefined;
  return `at://${did}/${POST_COLLECTION}/${rkey}`;
}

// Positive results are permanent; misses are retried after a while so the 10-second poll does not reopen the relay each time.
const RETRY_MS = 5 * 60_000;
const cache = new Map<string, { uri?: string; at: number }>();

export function clearNostrCache() {
  cache.clear();
}

type Socket = Pick<WebSocket, 'send' | 'close'> & {
  onopen: ((ev: Event) => unknown) | null;
  onmessage: ((ev: MessageEvent) => unknown) | null;
  onerror: ((ev: Event) => unknown) | null;
  onclose: ((ev: CloseEvent) => unknown) | null;
};

function query(relay: string, ids: string[], timeoutMs: number, open: (url: string) => Socket): Promise<NostrEvent[]> {
  return new Promise(resolve => {
    const events: NostrEvent[] = [];
    let ws: Socket;
    const finish = () => {
      clearTimeout(timer);
      try { ws.close(); } catch { /* already closed */ }
      resolve(events);
    };
    const timer = setTimeout(finish, timeoutMs);
    try {
      ws = open(relay);
    } catch {
      clearTimeout(timer);
      resolve(events);
      return;
    }
    ws.onopen = () => ws.send(JSON.stringify(['REQ', 'targets', { ids, limit: ids.length }]));
    ws.onmessage = ev => {
      try {
        const msg = JSON.parse(String(ev.data));
        if (msg[0] === 'EVENT' && msg[1] === 'targets') events.push(msg[2]);
        else if (msg[0] === 'EOSE' || msg[0] === 'CLOSED') finish();
      } catch { /* ignore malformed frames */ }
    };
    ws.onerror = finish;
    ws.onclose = finish;
  });
}

export async function resolveEventUris(
  relay: string,
  eventIds: string[],
  { timeoutMs = 5000, now = Date.now(), open = (url: string) => new WebSocket(url) as Socket }: { timeoutMs?: number; now?: number; open?: (url: string) => Socket } = {},
): Promise<Map<string, string>> {
  const out = new Map<string, string>();
  const todo: string[] = [];
  for (const id of new Set(eventIds)) {
    if (!HEX64.test(id)) continue;
    const hit = cache.get(id);
    if (hit?.uri) out.set(id, hit.uri);
    else if (!hit || now - hit.at >= RETRY_MS) todo.push(id);
  }
  if (!relay || !todo.length) return out;
  // The relay caps a filter at its maxResults (500); stay well below it.
  for (let k = 0; k < todo.length; k += 100) {
    const batch = todo.slice(k, k + 100);
    const events = await query(relay, batch, timeoutMs, open);
    for (const e of events) {
      if (!e || typeof e.id !== 'string' || !batch.includes(e.id) || !(await eventIdMatches(e))) continue;
      const uri = postUriOf(e);
      if (uri) cache.set(e.id, { uri, at: now });
    }
    for (const id of batch) {
      const uri = cache.get(id)?.uri;
      if (uri) out.set(id, uri);
      else cache.set(id, { at: now });
    }
  }
  return out;
}
