import type { LatLngBounds } from 'leaflet';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import MapView, { postBounds } from './MapView';
import { buildThreads, delayText, fetchFeed, resolveHandle, validityState, type Post, type Relation, type Thread, type ValidityState } from './mosimo';

const APPVIEW = import.meta.env.VITE_APPVIEW_URL || 'https://appview.oriom.dev';
const CLIENT_KEY: string = import.meta.env.VITE_CLIENT_KEY ?? '';
const POLL_MS = 10_000;

type Status = 'todo' | 'doing' | 'done';
const STATUS: Record<Status, string> = { todo: '未対応', doing: '対応中', done: '対応済' };
const VALIDITY: Record<ValidityState, string> = { valid: '有効', expired: '期限切れ', unknown: '有効期間不明' };
const RELATION: Record<Relation, string> = { correction: '訂正', supplement: '補足', endorsement: '追認' };
const STATUS_KEY = 'mosimo-gov-web:status';

const merge = (a: Post[], b: Post[]) => {
  const seen = new Set(a.map(p => p.uri));
  return [...a, ...b.filter(p => !seen.has(p.uri))];
};
const short = (did: string) => (did.length > 24 ? did.slice(0, 20) + '…' : did);
const fmt = (s?: string) => {
  const t = Date.parse(s ?? '');
  return Number.isNaN(t) ? (s ?? '—') : new Date(t).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
};

function useHandle(did: string) {
  const [handle, setHandle] = useState<{ did: string; value?: string }>();
  useEffect(() => {
    let live = true;
    resolveHandle(did).then(value => live && setHandle({ did, value }));
    return () => { live = false; };
  }, [did]);
  return (handle?.did === did && handle.value) || short(did);
}

function useStatuses() {
  const [statuses, setStatuses] = useState<Record<string, Status>>(() => {
    try { return JSON.parse(localStorage.getItem(STATUS_KEY) ?? '{}'); } catch { return {}; }
  });
  const set = (uri: string, s: Status) => setStatuses(prev => {
    const next = { ...prev, [uri]: s };
    try { localStorage.setItem(STATUS_KEY, JSON.stringify(next)); } catch { /* private mode: keep in memory */ }
    return next;
  });
  return [statuses, set] as const;
}

function useUrlParam(name: string) {
  const [value, setValue] = useState(() => new URLSearchParams(location.search).get(name) ?? undefined);
  const set = useCallback((v?: string) => {
    const url = new URL(location.href);
    if (v) url.searchParams.set(name, v); else url.searchParams.delete(name);
    history.replaceState(null, '', url);
    setValue(v);
  }, [name]);
  return [value, set] as const;
}

const Badge = ({ tone = 'gray', children }: { tone?: 'gray' | 'blue' | 'red' | 'orange' | 'green'; children: ReactNode }) => {
  const tones = {
    gray: 'border-solid-gray-420 text-solid-gray-800',
    blue: 'border-key-800 text-key-900',
    red: 'border-error-1 text-error-2',
    orange: 'border-warning-orange-1 text-warning-orange-2',
    green: 'border-success-1 text-success-2',
  };
  return <span className={`inline-block rounded-4 border bg-white px-1.5 text-dns-14N-130 ${tones[tone]}`}>{children}</span>;
};

function PostBadges({ post, now }: { post: Post; now: number }) {
  const v = validityState(post.validity, now);
  const delay = delayText(post);
  return (
    <>
      <Badge>身元未確認</Badge>
      {post.bsaf && <Badge tone="blue">BSAF原投稿</Badge>}
      {post.annotation && <Badge tone="blue">{RELATION[post.annotation.relation]}</Badge>}
      <Badge tone={v === 'valid' ? 'green' : v === 'expired' ? 'red' : 'gray'}>{VALIDITY[v]}</Badge>
      {post.priority != null && <Badge tone={post.priority >= 2 ? 'red' : 'orange'}>優先度 {post.priority}</Badge>}
      {delay && <Badge tone="orange">{delay}</Badge>}
      {!post.origin && !post.areas.length && <Badge>位置情報なし</Badge>}
    </>
  );
}

