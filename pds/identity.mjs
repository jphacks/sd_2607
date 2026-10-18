// Validate text before JSON.parse loses duplicate object keys or invalid UTF-8 bytes.
export function parseNetworkJson(bytes) {
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  const value = JSON.parse(text);
  const tokens = text.match(/"(?:[^"\\]|\\.)*"|[{}\[\]:,]|[^\s{}\[\]:,]+/g) ?? [];
  let offset = 0;
  let items = 0;
  function walk(depth) {
    if (depth > 64 || ++items > 32768) throw new Error('JSON resource limit');
    const token = tokens[offset++];
    if (token === '{') {
      const keys = new Set();
      while (tokens[offset] !== '}') {
        const key = JSON.parse(tokens[offset++]);
        if (keys.has(key)) throw new Error('Duplicate JSON key');
        keys.add(key);
        offset++; // colon; syntax was already checked by JSON.parse
        walk(depth + 1);
        if (tokens[offset] === ',') offset++;
      }
      offset++;
    } else if (token === '[') {
      while (tokens[offset] !== ']') {
        walk(depth + 1);
        if (tokens[offset] === ',') offset++;
      }
      offset++;
    }
  }
  walk(0);
  return value;
}

// Only configured URLs are contacted; untrusted documents never choose a fetch URL.
export async function fetchBytes(url, { maxBytes = 262144, contentType, ...options } = {}) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`Upstream HTTP ${response.status}`);
  if (contentType && response.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== contentType) {
    await response.body?.cancel();
    throw new Error('Unexpected upstream content type');
  }
  let size = 0;
  const chunks = [];
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > maxBytes) throw new Error('Upstream response too large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

export async function fetchJson(url, options) {
  return parseNetworkJson(await fetchBytes(url, options));
}

export function createIdentityResolver(config, getJson = fetchJson) {
  return async did => {
    if (!/^did:plc:[a-z2-7]{24}$/.test(did)) throw new Error('Only did:plc identities are supported');
    const origin = new URL(config.plcUrl ?? 'https://plc.directory').origin;
    const [data, didDoc] = await Promise.all([
      getJson(`${origin}/${did}/data`), getJson(`${origin}/${did}`),
    ]);
    const key = data.verificationMethods?.atproto;
    const service = data.services?.atproto_pds;
    if (data.did !== did || didDoc.id !== did || !key?.startsWith('did:key:')
      || service?.type !== 'AtprotoPersonalDataServer') throw new Error('Invalid PLC identity');
    const endpoint = new URL(service.endpoint);
    if (endpoint.protocol !== 'https:' || endpoint.username || endpoint.password || endpoint.search
      || endpoint.hash || !['', '/'].includes(endpoint.pathname)) throw new Error('Invalid canonical PDS endpoint');
    const handle = (data.alsoKnownAs ?? []).find(value => /^at:\/\/[^/]+$/.test(value))?.slice(5);
    return { did, signingKey: key, endpoint: endpoint.origin, didDoc, handle };
  };
}
