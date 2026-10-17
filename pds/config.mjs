export function validateConfig(input, env = process.env) {
  const config = { plcUrl: 'https://plc.directory', relays: [], peers: [], maxEventBytes: 131072,
    maxSubscriptions: 20, maxResults: 500, maxBufferedBytes: 4194304, maxEvents: 1000000, ...input };
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
  return config;
}
