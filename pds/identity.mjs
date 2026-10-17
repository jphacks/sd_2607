// Only the configured PLC directory is contacted; untrusted DID documents never choose a fetch URL.
export async function fetchJson(url, { maxBytes = 262144, ...options } = {}) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`Upstream HTTP ${response.status}`);
  let size = 0;
  const chunks = [];
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > maxBytes) throw new Error('Upstream response too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

export function createIdentityResolver(config, getJson = fetchJson) {
  return async did => {
    if (!/^did:plc:[a-z2-7]{24}$/.test(did)) throw new Error('Only did:plc identities are supported');
    const origin = new URL(config.plcUrl ?? 'https://plc.directory').origin;
    const [data, didDoc] = await Promise.all([
      getJson(`${origin}/${did}/data`), getJson(`${origin}/${did}`),
    ]);
    const key = data.signingKeys?.atproto;
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