function ThreadItem({ thread, status, selected, now, onSelect }: { thread: Thread; status: Status; selected: boolean; now: number; onSelect: () => void }) {
  const { post } = thread;
  const name = useHandle(post.did);
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        aria-current={selected || undefined}
        className={`block w-full border-b border-solid-gray-200 px-4 py-3 text-left hover:bg-solid-gray-50 ${selected ? 'bg-key-50 shadow-[inset_4px_0_0] shadow-key-800' : ''}`}
      >
        <div className="flex items-baseline justify-between gap-2">
          <span className="truncate text-dns-16B-130">{name}</span>
          <span className="shrink-0 text-dns-14N-130 text-solid-gray-600">{fmt(post.createdAt)}</span>
        </div>
        <p className={`mt-1 line-clamp-3 whitespace-pre-wrap break-words text-std-16N-170 ${thread.corrected ? 'text-solid-gray-536' : ''}`}>{post.text}</p>
        <div className="mt-1.5 flex flex-wrap gap-1">
          <Badge tone={status === 'todo' ? 'red' : status === 'doing' ? 'orange' : 'green'}>{STATUS[status]}</Badge>
          {thread.corrected && <Badge tone="red">訂正あり</Badge>}
          {thread.orphan && <Badge>参照先未取得</Badge>}
          {thread.endorsers > 0 && <Badge tone="blue">追認 {thread.endorsers}</Badge>}
          {thread.replies.length > 0 && <Badge>関連 {thread.replies.length}件</Badge>}
          <PostBadges post={post} now={now} />
        </div>
      </button>
    </li>
  );
}

function Reply({ post, onSelect }: { post: Post; onSelect: () => void }) {
  const name = useHandle(post.did);
  return (
    <li>
      <button type="button" onClick={onSelect} className="block w-full rounded-8 border border-solid-gray-200 px-3 py-2 text-left hover:bg-solid-gray-50">
        <div className="flex flex-wrap items-baseline gap-2 text-dns-14N-130">
          <Badge tone="blue">{post.annotation ? RELATION[post.annotation.relation] : '投稿'}</Badge>
          <span className="text-dns-14B-130">{name}</span>
          <span className="text-solid-gray-600">{fmt(post.createdAt)}</span>
        </div>
        <p className="mt-1 whitespace-pre-wrap break-words text-std-16N-170">{post.text}</p>
      </button>
    </li>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[7rem_1fr] gap-2 border-b border-solid-gray-100 py-1.5">
      <dt className="text-dns-14B-130 text-solid-gray-600">{label}</dt>
      <dd className="min-w-0 break-all text-dns-14N-130">{children}</dd>
    </div>
  );
}

function Detail({ post, thread, status, now, onStatus, onSelect, onAuthor }: {
  post: Post;
  thread?: Thread;
  status: Status;
  now: number;
  onStatus: (s: Status) => void;
  onSelect: (uri: string) => void;
  onAuthor: (did: string) => void;
}) {
  const name = useHandle(post.did);
  const isRoot = thread?.post === post;
  return (
    <div className="space-y-4 p-4">
      <div>
        <div className="flex items-baseline justify-between gap-2">
          <h2 className="truncate text-std-18B-160">{name}</h2>
          <button type="button" onClick={() => onAuthor(post.did)} className="shrink-0 text-dns-14N-130 text-key-900 underline hover:decoration-2">この発信者の投稿</button>
        </div>
        <div className="mt-1 flex flex-wrap gap-1">
          {isRoot && thread.corrected && <Badge tone="red">訂正あり</Badge>}
          <PostBadges post={post} now={now} />
        </div>
      </div>
      {!isRoot && thread && (
        <button type="button" onClick={() => onSelect(thread.post.uri)} className="text-dns-14N-130 text-key-900 underline">← 対象の投稿を表示</button>
      )}
      {isRoot && thread.orphan && <p className="text-dns-14N-130 text-solid-gray-600">参照先の投稿は未取得です。</p>}
      <p className="whitespace-pre-wrap break-words text-std-17N-170">{post.text}</p>

      <fieldset>
        <legend className="mb-1 text-dns-14B-130">対応状況</legend>
        <div className="inline-flex overflow-hidden rounded-8 border border-key-900">
          {(Object.keys(STATUS) as Status[]).map(s => (
            <label key={s} className={`cursor-pointer px-3 py-1.5 text-dns-16N-130 has-focus-visible:ring-2 has-focus-visible:ring-focus-yellow ${status === s ? 'bg-key-900 text-white' : 'bg-white text-key-900 hover:bg-key-50'}`}>
              <input type="radio" name="status" className="sr-only" checked={status === s} onChange={() => onStatus(s)} />
              {STATUS[s]}
            </label>
          ))}
        </div>
      </fieldset>

      <dl>
        <Row label="作成日時">{post.createdAt}</Row>
        <Row label="索引日時">{post.indexedAt ?? '—'}</Row>
        <Row label="有効期間">{post.validity ? `${post.validity.from ?? '不明'} 〜 ${post.validity.until ?? '不明'}` : '—'}</Row>
        <Row label="発信地点">{post.origin ? post.origin.map(v => v.toFixed(6)).join(', ') : '—'}</Row>
        <Row label="影響範囲">{post.areas.length ? `${post.areas.length}件` : '—'}</Row>
        {post.unknownTypes.length > 0 && <Row label="未対応の拡張">{post.unknownTypes.join(', ')}</Row>}
        <Row label="発信者DID"><code>{post.did}</code></Row>
        <Row label="atURI"><code>{post.uri}</code></Row>
      </dl>

      {isRoot && thread.replies.length > 0 && (
        <section>
          <h3 className="mb-2 text-std-16B-170">訂正・補足・追認（{thread.replies.length}件）</h3>
          <ul className="space-y-2">
            {thread.replies.map(r => <Reply key={r.uri} post={r} onSelect={() => onSelect(r.uri)} />)}
          </ul>
        </section>
      )}
    </div>
  );
}

