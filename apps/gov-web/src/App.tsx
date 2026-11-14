import type { LatLngBounds } from 'leaflet';
import { Component, useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import MapView, { insetFocus, postParts } from './MapView';
import { APPVIEW, bsafTag, buildThreads, CLIENT_KEY, RELAY, delayText, fetchFeed, JMA_BOT, KIKIKURU_BOT, resolveHandle, validityState, type Area, type Post, type Relation, type Thread, type ValidityState } from './mosimo';

const POLL_MS = 10_000;
// ponytail: a burst longer than this many pages per poll leaves a gap that さらに読み込む fills at the end of the list; a keyset cursor (AppView B21) would let polling resume.
const POLL_PAGES = 5;
const TITLE = document.title;

type Status = 'todo' | 'doing' | 'done';
type Tone = 'gray' | 'blue' | 'red' | 'orange' | 'green' | 'purple';
const STATUS: Record<Status, string> = { todo: '未対応', doing: '対応中', done: '対応済' };
const STATUS_TONE: Record<Status, Tone> = { todo: 'gray', doing: 'orange', done: 'green' };
const VALIDITY: Record<ValidityState, string> = { valid: '有効', pending: '開始前', expired: '期限切れ', unknown: '有効期間不明' };
const RELATION: Record<Relation, string> = { correction: '訂正', supplement: '補足', endorsement: '追認' };
// priority §1: level 1 is the default and 0 is bulk, so only important and urgent get a badge.
const PRIORITY: Record<number, string | undefined> = { 2: '重要', 3: '緊急' };
// hazard §2.1: anything but Actual, a missing status included, must not read as a real event.
// A Map, since the status is the author's string and a plain object would answer "__proto__" with Object.prototype.
const HAZARD_STATUS = new Map([['Exercise', '訓練'], ['Test', '試験'], ['Unknown', '状態不明']]);
const KIND: Record<string, string | undefined> = {
  origin: '発信地点', area: '影響範囲', validity: '有効期間', annotation: '注釈', priority: '優先度', hazard: '災害種別', bsaf: 'BSAF',
  connectivity: '投稿経路',
};
// public/bsaf §5.1, hazard §1: a `value:` means what its own bot defines for that `type:`, so only (bot, type, value)
// triples listed here get a label (hazard §3, §4; wording checked against the bots' own headings). Anything else gets none.
const pairs = (bot: string, types: string[], values: Record<string, string>) =>
  types.flatMap(t => Object.entries(values).map(([v, label]) => [`${bot} ${t} ${v}`, label] as const));
const BSAF_VALUE = new Map<string, string>([
  // §3.1: the largest intensity observed in the post.
  ...pairs(JMA_BOT, ['earthquake'], Object.fromEntries(['1', '2', '3', '4', '5-', '5+', '6-', '6+', '7'].map(v => [v, `最大震度${v.replace('-', '弱').replace('+', '強')}`]))),
  ...pairs(JMA_BOT, ['tsunami'], { 'special-warning': '大津波警報', warning: '津波警報', advisory: '津波注意報' }),
  ...pairs(JMA_BOT, ['nankai-trough'], { 'special-warning': '巨大地震警戒', warning: '巨大地震注意' }),
  // Kinds that only ever have a 注意報.
  ...pairs(KIKIKURU_BOT, ['thunderstorm-warning', 'dense-fog-warning', 'dry-air-warning', 'frost-warning', 'low-temperature-warning', 'snow-accretion-warning', 'avalanche-warning', 'melting-snow-warning', 'icing-warning'],
    { advisory: '注意報', cancelled: '解除' }),
  ...pairs(KIKIKURU_BOT, ['wind-warning', 'wave-warning', 'snow-warning'], { advisory: '注意報', warning: '警報', 'special-warning': '特別警報', cancelled: '解除' }),
  ...pairs(KIKIKURU_BOT, ['heavy-rain-warning', 'landslide-warning', 'flood-warning', 'storm-surge-warning'],
    { level2: 'レベル2', level3: 'レベル3', level4: 'レベル4', level5: 'レベル5', cancelled: '解除' }),
  // §4.3 and the headings: here `warning` is an information, not a 警報.
  ...pairs(KIKIKURU_BOT, ['landslide-warning'], { warning: '土砂災害警戒情報' }),
  ...pairs(KIKIKURU_BOT, ['tornado-warning'], { warning: '竜巻注意情報' }),
  ...pairs(KIKIKURU_BOT, ['heavy-rain'], { warning: '記録的短時間大雨情報' }),
  ...pairs(KIKIKURU_BOT, ['linear-rainband-warning'], { level4: '直前予測', level5: '発生' }),
  ...pairs(KIKIKURU_BOT, ['typhoon'], { violent: '猛烈な台風', 'very-strong': '非常に強い台風', strong: '強い台風' }),
]);
const bsafValue = (p: Post) => {
  const type = bsafTag(p.bsafTags, 'type'), value = bsafTag(p.bsafTags, 'value');
  return type && value ? BSAF_VALUE.get(`${p.did} ${type} ${value}`) : undefined;
};
const STATUS_KEY = 'mosimo-gov-web:status';

const merge = (a: Post[], b: Post[]) => {
  const seen = new Set(a.map(p => p.uri));
  return [...a, ...b.filter(p => !seen.has(p.uri))];
};
const short = (did: string) => (did.length > 24 ? did.slice(0, 20) + '…' : did);
const fmt = (s?: string, long = false) => {
  const t = Date.parse(s ?? '');
  return Number.isNaN(t) ? (s ?? '—') : new Date(t).toLocaleString('ja-JP', long
    ? { year: 'numeric', month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit' }
    : { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
};
const rtf = new Intl.RelativeTimeFormat('ja', { numeric: 'auto' });
const ago = (t: number, now: number) => {
  const m = Math.round((t - now) / 60000);
  return Math.abs(m) < 60 ? rtf.format(m, 'minute') : Math.abs(m) < 1440 ? rtf.format(Math.round(m / 60), 'hour') : rtf.format(Math.round(m / 1440), 'day');
};
const areaText = (a: Area) =>
  a.shape === 'rect' ? '区画' : `円（半径 ${a.radiusMeters >= 1000 ? `${+(a.radiusMeters / 1000).toFixed(1)} km` : `${Math.round(a.radiusMeters)} m`}）`;
// annotation §3: only the target's own author can correct it; anyone else's correction is a claim.
const relationText = (p: Post, target?: Post) =>
  p.repostOf ? '転載'
  : p.annotation?.relation === 'correction' && target?.did === p.did ? '著者による訂正'
  : p.annotation ? RELATION[p.annotation.relation] : '投稿';
const authorCorrections = (t: Thread) =>
  t.replies.filter(r => r.annotation?.relation === 'correction' && r.annotation.target === t.post.uri && r.did === t.post.did);

function useHandle(did: string) {
  const [handle, setHandle] = useState<{ did: string; value?: string }>();
  useEffect(() => {
    let live = true;
    resolveHandle(did).then(value => live && setHandle({ did, value }));
    return () => { live = false; };
  }, [did]);
  return (handle?.did === did && handle.value) || short(did);
}
// Without a verified handle the name is a DID; set it as an identifier so it reads as intended, not as a broken name.
const nameClass = (name: string, text: string) => (name.startsWith('@') ? text : 'font-mono text-dns-14N-130 text-solid-gray-600');

const readStatuses = (): Record<string, Status> => {
  try { return JSON.parse(localStorage.getItem(STATUS_KEY) ?? '{}') ?? {}; } catch { return {}; }
};
function useStatuses() {
  const [statuses, setStatuses] = useState(readStatuses);
  // Other tabs write the same key; follow them, and re-read before writing so a stale copy here does not erase their records.
  useEffect(() => {
    const sync = (e: StorageEvent) => { if (e.key === STATUS_KEY || e.key === null) setStatuses(prev => ({ ...prev, ...readStatuses() })); };
    addEventListener('storage', sync);
    return () => removeEventListener('storage', sync);
  }, []);
  const set = (uri: string, s: Status) => setStatuses(prev => {
    const next = { ...prev, ...readStatuses(), [uri]: s };
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

const Badge = ({ tone = 'gray', children }: { tone?: Tone; children: ReactNode }) => {
  const tones = {
    gray: 'border-solid-gray-420 text-solid-gray-800',
    blue: 'border-key-800 text-key-900',
    red: 'border-error-1 text-error-2',
    orange: 'border-warning-orange-1 text-warning-orange-2',
    green: 'border-success-1 text-success-2',
    purple: 'border-purple-700 text-purple-900',
  };
  return <span className={`inline-block rounded-4 border bg-white px-1.5 text-dns-14N-130 ${tones[tone]}`}>{children}</span>;
};

// Local time on screen; the original string stays in dateTime and the tooltip.
const Time = ({ at, long, empty = '—' }: { at?: string; long?: boolean; empty?: string }) =>
  at ? <time dateTime={at} title={at} className="whitespace-nowrap">{fmt(at, long)}</time> : <>{empty}</>;

// A record without its own creation time falls back to when the AppView received it, labelled as such.
const stamp = (p: Post) => [p.createdAt ? '' : '受信 ', p.createdAt ?? p.receivedAt] as const;
function When({ post, now, long }: { post: Post; now: number; long?: boolean }) {
  const [label, at] = stamp(post);
  const t = Date.parse(at ?? '');
  return <>{label}<Time at={at} long={long} />{t >= 0 && `（${ago(t, now)}）`}</>;
}

// Every post is unverified (no signature-chain check yet), so a claimed priority never gets the alert red (hazard §8, priority §2).
function PriorityBadge({ post }: { post: Post }) {
  const level = PRIORITY[post.priority ?? 1];
  return level ? <Badge tone="orange">申告優先度 {level}</Badge> : null;
}

function PostBadges({ post, now, target, list }: { post: Post; now: number; target?: Post; list?: boolean }) {
  const v = validityState(post.validity, now);
  const value = bsafValue(post);
  const delay = delayText(post);
  return (
    <>
      {post.hazardStatus && <Badge tone="orange">{HAZARD_STATUS.get(post.hazardStatus) ?? `状態 ${post.hazardStatus}`}</Badge>}
      {post.bsaf && <Badge tone="blue">BSAF</Badge>}
      {value && <Badge>{value}</Badge>}
      {(post.annotation || post.repostOf) && <Badge tone="blue">{relationText(post, target)}</Badge>}
      {v !== 'unknown' && <Badge tone={v === 'valid' ? 'green' : 'gray'}>{VALIDITY[v]}</Badge>}
      <PriorityBadge post={post} />
      {/* Direct is the usual path and stays plain; a mesh relay gets its own colour and a node icon so it stands out in the list. */}
      {post.online === true && <Badge>直接投稿</Badge>}
      {post.online === false && (
        <Badge tone="purple">
          <svg aria-hidden viewBox="0 0 16 16" className="mr-1 inline size-3.5 align-[-2px]" fill="currentColor">
            <path d="M3 12 8 4l5 8z" fill="none" stroke="currentColor" strokeWidth="1.5" />
            <circle cx="3" cy="12" r="2" /><circle cx="8" cy="4" r="2" /><circle cx="13" cy="12" r="2" />
          </svg>
          mesh経由で投稿
        </Badge>
      )}
      {delay && <Badge>{delay}</Badge>}
      {post.conflicts.length > 0 && <Badge tone="orange">複数指定</Badge>}
      {/* BSAF never carries coordinates, so its rows are not flagged; an unreadable area is not "none" either. */}
      {!post.origin && !post.areas.length && !post.areaIncomplete && !(list && (post.bsaf || post.bsafTags.length > 0)) && <Badge>位置情報なし</Badge>}
      {/* annotation §5.1: the unverified state, once per post in the detail. */}
      {!list && <Badge>身元未確認</Badge>}
    </>
  );
}

// One bad post must not blank the dashboard: each pane fails alone, and a new `reset` value retries it.
class Pane extends Component<{ reset?: string; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError = () => ({ failed: true });
  componentDidUpdate(prev: { reset?: string }) {
    if (this.state.failed && prev.reset !== this.props.reset) this.setState({ failed: false });
  }
  render() {
    return this.state.failed ? <p role="alert" className="p-4 text-dns-16N-130 text-error-2">この欄を表示できませんでした。再読み込みしてください。</p> : this.props.children;
  }
}

function ThreadItem({ thread, status, selected, tabStop, now, onSelect }: {
  thread: Thread; status: Status; selected: boolean; tabStop: boolean; now: number; onSelect: () => void;
}) {
  const { post } = thread;
  const name = useHandle(post.did);
  const id = useId();
  const ref = useRef<HTMLButtonElement>(null);
  // A selection made on the map or in the detail brings its row into view.
  useEffect(() => { if (selected) ref.current?.scrollIntoView({ block: 'nearest' }); }, [selected]);
  const dim = status === 'done' || validityState(post.validity, now) === 'expired';
  const correction = authorCorrections(thread)[0];
  const [label, at] = stamp(post);
  return (
    <li>
      <button
        ref={ref}
        type="button"
        data-uri={post.uri}
        tabIndex={tabStop ? 0 : -1}
        onClick={onSelect}
        aria-current={selected || undefined}
        aria-label={`${name} ${label}${fmt(at)} ${STATUS[status]}`}
        aria-describedby={`${id}t ${correction ? `${id}c ` : ''}${id}b`}
        className={`block w-full border-b border-solid-gray-200 px-4 py-3 text-left ${insetFocus} ${selected ? 'bg-key-50 shadow-[inset_4px_0_0] shadow-key-800' : dim ? 'bg-solid-gray-50 hover:bg-solid-gray-100' : 'hover:bg-solid-gray-50'}`}
      >
        <div className="flex items-baseline justify-between gap-2">
          <span className={`truncate ${nameClass(name, 'text-dns-16B-130')}`}>{name}</span>
          <span className="shrink-0 text-dns-14N-130 text-solid-gray-600"><When post={post} now={now} /></span>
        </div>
        <p id={`${id}t`} className={`mt-1 line-clamp-2 whitespace-pre-wrap break-words text-dns-16N-130 ${thread.corrected || dim ? 'text-solid-gray-600' : ''}`}>
          {post.text.replace(/\n{2,}/g, '\n')}
        </p>
        {correction && <p id={`${id}c`} className="mt-1 line-clamp-1 text-dns-14N-130"><strong className="text-error-2">著者による訂正</strong> {correction.text}</p>}
        <div id={`${id}b`} className="mt-1.5 flex flex-wrap gap-1 empty:hidden">
          {/* 未対応 is the default; only a recorded status earns a badge (the label above still names it). */}
          {status !== 'todo' && <Badge tone={STATUS_TONE[status]}>{STATUS[status]}</Badge>}
          {thread.corrected && <Badge tone="red">訂正あり</Badge>}
          {thread.endorsers > 0 && <Badge tone="blue">追認 {thread.endorsers}</Badge>}
          {thread.replies.length > 0 && <Badge>関連 {thread.replies.length}件</Badge>}
          <PostBadges post={post} now={now} list />
        </div>
      </button>
    </li>
  );
}

function Reply({ post, target, now, onSelect }: { post: Post; target?: Post; now: number; onSelect: () => void }) {
  const name = useHandle(post.did);
  return (
    <li>
      <button type="button" onClick={onSelect} className="block w-full rounded-8 border border-solid-gray-200 px-3 py-2 text-left hover:bg-solid-gray-50">
        <div className="flex flex-wrap items-baseline gap-2 text-dns-14N-130">
          <Badge tone="blue">{relationText(post, target)}</Badge>
          <Badge>身元未確認</Badge>
          <span className={nameClass(name, 'text-dns-14B-130')}>{name}</span>
          <span className="text-solid-gray-600"><When post={post} now={now} /></span>
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
  thread: Thread;
  status: Status;
  now: number;
  onStatus: (s: Status) => void;
  onSelect: (uri: string) => void;
  onAuthor: (did: string) => void;
}) {
  const name = useHandle(post.did);
  const isRoot = thread.post === post;
  const find = (uri?: string) => [thread.post, ...thread.replies].find(p => p.uri === uri);
  const target = find(post.annotation?.target ?? post.repostOf);
  const corrections = isRoot ? authorCorrections(thread) : [];
  return (
    <div className="space-y-4 p-4">
      <div>
        <div className="flex items-baseline justify-between gap-2">
          <h2 className={`truncate ${nameClass(name, 'text-std-18B-160')}`}>{name}</h2>
          <button type="button" onClick={() => onAuthor(post.did)} className="shrink-0 text-dns-14N-130 text-key-900 underline hover:decoration-2">この発信者の投稿</button>
        </div>
        {/* The list row's time, 受信 included when the record has no creation time. */}
        <p className="mt-0.5 text-dns-14N-130 text-solid-gray-600"><When post={post} now={now} long /></p>
        <div className="mt-1 flex flex-wrap gap-1">
          {isRoot && thread.corrected && <Badge tone="red">訂正あり</Badge>}
          <PostBadges post={post} now={now} target={target} />
        </div>
      </div>
      {!isRoot && (
        <button type="button" onClick={() => onSelect(target?.uri ?? thread.post.uri)} className="text-dns-14N-130 text-key-900 underline">← 対象の投稿を表示</button>
      )}
      {/* The author's own correction goes above the text it corrects, not at the bottom of the replies. */}
      {corrections.map(c => (
        <div key={c.uri} className="rounded-8 border-2 border-error-1 px-3 py-2">
          <p className="text-dns-14B-130 text-error-2">著者による訂正 <When post={c} now={now} long /></p>
          <p className="mt-1 whitespace-pre-wrap break-words text-std-16N-170">{c.text}</p>
        </div>
      ))}
      <p className={`whitespace-pre-wrap break-words text-std-17N-170 ${corrections.length ? 'text-solid-gray-600' : ''}`}>{post.text}</p>

      <fieldset>
        <legend className="mb-1 text-dns-14B-130">{isRoot ? '対応状況' : '元の投稿の対応状況'}</legend>
        <div className="inline-flex overflow-hidden rounded-8 border border-key-900">
          {(Object.keys(STATUS) as Status[]).map(s => (
            <label key={s} className={`cursor-pointer px-3 py-1.5 text-dns-16N-130 has-focus-visible:outline-4 has-focus-visible:-outline-offset-4 has-focus-visible:outline-black has-focus-visible:ring-6 has-focus-visible:ring-inset has-focus-visible:ring-focus-yellow ${status === s ? 'bg-key-900 text-white' : 'bg-white text-key-900 hover:bg-key-50'}`}>
              <input type="radio" name="status" className="sr-only" checked={status === s} onChange={() => onStatus(s)} />
              {STATUS[s]}
            </label>
          ))}
        </div>
      </fieldset>

      {/* Rows without a value are left out; the badges above already say what is missing. */}
      <dl className="empty:hidden">
        {post.validity && (
          <Row label="有効期間"><Time at={post.validity.startsAt} long empty="不明" /> 〜 <Time at={post.validity.endsAt} long empty="不明" /></Row>
        )}
        {post.origin && (
          <Row label="発信地点">
            {post.origin.map(v => v.toFixed(6)).join(', ')}{' '}
            <a href={`https://maps.gsi.go.jp/#16/${post.origin[0]}/${post.origin[1]}/`} target="_blank" rel="noreferrer" className="text-key-900 underline">地理院地図</a>
          </Row>
        )}
        {/* geo §4.3: a covering approximation (a BSAF prefecture's cells) is not the exact target, and says so. */}
        {post.areas.length > 0 && <Row label="影響範囲">{post.areaApprox ? '範囲' : post.areas.map(areaText).join('、')}</Row>}
      </dl>

      {isRoot && thread.replies.length > 0 && (
        <section>
          <h3 className="mb-2 text-std-16B-170">関連する投稿（{thread.replies.length}件）</h3>
          <ul className="space-y-2">
            {thread.replies.map(r => <Reply key={r.uri} post={r} target={find(r.annotation?.target ?? r.repostOf)} now={now} onSelect={() => onSelect(r.uri)} />)}
          </ul>
        </section>
      )}

      <details>
        <summary className="cursor-pointer text-dns-14B-130 text-solid-gray-600">技術情報</summary>
        <dl className="mt-1">
          {/* B6: the AppView's time is rewritten by backfill, so it stands in only for a missing creation time. */}
          {!post.createdAt && <Row label="受信日時"><Time at={post.receivedAt} long /></Row>}
          {post.conflicts.length > 0 && <Row label="複数指定">{post.conflicts.map(k => KIND[k] ?? k).join('、')}</Row>}
          {post.unknownTypes.length > 0 && <Row label="未対応の拡張">{post.unknownTypes.join(', ')}</Row>}
          {/* public/bsaf §3.1, §5.1: tags verbatim, uninterpreted. */}
          {post.bsafTags.length > 0 && (
            <Row label="BSAFタグ"><ul>{post.bsafTags.map((t, i) => <li key={i}><code>{t}</code></li>)}</ul></Row>
          )}
          {post.repostOf && <Row label="転載元"><code>{post.repostOf}</code></Row>}
          {post.annotation?.target && <Row label="参照先"><code>{post.annotation.target}</code></Row>}
          <Row label="発信者DID"><code>{post.did}</code></Row>
          <Row label="atURI"><code>{post.uri}</code></Row>
        </dl>
      </details>
    </div>
  );
}

const field = 'h-10 rounded-8 border border-solid-gray-600 bg-white px-3 text-dns-16N-130';
const button = 'rounded-8 px-4 py-1.5 text-dns-16B-130';
const secondary = `${button} border border-key-900 text-key-900 hover:bg-key-50 disabled:opacity-50`;

type Kind = 'all' | 'posts' | 'alerts';
// Remembered per browser so an operator who hides BSAF/official alerts keeps them hidden after reload.
function useKind(): [Kind, (k: Kind) => void] {
  const [kind, setKind] = useState<Kind>(() => {
    try {
      const v = localStorage.getItem('mosimo-gov-kind');
      return v === 'posts' || v === 'alerts' ? v : 'all';
    } catch {
      return 'all';
    }
  });
  return [kind, k => {
    setKind(k);
    try { localStorage.setItem('mosimo-gov-kind', k); } catch { /* storage unavailable */ }
  }];
}

export default function App() {
  const [did, setDid] = useUrlParam('did');
  const [selectedUri, setSelectedUri] = useUrlParam('uri');
  const [posts, setPosts] = useState<Post[]>([]);
  const [pending, setPending] = useState<Post[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const [fetchedAt, setFetchedAt] = useState<number>();
  const [now, setNow] = useState(Date.now);
  const [attempt, setAttempt] = useState(0);
  const [failedUris, setFailedUris] = useState<string[]>([]);
  const [statuses, setStatus] = useStatuses();
  const [keyword, setKeyword] = useState('');
  const [kind, setKind] = useKind();
  const [validity, setValidity] = useState<'all' | ValidityState>('all');
  const [statusFilter, setStatusFilter] = useState<'all' | Status>('all');
  const [inView, setInView] = useState(false);
  const [bounds, setBounds] = useState<LatLngBounds>();
  const listRef = useRef<HTMLUListElement>(null);
  const detailRef = useRef<HTMLElement>(null);

  const known = useRef(new Set<string>());
  useEffect(() => { known.current = new Set([...posts, ...pending].map(p => p.uri)); }, [posts, pending]);

  // Bumped whenever the feed restarts; a response that belongs to an older feed is dropped.
  const epoch = useRef(0);
  const current = () => { const e = epoch.current; return () => e === epoch.current; };

  const done = (ok: boolean) => {
    setError(!ok);
    if (ok) setFetchedAt(Date.now());
    setNow(Date.now());
  };

  // First page, and polling: new arrivals wait behind the 新着 button so the list does not move under the operator.
  useEffect(() => {
    const live = current();
    let loaded = false, inflight = false;
    setPosts([]); setPending([]); setCursor(undefined); setBusy(true);
    const first = () => fetchFeed(APPVIEW, CLIENT_KEY, { did }, RELAY)
      .then(page => {
        if (!live()) return;
        loaded = true;
        // A ?uri= post fetched meanwhile stays after the page.
        setPosts(prev => merge(page.posts, prev)); setCursor(page.cursor); done(true);
      })
      .finally(() => live() && setBusy(false));
    // Follow older pages until one reaches a post we already have, so a burst longer than a page is not skipped.
    const poll = async () => {
      let fresh: Post[] = [], next: string | undefined;
      for (let i = 0; i < POLL_PAGES; i++) {
        const page = await fetchFeed(APPVIEW, CLIENT_KEY, { did, cursor: next }, RELAY);
        if (!live()) return;
        const add = page.posts.filter(p => !known.current.has(p.uri));
        fresh = merge(fresh, add);
        if (add.length < page.posts.length || !page.cursor) break;
        next = page.cursor;
      }
      if (fresh.length) setPending(prev => merge(fresh, prev));
      done(true);
    };
    // Until a first page arrives, polling retries it, so a failed start still ends up with a cursor.
    const run = () => {
      if (inflight) return;
      inflight = true;
      (loaded ? poll() : first())
        .catch(() => live() && done(false))
        .finally(() => { inflight = false; });
    };
    run();
    // ponytail: polling only adds arrivals; deletions in loaded pages show after a reload.
    const timer = setInterval(run, POLL_MS);
    return () => { epoch.current++; clearInterval(timer); };
  }, [did, attempt]);

  // A shared ?uri= link may point past the loaded pages; fetch it on its own, and only once if it fails.
  useEffect(() => {
    if (!selectedUri || busy || known.current.has(selectedUri) || failedUris.includes(selectedUri)) return;
    const live = current();
    fetchFeed(APPVIEW, CLIENT_KEY, { uri: selectedUri }, RELAY)
      .then(page => {
        if (!live()) return;
        if (!page.posts.some(p => p.uri === selectedUri)) throw new Error('Not found');
        setPosts(prev => merge(prev, page.posts));
      })
      .catch(() => live() && setFailedUris(f => [...f, selectedUri]));
  }, [selectedUri, busy, failedUris]);

  const loadMore = () => {
    if (busy || !cursor) return;
    const live = current();
    setBusy(true);
    // Offsets shift as new posts arrive; overlap is de-duplicated by URI.
    fetchFeed(APPVIEW, CLIENT_KEY, { did, cursor }, RELAY)
      .then(page => { if (live()) { setPosts(prev => merge(prev, page.posts)); setCursor(page.cursor); done(true); } })
      .catch(() => live() && done(false))
      .finally(() => live() && setBusy(false));
  };

  useEffect(() => { document.title = (pending.length ? `(${pending.length}) ` : '') + TITLE; }, [pending.length]);

  const threads = useMemo(() => buildThreads(posts), [posts]);
  const threadOf = useMemo(() => new Map(threads.flatMap(t => [t.post, ...t.replies].map(p => [p.uri, t] as const))), [threads]);
  // Status belongs to the thread root alone: the root is the incident, its annotations are evidence for it (D6-A).
  // A record made on a post while it was a root stays with that post, so it does not mark someone else's incident.
  // hasOwn also drops a stored value that is not a Status.
  const statusOf = (t: Thread): Status => { const s = statuses[t.post.uri]; return Object.hasOwn(STATUS, s) ? s : 'todo'; };

  const { shown, counts } = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    const base = threads.filter(({ post, replies }) =>
      (kind === 'all' || post.alert === (kind === 'alerts')) &&
      (validity === 'all' || validityState(post.validity, now) === validity) &&
      (!q || [post, ...replies].some(p => p.text.toLowerCase().includes(q))));
    // Any post in a thread places it. A thread without geometry is neither in nor out of view, so it is excluded (geo §4.2).
    const geo = (t: Thread) => [t.post, ...t.replies].flatMap(postParts);
    const inBounds = (t: Thread) => !inView || (!!bounds && geo(t).some(b => bounds.intersects(b)));
    const counts: Record<Status, number> = { todo: 0, doing: 0, done: 0 };
    for (const t of base) if (inBounds(t)) counts[statusOf(t)]++;
    const byStatus = statusFilter === 'all' ? base : base.filter(t => statusOf(t) === statusFilter);
    return { shown: byStatus.filter(inBounds), counts };
  }, [threads, kind, validity, keyword, statusFilter, statuses, inView, bounds, now]);
  const filtering = !!keyword.trim() || kind !== 'all' || validity !== 'all' || statusFilter !== 'all' || inView;
  const clear = () => { setKeyword(''); setKind('all'); setValidity('all'); setStatusFilter('all'); setInView(false); };

  const selected = posts.find(p => p.uri === selectedUri);
  const selectedThread = selected && threadOf.get(selected.uri);
  const tabStop = selectedThread && shown.includes(selectedThread) ? selectedThread : shown[0];
  const author = useHandle(did ?? '');

  // Below lg the detail is stacked under the list, so a pick brings it into view.
  const pick = (uri: string) => {
    setSelectedUri(uri);
    if (!matchMedia('(width >= 64rem)').matches) requestAnimationFrame(() => detailRef.current?.scrollIntoView());
  };
  // Roving tabindex: the list is one tab stop, and ↑↓ move and select.
  const onListKey = (e: KeyboardEvent<HTMLUListElement>) => {
    const step = e.key === 'ArrowDown' ? 1 : e.key === 'ArrowUp' ? -1 : 0;
    const items = [...e.currentTarget.querySelectorAll<HTMLButtonElement>('button[data-uri]')];
    const i = items.indexOf(document.activeElement as HTMLButtonElement);
    if (!step || i < 0) return;
    e.preventDefault();
    const next = items[i + step];
    if (!next) return;
    next.focus();
    setSelectedUri(next.dataset.uri);
  };
  const showPending = () => {
    setPosts(prev => merge(pending, prev)); setPending([]);
    // The button goes away; land focus on the newest row rather than on body.
    requestAnimationFrame(() => listRef.current?.querySelector('button')?.focus());
  };

  return (
    <div className="flex min-h-dvh flex-col lg:h-dvh">
      <header className="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-solid-gray-200 bg-white px-4 py-3">
        <h1 className="text-std-20B-150">Mosimo 投稿確認ダッシュボード</h1>
        <p className="text-dns-14N-130 text-solid-gray-600">{fetchedAt && `最終更新 ${new Date(fetchedAt).toLocaleTimeString('ja-JP')}`}</p>
        {/* Live regions carry only errors and new arrivals; the clock above changes every poll and stays silent. */}
        <p role="alert" className="text-dns-14N-130 text-error-2">
          {error && (posts.length ? '更新できませんでした。接続を確認してください' : '投稿を取得できませんでした。接続を確認してください')}
        </p>
        <p role="status" className="sr-only">{pending.length > 0 && `新着 ${pending.length}件があります`}</p>
        {pending.length > 0 && (
          <button type="button" onClick={showPending} className={`${button} ml-auto bg-key-900 text-white hover:bg-key-1000 hover:underline`}>
            新着 {pending.length}件を表示
          </button>
        )}
      </header>

      <form role="search" onSubmit={e => e.preventDefault()} className="flex flex-wrap items-end gap-3 border-b border-solid-gray-200 bg-solid-gray-50 px-4 py-2">
        <label className="flex flex-col gap-2 text-dns-14B-130">キーワード
          <input type="search" value={keyword} onChange={e => setKeyword(e.target.value)} className={field} />
        </label>
        <label className="flex flex-col gap-2 text-dns-14B-130">種類
          <select value={kind} onChange={e => setKind(e.target.value as typeof kind)} className={field}>
            <option value="all">すべて</option>
            <option value="posts">警報・注意報を除く</option>
            <option value="alerts">警報・注意報のみ</option>
          </select>
        </label>
        <label className="flex flex-col gap-2 text-dns-14B-130">有効状態
          <select value={validity} onChange={e => setValidity(e.target.value as typeof validity)} className={field}>
            <option value="all">すべて</option>
            {(Object.keys(VALIDITY) as ValidityState[]).map(v => <option key={v} value={v}>{VALIDITY[v]}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-2 text-dns-14B-130">対応状況
          {/* min-w keeps the checkbox beside it still while the counts change width. */}
          <select value={statusFilter} onChange={e => setStatusFilter(e.target.value as typeof statusFilter)} className={`${field} min-w-40`}>
            <option value="all">すべて（{counts.todo + counts.doing + counts.done}）</option>
            {(Object.keys(STATUS) as Status[]).map(s => <option key={s} value={s}>{STATUS[s]}（{counts[s]}）</option>)}
          </select>
        </label>
        <label className="flex h-10 items-center gap-2 text-dns-16N-130">
          <input type="checkbox" checked={inView} onChange={e => setInView(e.target.checked)} className="size-5 accent-key-900" />
          地図の表示範囲内のみ
        </label>
        {did && (
          <p className="flex h-10 items-center gap-2 text-dns-14N-130">
            発信者 <strong>{author}</strong> の投稿のみ
            <button type="button" onClick={() => { setDid(undefined); setSelectedUri(undefined); }} className="text-key-900 underline">解除</button>
          </p>
        )}
      </form>

      <main className="grid flex-1 grid-cols-1 lg:min-h-0 lg:grid-cols-[380px_1fr_420px]">
        <section aria-label="投稿一覧" className="flex max-h-[60vh] flex-col border-solid-gray-200 lg:max-h-none lg:min-h-0 lg:border-r">
          <p role="status" className="border-b border-solid-gray-200 px-4 py-2 text-dns-14N-130 text-solid-gray-600 empty:hidden">{posts.length > 0 && `${shown.length}件`}</p>
          {/* No Leaflet here, so any change to what the list shows may retry it (R1 N1). */}
          <Pane reset={[keyword, kind, validity, statusFilter, inView, did, posts.length].join('|')}>
            <ul ref={listRef} onKeyDown={onListKey} className="min-h-0 flex-1 overflow-y-auto">
              {shown.map(t => (
                <ThreadItem key={t.post.uri} thread={t} now={now} status={statusOf(t)} selected={selectedThread === t} tabStop={tabStop === t} onSelect={() => pick(t.post.uri)} />
              ))}
              {!shown.length && (
                <li className="space-y-3 px-4 py-6 text-center text-dns-16N-130 text-solid-gray-600">
                  {busy && !posts.length ? <p>読み込み中…</p>
                    : error && !posts.length ? <><p>投稿を取得できませんでした</p><button type="button" onClick={() => setAttempt(n => n + 1)} className={secondary}>再試行</button></>
                    : !posts.length ? <p>投稿はありません</p>
                    : <><p>該当する投稿はありません</p>{filtering && <button type="button" onClick={clear} className={secondary}>条件をクリア</button>}</>}
                </li>
              )}
              {cursor && (
                <li className="p-4 text-center">
                  <button type="button" disabled={busy} onClick={loadMore} className={secondary}>さらに読み込む</button>
                </li>
              )}
            </ul>
          </Pane>
        </section>

        {/* DOM order list → detail → map so the stacked layout and Tab reach the detail first; lg puts the map back in the middle. */}
        <section ref={detailRef} aria-label="投稿の詳細" className="border-solid-gray-200 lg:order-last lg:min-h-0 lg:overflow-y-auto lg:border-l">
          <Pane reset={selectedUri}>
            {selected && selectedThread ? (
              <Detail
                post={selected}
                thread={selectedThread}
                now={now}
                status={statusOf(selectedThread)}
                onStatus={s => setStatus(selectedThread.post.uri, s)}
                onSelect={pick}
                onAuthor={d => { setSelectedUri(undefined); setDid(d); }}
              />
            ) : selectedUri && failedUris.includes(selectedUri) ? (
              <div className="space-y-3 p-4">
                <p role="alert" className="text-dns-16N-130 text-error-2">投稿を取得できませんでした。</p>
                <button type="button" onClick={() => setFailedUris(f => f.filter(u => u !== selectedUri))} className={secondary}>再試行</button>
              </div>
            ) : (
              <p className="p-4 text-dns-16N-130 text-solid-gray-600">{selectedUri ? '読み込み中…' : '一覧または地図から投稿を選択してください。'}</p>
            )}
          </Pane>
        </section>

        <section aria-label="地図" className="h-[50vh] lg:h-auto">
          <Pane reset={selectedUri}>
            <MapView
              posts={shown.flatMap(t => [t.post, ...t.replies])}
              selected={selected}
              inView={inView}
              onSelect={pick}
              onBounds={setBounds}
              meta={p => {
                const t = threadOf.get(p.uri);
                const s = t ? statusOf(t) : 'todo';
                return <><When post={p} now={now} /> {s !== 'todo' && <Badge tone={STATUS_TONE[s]}>{STATUS[s]}</Badge>} <PriorityBadge post={p} /></>;
              }}
            />
          </Pane>
        </section>
      </main>
    </div>
  );
}
