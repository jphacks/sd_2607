import { rootConfig, validateImporterBlock, IMPORTER } from './attest.mjs';

export function validateConfig(input, env = process.env) {
  const config = { plcUrl: 'https://plc.directory', relays: [], peers: [], maxEventBytes: 131072,
    maxSubscriptions: 20, maxResults: 500, maxBufferedBytes: 4194304, maxEvents: 1000000,
    publicInformation: { roots: [], selection: [] }, ...input };
  function endpoint(value, protocols) {
    const url = new URL(value);
    if (!protocols.includes(url.protocol) || url.username || url.password || url.hash || url.search
      || url.pathname !== '/') throw new Error('Endpoints must be credential-free origins');
    return url.origin;
  }
  if (!Array.isArray(config.groups) || !config.groups.length) throw new Error('At least one logical PDS group is required');
  config.groups = config.groups.map(group => ({ id: group.id, endpoint: endpoint(group.endpoint, ['https:']) }));
  for (const group of config.groups) if (typeof group.id !== 'string' || !group.id.length) throw new Error('Invalid group ID');
  if (new Set(config.groups.map(group => group.id)).size !== config.groups.length
    || new Set(config.groups.map(group => group.endpoint)).size !== config.groups.length) throw new Error('Duplicate logical group');
  config.plcUrl = endpoint(config.plcUrl, ['https:']);
  if (!Array.isArray(config.relays) || !Array.isArray(config.peers)) throw new Error('relays and peers must be arrays');
  config.relays = config.relays.map(value => {
    const url = new URL(value);
    if (url.protocol !== 'wss:' || url.username || url.password || url.hash) throw new Error('Invalid relay URL');
    return url.href;
  });
  config.peers = config.peers.map(peer => ({ url: endpoint(peer.url, ['https:']), tokenEnv: peer.tokenEnv ?? 'PEER_TOKEN' }));
  if (config.peers.length && !env.PEER_TOKEN?.trim()) throw new Error('PEER_TOKEN required for peer authentication');
  for (const peer of config.peers) if (!env[peer.tokenEnv]?.trim()) throw new Error(`Missing token variable ${peer.tokenEnv}`);
  for (const key of ['maxEventBytes', 'maxSubscriptions', 'maxResults', 'maxBufferedBytes', 'maxEvents']) {
    if (!Number.isSafeInteger(config[key]) || config[key] < 1) throw new Error(`Invalid ${key}`);
  }
  // Trust roots are receiver-local settings (attest §3): the key, the roles it may grant and the
  // chain depth it may delegate. The selection lists the (Bot, type) pairs this relay authorizes.
  const pi = config.publicInformation;
  if (!pi || !Array.isArray(pi.roots) || pi.roots.length > 64 || !Array.isArray(pi.selection)
    || pi.selection.length > 16 || (pi.revocationDomains !== undefined && !Array.isArray(pi.revocationDomains))) throw new Error('Invalid publicInformation');
  const ids = new Set();
  config.publicInformation = { ...pi, roots: pi.roots.map(root => {
    const normalized = rootConfig(root);
    if (ids.has(normalized.id)) throw new Error('Duplicate public root');
    ids.add(normalized.id);
    return { ...root, pubkey: normalized.id };
  }), revocationDomains: pi.revocationDomains ?? [] };
  if (pi.selection.length) validateImporterBlock({ $type: IMPORTER, sources: pi.selection });
  for (const domain of config.publicInformation.revocationDomains) {
    if (typeof domain !== 'string' || !/^[a-z0-9.-]+$/.test(domain)) throw new Error('Invalid revocation domain');
  }
  return config;
}