const field = 'rounded-8 border border-solid-gray-600 bg-white px-3 py-1.5 text-dns-16N-130';
const button = 'rounded-8 px-4 py-1.5 text-dns-16B-130';

export default function App() {
  const [did, setDid] = useUrlParam('did');
  const [selectedUri, setSelectedUri] = useUrlParam('uri');
  const [posts, setPosts] = useState<Post[]>([]);
  const [pending, setPending] = useState<Post[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [fetchedAt, setFetchedAt] = useState<number>();
  const [now, setNow] = useState(Date.now);
  const [statuses, setStatus] = useStatuses();
  const [keyword, setKeyword] = useState('');
  const [kind, setKind] = useState<'all' | 'mosimo' | 'bsaf'>('all');
  const [validity, setValidity] = useState<'all' | ValidityState>('all');
  const [inView, setInView] = useState(false);
  const [bounds, setBounds] = useState<LatLngBounds>();

  const known = useRef(new Set<string>());
  useEffect(() => { known.current = new Set([...posts, ...pending].map(p => p.uri)); }, [posts, pending]);

  const done = (ok: boolean) => {
    setError(ok ? undefined : '更新できませんでした。接続を確認してください（表示中の投稿は前回取得分です）');
    if (ok) setFetchedAt(Date.now());
    setNow(Date.now());
  };

  // First page, and polling: new arrivals wait behind the 新着 button so the list does not move under the operator.
  useEffect(() => {
    let live = true;
    setPosts([]); setPending([]); setCursor(undefined); setBusy(true);
    fetchFeed(APPVIEW, CLIENT_KEY, { did })
      .then(page => { if (live) { setPosts(page.posts); setCursor(page.cursor); done(true); } })
      .catch(() => live && done(false))
      .finally(() => live && setBusy(false));
    // ponytail: polling only adds arrivals; deletions in loaded pages show after a reload.
    const timer = setInterval(() => {
      fetchFeed(APPVIEW, CLIENT_KEY, { did })
        .then(page => {
          if (!live) return;
          const fresh = page.posts.filter(p => !known.current.has(p.uri));
          if (fresh.length) setPending(prev => merge(fresh, prev));
          done(true);
        })
        .catch(() => live && done(false));
    }, POLL_MS);
    return () => { live = false; clearInterval(timer); };
  }, [did]);

  // A shared ?uri= link may point past the loaded pages; fetch it on its own.
  useEffect(() => {
    if (!selectedUri || busy || known.current.has(selectedUri)) return;
    fetchFeed(APPVIEW, CLIENT_KEY, { uri: selectedUri }).then(page => setPosts(prev => merge(prev, page.posts))).catch(() => {});
  }, [selectedUri, busy]);

  const loadMore = () => {
    if (busy || !cursor) return;
    setBusy(true);
    // Offsets shift as new posts arrive; overlap is de-duplicated by URI.
    fetchFeed(APPVIEW, CLIENT_KEY, { did, cursor })
      .then(page => { setPosts(prev => merge(prev, page.posts)); setCursor(page.cursor); done(true); })
      .catch(() => done(false))
      .finally(() => setBusy(false));
  };

  const threads = useMemo(() => buildThreads(posts), [posts]);
  const shown = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    return threads.filter(({ post, replies }) =>
      (kind === 'all' || post.bsaf === (kind === 'bsaf')) &&
      (validity === 'all' || validityState(post.validity, now) === validity) &&
      (!q || [post, ...replies].some(p => p.text.toLowerCase().includes(q))) &&
      (!inView || !!(bounds && postBounds(post) && bounds.intersects(postBounds(post)!))));
  }, [threads, kind, validity, keyword, inView, bounds, now]);
  const shownCount = shown.reduce((n, t) => n + 1 + t.replies.length, 0);

  const selected = posts.find(p => p.uri === selectedUri);
  const selectedThread = selected && threads.find(t => t.post === selected || t.replies.includes(selected));
  const author = useHandle(did ?? '');

  return (
    <div className="flex min-h-dvh flex-col lg:h-dvh">
      <header className="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-solid-gray-200 bg-white px-4 py-3">
        <h1 className="text-std-20B-150">Mosimo 投稿確認ダッシュボード</h1>
        <p role="status" className={`text-dns-14N-130 ${error ? 'text-error-2' : 'text-solid-gray-600'}`}>
          {error ?? (fetchedAt ? `最終取得 ${new Date(fetchedAt).toLocaleTimeString('ja-JP')}` : '読み込み中…')}
        </p>
        {pending.length > 0 && (
          <button type="button" onClick={() => { setPosts(prev => merge(pending, prev)); setPending([]); }} className={`${button} ml-auto bg-key-900 text-white hover:bg-key-1000 hover:underline`}>
            新着 {pending.length}件を表示
          </button>
        )}
      </header>

      <form role="search" onSubmit={e => e.preventDefault()} className="flex flex-wrap items-end gap-3 border-b border-solid-gray-200 bg-solid-gray-50 px-4 py-2">
        <label className="flex flex-col gap-1 text-dns-14B-130">キーワード
          <input type="search" value={keyword} onChange={e => setKeyword(e.target.value)} className={field} />
        </label>
        <label className="flex flex-col gap-1 text-dns-14B-130">種類
          <select value={kind} onChange={e => setKind(e.target.value as typeof kind)} className={field}>
            <option value="all">すべて</option>
            <option value="mosimo">Mosimo</option>
            <option value="bsaf">BSAF</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-dns-14B-130">有効状態
          <select value={validity} onChange={e => setValidity(e.target.value as typeof validity)} className={field}>
            <option value="all">すべて</option>
            {(Object.keys(VALIDITY) as ValidityState[]).map(v => <option key={v} value={v}>{VALIDITY[v]}</option>)}
          </select>
        </label>
        <label className="flex items-center gap-2 py-1.5 text-dns-16N-130">
          <input type="checkbox" checked={inView} onChange={e => setInView(e.target.checked)} className="size-5 accent-key-900" />
          地図の表示範囲内のみ
        </label>
        {did && (
          <p className="flex items-center gap-2 py-1.5 text-dns-14N-130">
            発信者 <strong>{author}</strong> の投稿のみ
            <button type="button" onClick={() => { setDid(undefined); setSelectedUri(undefined); }} className="text-key-900 underline">解除</button>
          </p>
        )}
      </form>

      <main className="grid flex-1 grid-cols-1 lg:min-h-0 lg:grid-cols-[380px_1fr_420px]">
        <section aria-label="投稿一覧" className="flex max-h-[60vh] flex-col border-solid-gray-200 lg:max-h-none lg:min-h-0 lg:border-r">
          <p className="border-b border-solid-gray-200 px-4 py-2 text-dns-14N-130 text-solid-gray-600">
            読み込み済み{posts.length}件中{shownCount}件を表示
          </p>
          <ul className="min-h-0 flex-1 overflow-y-auto">
            {shown.map(t => (
              <ThreadItem key={t.post.uri} thread={t} now={now} status={statuses[t.post.uri] ?? 'todo'} selected={selectedThread === t} onSelect={() => setSelectedUri(t.post.uri)} />
            ))}
            {!busy && !shown.length && <li className="px-4 py-6 text-center text-dns-16N-130 text-solid-gray-600">該当する投稿はありません</li>}
            {cursor && (
              <li className="p-4 text-center">
                <button type="button" disabled={busy} onClick={loadMore} className={`${button} border border-key-900 text-key-900 hover:bg-key-50 disabled:opacity-50`}>さらに読み込む</button>
              </li>
            )}
          </ul>
        </section>

        <section aria-label="地図" className="h-[50vh] lg:h-auto">
          <MapView posts={shown.map(t => t.post)} selected={selected} onSelect={setSelectedUri} onBounds={setBounds} />
        </section>

        <section aria-label="投稿の詳細" className="border-solid-gray-200 lg:min-h-0 lg:overflow-y-auto lg:border-l">
          {selected ? (
            <Detail
              post={selected}
              thread={selectedThread}
              now={now}
              status={statuses[selected.uri] ?? 'todo'}
              onStatus={s => setStatus(selected.uri, s)}
              onSelect={setSelectedUri}
              onAuthor={d => { setSelectedUri(undefined); setDid(d); }}
            />
          ) : (
            <p className="p-4 text-dns-16N-130 text-solid-gray-600">一覧または地図から投稿を選択してください。</p>
          )}
        </section>
      </main>
    </div>
  );
}
