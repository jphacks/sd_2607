import { beforeEach, expect, test } from 'vitest';
import { cborMapText, clearNostrCache, eventIdMatches, postUriOf, resolveEventUris, type NostrEvent } from './nostr';
import { parsePost, resolveNostrTargets, buildThreads } from './mosimo';

// Real kind-1 event from the production relay (an Android post); an endorsement targets it by event ID.
const EVENT: NostrEvent = {"id":"740710bcaf75037609f8e2aa0d5f24f931e357fc4f23e674f78554de46d0ff43","pubkey":"ae51a945bcc535d8058d951a2794b8c14ea0f9407ecd3ae0a7bf6844604191b4","created_at":1794634702,"kind":1,"tags":[["t","mosimo.jisaku.dev"],["mosimo:v","1"],["mosimo:commit","pmNkaWR4IGRpZDpwbGM6Zjc2bnF5N21kN3BxdGpsejVqbWllY21qY3Jldm0zbjJhcnR6ZjRjMmFoY3NpZ1hArtOcdQ9lqAJ/v9J/NEKgtB6Ba3mVPMCv5uSyeMxekZhEdHe6CCad95KfuquGfGQ1yLGwZ/tTPCcZ8Yp64RQHb2RkYXRh2CpYJQABcRIgZ/ZPL7t+pMDC3TcMCI8KW7vu0GWFGtcUANMipwSVxpBkcHJldvZndmVyc2lvbgM"],["mosimo:base","bafyreih42ndo7c72gc5v4vxoxmzo7k7itqvspgno5ge2zv6ttpqgdqa43y"],["mosimo:rkey","3n2artzf4c2ah"],["mosimo:exp","2026-11-15T05:38:22Z"],["mosimo:ext:dev.jisaku.mosimo.geo#origin","o2NhY2MZJldjbGF0GgIj68FjbG9uGghSd8I"],["mosimo:ext:dev.jisaku.mosimo.validity","oWV1bnRpbHQyMDI2LTExLTE0VDExOjM4OjIyWg"],["mosimo:ext:dev.jisaku.bosai.connectivity","oWZvbmxpbmX0"]],"content":"［安全］Test post from dev offline","sig":"28fb5649282a1dc2ac1f459e3d20d8a92ea89f17d8525425941ecb07333e3f34393892f5d99ee80c0eef806b1dbf4e8713bbe933703b87a2cd52bf7604953e6f"};
const URI = 'at://did:plc:f76nqy7md7pqtjlz5jmiecmj/dev.jisaku.mosimo.post/3n2artzf4c2ah';

// Fake relay: answers REQ with the given events, then EOSE.
function relay(events: NostrEvent[], calls: string[][] = []) {
  return (_url: string) => {
    const ws = {
      onopen: null as any, onmessage: null as any, onerror: null as any, onclose: null as any,
      send(data: string) {
        const [, sub, filter] = JSON.parse(data);
        calls.push(filter.ids);
        queueMicrotask(() => {
          for (const e of events) if (filter.ids.includes(e.id)) ws.onmessage?.({ data: JSON.stringify(['EVENT', sub, e]) });
          ws.onmessage?.({ data: JSON.stringify(['EOSE', sub]) });
        });
      },
      close() {},
    };
    queueMicrotask(() => ws.onopen?.({}));
    return ws as any;
  };
}

beforeEach(() => clearNostrCache());

test('reads the DID from the signed commit and builds the post URI', async () => {
  expect(await eventIdMatches(EVENT)).toBe(true);
  expect(postUriOf(EVENT)).toBe(URI);
  expect(cborMapText(new Uint8Array([0xa1, 0x63, 0x64, 0x69, 0x64, 0x61, 0x78]), 'did')).toBe('x');
  expect(cborMapText(new Uint8Array([0xa1, 0x63]), 'did')).toBeUndefined();
  expect(postUriOf({ ...EVENT, kind: 8173 })).toBeUndefined();
  expect(postUriOf({ ...EVENT, tags: EVENT.tags.slice(1) })).toBeUndefined();
});

test('rejects events whose ID does not match their content', async () => {
  const forged = { ...EVENT, content: 'forged' };
  expect(await eventIdMatches(forged)).toBe(false);
  expect((await resolveEventUris('wss://relay', [EVENT.id], { open: relay([forged]) })).size).toBe(0);
});

test('caches hits and backs off on misses', async () => {
  const calls: string[][] = [];
  const missing = 'f'.repeat(64);
  const open = relay([EVENT], calls);
  const first = await resolveEventUris('wss://relay', [EVENT.id, missing, 'not-hex'], { open, now: 0 });
  expect(first.get(EVENT.id)).toBe(URI);
  expect(calls).toEqual([[EVENT.id, missing]]);
  await resolveEventUris('wss://relay', [EVENT.id, missing], { open, now: 60_000 });
  expect(calls).toHaveLength(1);
  await resolveEventUris('wss://relay', [EVENT.id, missing], { open, now: 6 * 60_000 });
  expect(calls[1]).toEqual([missing]);
});

test('an endorsement by Nostr event ID folds under the atproto post', async () => {
  const NS = 'dev.jisaku.mosimo';
  const bytes = btoa(String.fromCharCode(...EVENT.id.match(/../g)!.map(h => parseInt(h, 16)))).replace(/=+$/, '');
  const target = parsePost({ uri: URI, text: EVENT.content, $type: `${NS}.post`, exp: '2026-11-15T05:38:22Z', ext: [] })!;
  const endorse = parsePost({
    uri: 'at://did:plc:other/dev.jisaku.mosimo.post/3n2aruvk3dsah', text: '確認しました', $type: `${NS}.post`, exp: '2026-11-15T05:38:52Z',
    ext: [{ $type: `${NS}.annotation`, relation: 'endorsement', target: { network: 'nostr', eventId: { $bytes: bytes } } }],
  })!;
  expect(endorse.annotation).toEqual({ relation: 'endorsement', nostr: true, eventId: EVENT.id });
  expect(buildThreads([endorse, target]).map(t => t.orphan)).toEqual([true, false]);
  await resolveNostrTargets([endorse, target], 'wss://relay', { open: relay([EVENT]) });
  expect(endorse.annotation?.target).toBe(URI);
  const [thread] = buildThreads([endorse, target]);
  expect(thread.post.uri).toBe(URI);
  expect(thread.endorsers).toBe(1);
  expect(thread.replies.map(r => r.uri)).toEqual([endorse.uri]);
});
