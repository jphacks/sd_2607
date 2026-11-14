// JMA XML messages to Mosimo post drafts: public.jma (common), public.jma.earthquake (震源・震度に関する情報),
// public.jma.weather §5.1 (気象警報・注意報（Ｒ０６）) and the common hazard extensions of public.jma.hazard revision 1.
// A draft is unsigned: the importer signs it, after checking that it fits one mesh payload.
import { NS, parseXml, child, children, text, attr, value, code, type Node } from './xml.mts';
import { cover, areaExtension } from './area.mts';
import { JMA_XML_FEED } from '../../pds/attest.mjs';

export const RULE = 'dev.jisaku.mosimo.public.jma.hazard';
export const REVISION = '1';
export const HAZARD = 'dev.jisaku.mosimo.hazard';
export const JMA_EARTHQUAKE = 'dev.jisaku.mosimo.public.jma.earthquake';
export const JMA_WEATHER = 'dev.jisaku.mosimo.public.jma.weather';
export const EARTHQUAKE_TITLE = '震源・震度に関する情報';
export const R06_PREFIX = '気象警報・注意報（Ｒ０６）（';

export interface Context { url: string; retrievedAt: string; digest: Uint8Array; converter: string }
export interface Draft { text: string; ext: any[]; priority: 0 | 1 | 2 | 3; part: string }
/** Decides whether a draft fits one mesh payload once signed (public.jma §6.4). */
export type Fits = (draft: Draft) => boolean;
export type Conversion =
  | { status: 'converted'; title: string; drafts: Draft[]; held: string[] }
  | { status: 'skipped'; title: string; reason: string };

const ja = (t: string) => ({ text: t, lang: 'ja' });
/** UTC Timestamp of a JMA date-time with offset; undefined when it does not parse. */
export function utc(s: string | undefined): string | undefined {
  if (!s || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(s)) return undefined;
  const ms = Date.parse(s);
  return Number.isFinite(ms) ? new Date(ms).toISOString().replace('.000Z', 'Z') : undefined;
}
/** 「14日15時38分」 from the original local date-time string, without time-zone conversion. */
function local(s: string | undefined) {
  const m = /^\d{4}-\d{2}-(\d{2})T(\d{2}):(\d{2})/.exec(s ?? '');
  return m ? `${Number(m[1])}日${Number(m[2])}時${m[3]}分` : s ?? '';
}
const optional = <K extends string, T>(key: K, v: T | undefined): { [P in K]?: Exclude<T, undefined> } =>
  (v === undefined || v === '' ? {} : { [key]: v }) as { [P in K]?: Exclude<T, undefined> };

function message(root: Node) {
  const control = child(root, NS.report, 'Control'), head = child(root, NS.head, 'Head');
  if (!control || !head) throw new Error('Missing Control/Head');
  const c = (name: string) => text(child(control, NS.report, name)) ?? '';
  const h = (name: string) => text(child(head, NS.head, name)) ?? '';
  const headline = text(child(child(head, NS.head, 'Headline'), NS.head, 'Text'));
  return {
    control: { title: c('Title'), dateTime: c('DateTime'), status: c('Status'), editorialOffice: c('EditorialOffice'), publishingOffice: c('PublishingOffice') },
    head: {
      title: h('Title'), reportDateTime: h('ReportDateTime'),
      ...optional('targetDateTime', value(child(head, NS.head, 'TargetDateTime'))),
      ...optional('validDateTime', value(child(head, NS.head, 'ValidDateTime'))),
      eventID: h('EventID'), infoType: h('InfoType'), serial: h('Serial'), infoKind: h('InfoKind'), infoKindVersion: h('InfoKindVersion'),
      ...optional('headline', headline),
    },
  };
}
type Message = ReturnType<typeof message>;

function source(msg: Message, ctx: Context) {
  return {
    $type: 'dev.jisaku.mosimo.public#source',
    document: { format: 'jma-xml-1.3', senderId: msg.control.publishingOffice, messageId: ctx.url,
      ...optional('sentAt', utc(msg.head.reportDateTime)), ...optional('revision', msg.head.serial) },
    retrievedFrom: ctx.url, retrievedAt: ctx.retrievedAt, converter: ctx.converter,
    conversion: { rule: RULE, revision: REVISION },
    originalDigest: { algorithm: 'sha-256', value: ctx.digest },
    // importer §1.2, public.jma §12: the feed re-post declaration; no origin, the type is the original Control/Title.
    feed: { id: JMA_XML_FEED, type: msg.control.title },
  };
}
const footer = (msg: Message) => `出典: 気象庁防災情報XML（${msg.control.publishingOffice} ${local(msg.head.reportDateTime)}発表）を転載サーバーが変換。気象庁の署名ではありません。`;
const infoMark = (msg: Message) => msg.head.infoType === '発表' ? '' : `【${msg.head.infoType}】`;

/** Converts one message, or says why it is not imported. Unknown titles and non-通常 status are skipped (public.jma §4). */
export function convert(bytes: Uint8Array, ctx: Context, fits: Fits): Conversion {
  const root = parseXml(bytes);
  if (root.namespaceURI !== NS.report || root.localName !== 'Report') throw new Error('Not a JMA Report');
  const msg = message(root);
  const title = msg.control.title;
  if (msg.control.status !== '通常') return { status: 'skipped', title, reason: `Control/Status ${msg.control.status}` };
  if (title === EARTHQUAKE_TITLE) return earthquake(root, msg, ctx, fits);
  if (title.startsWith(R06_PREFIX)) return weather(root, msg, ctx, fits);
  return { status: 'skipped', title, reason: 'Unsupported Control/Title' };
}

// ---- 震源・震度に関する情報 -------------------------------------------------------------------

const SHINDO: Record<string, [severity: string, priority: 1 | 2 | 3]> = {
  1: ['Minor', 1], 2: ['Minor', 1], 3: ['Moderate', 1], 4: ['Moderate', 1],
  '5-': ['Severe', 2], '5+': ['Severe', 2], '6-': ['Extreme', 3], '6+': ['Extreme', 3], 7: ['Extreme', 3],
};
const shindoLabel = (v: string) => ({ '5-': '5弱', '5+': '5強', '6-': '6弱', '6+': '6強' } as Record<string, string>)[v] ?? v;

function hypocenterOf(eq: Node) {
  const area = child(child(eq, NS.seismology, 'Hypocenter'), NS.seismology, 'Area');
  if (!area) return undefined;
  const s = (name: string) => text(child(area, NS.seismology, name));
  return {
    name: s('Name') ?? '', ...optional('code', code(child(area, NS.seismology, 'Code'))),
    coordinates: children(area, NS.element, 'Coordinate').map(value),
    ...optional('detailedName', s('DetailedName')), ...optional('detailedCode', code(child(area, NS.seismology, 'DetailedCode'))),
    ...optional('nameFromMark', s('NameFromMark')), ...optional('markCode', code(child(area, NS.seismology, 'MarkCode'))),
    ...optional('direction', s('Direction')), ...optional('distance', value(child(area, NS.seismology, 'Distance'))),
    ...optional('source', text(child(child(eq, NS.seismology, 'Hypocenter'), NS.seismology, 'Source'))),
  };
}
/** hazard.earthquake hypocenter from the decimal-degree ISO 6709 coordinate; shallow/unknown depth is omitted. */
function hazardHypocenter(h: ReturnType<typeof hypocenterOf>) {
  if (!h) return undefined;
  const out: any = { ...(h.name ? { name: ja(h.name) } : {}) };
  const decimal = h.coordinates.find(c => !c?.type || c.type === '震源位置（度）');
  const m = /^([+-]\d{1,2}\.\d+)([+-]\d{1,3}\.\d+)([+-]\d+)?\/$/.exec(decimal?.value ?? '');
  if (m) {
    out.lat = Math.round(Number(m[1]) * 1e6); out.lon = Math.round(Number(m[2]) * 1e6);
    if (m[3] !== undefined && Number(m[3]) < 0) out.depth = -Number(m[3]) * 1000;
  }
  return Object.keys(out).length ? out : undefined;
}

function earthquake(root: Node, msg: Message, ctx: Context, fits: Fits): Conversion {
  const body = child(root, NS.seismology, 'Body');
  if (!body) throw new Error('Missing seismology Body');
  const eqEl = child(body, NS.seismology, 'Earthquake');
  const hypocenter = eqEl ? hypocenterOf(eqEl) : undefined;
  const magnitude = value(child(eqEl, NS.element, 'Magnitude'));
  const earthquakeExt = eqEl ? {
    ...optional('originTime', value(child(eqEl, NS.seismology, 'OriginTime'))),
    ...optional('arrivalTime', value(child(eqEl, NS.seismology, 'ArrivalTime'))),
    ...optional('condition', text(child(eqEl, NS.seismology, 'Condition'))),
    ...optional('hypocenter', hypocenter), ...optional('magnitude', magnitude),
  } : undefined;
  const obsEl = child(child(body, NS.seismology, 'Intensity'), NS.seismology, 'Observation');
  const defs: Record<string, string> = {};
  for (const t of children(child(obsEl, NS.seismology, 'CodeDefine'), NS.seismology, 'Type')) defs[attr(t, 'xpath') ?? ''] = text(t)!;
  const s = (el: Node, name: string) => text(child(el, NS.seismology, name));
  const named = (el: Node, path: string) => ({ name: s(el, 'Name') ?? '', code: code(child(el, NS.seismology, 'Code'), defs[path])! });
  const prefs = children(obsEl, NS.seismology, 'Pref').map(p => ({
    ...named(p, 'Pref/Code'), ...optional('maxInt', s(p, 'MaxInt')), ...optional('revise', s(p, 'Revise')),
    areas: children(p, NS.seismology, 'Area').map(a => ({
      ...named(a, 'Pref/Area/Code'), ...optional('maxInt', s(a, 'MaxInt')), ...optional('revise', s(a, 'Revise')),
      cities: children(a, NS.seismology, 'City').map(c => ({
        ...named(c, 'Pref/Area/City/Code'), ...optional('maxInt', s(c, 'MaxInt')), ...optional('condition', s(c, 'Condition')),
        ...optional('revise', s(c, 'Revise')),
        stations: children(c, NS.seismology, 'IntensityStation').map(st => ({
          ...named(st, 'Pref/Area/City/IntensityStation/Code'), int: s(st, 'Int') ?? '', ...optional('revise', s(st, 'Revise')) })),
      })),
    })),
  }));
  const commentsEl = child(body, NS.seismology, 'Comments');
  const fixed = (name: string) => {
    const el = child(commentsEl, NS.seismology, name);
    return el ? { text: text(child(el, NS.seismology, 'Text')) ?? '', code: code(child(el, NS.seismology, 'Code'), attr(el, 'codeType'))! } : undefined;
  };
  const comments = commentsEl ? { ...optional('forecastComment', fixed('ForecastComment')), ...optional('varComment', fixed('VarComment')),
    ...optional('freeFormComment', text(child(commentsEl, NS.seismology, 'FreeFormComment'))),
    ...optional('uri', text(child(commentsEl, NS.seismology, 'URI'))) } : undefined;
  const bodyText = text(child(body, NS.seismology, 'Text'));
  const maxInt = s(obsEl, 'MaxInt');
  const cancelled = msg.head.infoType === '取消';
  const overview = prefs.length ? { prefectures: prefs.map(p => ({ name: p.name, code: p.code })) } : undefined;

  const quakeLines = () => {
    if (!hypocenter && !earthquakeExt?.originTime) return [];
    const where = hypocenter?.name ? `${hypocenter.name}で地震` : '地震';
    const depth = hypocenter?.coordinates.find(c => c?.description)?.description;
    return [`${local(earthquakeExt?.originTime?.value)}ころ、${where}`,
      ...(depth ? [`震源: ${depth}`] : []), ...(magnitude ? [`マグニチュード: ${magnitude.description ?? magnitude.value}`] : [])];
  };
  const commentLines = () => [comments?.forecastComment?.text, comments?.varComment?.text, comments?.freeFormComment].filter((t): t is string => !!t);
  const hazard = (scopeMax: string | undefined) => {
    const row = scopeMax === undefined ? undefined : SHINDO[scopeMax];
    const kind: any = { $type: `${HAZARD}.earthquake`, kind: 'event', ...optional('hypocenter', hazardHypocenter(hypocenter)) };
    const mag = magnitude && !magnitude.nil && /^\d+(?:\.\d+)?$/.test(magnitude.value) && Number(magnitude.value) > 0 ? magnitude : undefined;
    if (mag) kind.magnitude = { value: Math.round(Number(mag.value) * 10), ...optional('type', mag.type) };
    return [{
      $type: HAZARD, status: 'Actual', category: ['Geo'],
      severity: prefs.length ? row?.[0] ?? 'Unknown' : 'Unknown', urgency: 'Past', certainty: 'Observed',
      ...optional('onset', utc(earthquakeExt?.originTime?.value)), event: ja(msg.head.title),
      ...(row ? { scales: [{ scheme: 'jp.jma.shindo', value: scopeMax!, basis: 'observed' }] } : {}),
    }, kind];
  };
  const extension = (selection: any, observationPrefs: any[] | undefined, reduction: string[], withOverview: boolean) => ({
    $type: JMA_EARTHQUAKE, message: msg, selection,
    ...(withOverview && overview && !cancelled ? { nationalOverview: overview } : {}),
    ...(reduction.length ? { reduction: { omitted: reduction } } : {}),
    body: {
      ...(cancelled ? {} : optional('earthquake', earthquakeExt)),
      ...(observationPrefs ? { observation: { maxInt: maxInt ?? '', prefs: observationPrefs } } : {}),
      ...optional('text', bodyText), ...(cancelled ? {} : optional('comments', comments)),
    },
  });

  // 取消 and messages without a domestic observation are one whole post (public.jma.earthquake §3.1).
  if (cancelled || !prefs.length) {
    const lines = [`${infoMark(msg)}【気象庁 ${title(msg)}】`, ...(cancelled ? [bodyText ?? 'この情報は取り消されました。'] : [...quakeLines(), ...commentLines()]), footer(msg)];
    const ext = [extension({ kind: 'whole' }, undefined, [], false), ...(cancelled ? [] : hazard(undefined)), source(msg, ctx)];
    const draft: Draft = { text: lines.join('\n'), ext, priority: 1, part: 'whole' };
    return fits(draft) ? { status: 'converted', title: msg.control.title, drafts: [draft], held: [] } : { status: 'converted', title: msg.control.title, drafts: [], held: ['whole'] };
  }

  const drafts: Draft[] = [], held: string[] = [];
  const build = (pi: number, ai: number | undefined, reduction: string[], withOverview: boolean): Draft => {
    const pref = prefs[pi]!;
    const areas = ai === undefined ? pref.areas : [pref.areas[ai]!];
    const selected = { ...pref, areas: reduction.includes('stationDetails') ? areas.map(a => ({ ...a, cities: a.cities.map(({ stations: _, ...c }) => c) })) : areas };
    const scopeMax = ai === undefined ? pref.maxInt : areas[0]!.maxInt;
    const scopeName = ai === undefined ? pref.name : `${pref.name} ${areas[0]!.name}`;
    const byInt = new Map<string, string[]>();
    for (const a of areas) { const k = a.maxInt ?? '不明（未入電を含む）'; byInt.set(k, [...(byInt.get(k) ?? []), a.name]); }
    const order = ['7', '6+', '6-', '5+', '5-', '4', '3', '2', '1'];
    const intLines = [...byInt].sort(([a], [b]) => (order.indexOf(a) + 99) % 99 - (order.indexOf(b) + 99) % 99)
      .map(([k, names]) => `震度${shindoLabel(k)}: ${names.join('、')}`);
    const unreceived = areas.some(a => a.cities.some(c => c.condition)) ? ['震度5弱以上と考えられるが震度を入手していない市町村があります。'] : [];
    const omittedNote = [
      ...(reduction.includes('stationDetails') ? ['容量上限のため、個別の震度観測点の詳細を省略。市町村・地域の最大震度と条件は保持。'] : []),
      ...(reduction.includes('nationalOverview') ? ['容量上限のため、全国の掲載都道府県一覧を省略。'] : []),
    ];
    const lines = [`${infoMark(msg)}【気象庁 ${title(msg)}】${scopeName}`, ...quakeLines(),
      `全国の最大震度: ${shindoLabel(maxInt ?? '不明')}`, `${scopeName}の震度（この投稿の範囲）:`, ...intLines, ...unreceived,
      ...(withOverview && overview ? [`震度を観測した都道府県等（全国）: ${overview.prefectures.map(p => p.name).join('、')}`] : []),
      ...commentLines(), ...omittedNote, footer(msg)];
    const cells = cover('quakeArea', areas.map(a => a.code.value));
    const ext = [extension({ kind: 'region', path: ai === undefined ? [pi] : [pi, ai] }, [selected], reduction, withOverview),
      ...hazard(scopeMax), ...(cells ? [areaExtension(cells)] : []), source(msg, ctx)];
    return { text: lines.join('\n'), ext, priority: SHINDO[scopeMax ?? '']?.[1] ?? 1, part: ai === undefined ? `pref:${pref.code.value}` : `area:${pref.code.value}/${areas[0]!.code.value}` };
  };
  // §4: Pref without omission; else each Area without omission; else omit categories in order, re-measuring each time.
  const attempt = (pi: number, ai: number | undefined) => {
    for (const [reduction, overviewKept] of [[[], true], [['stationDetails'], true], [['stationDetails', 'nationalOverview'], false]] as [string[], boolean][]) {
      if (ai === undefined && reduction.length) return undefined;
      const d = build(pi, ai, reduction, overviewKept);
      if (fits(d)) return d;
    }
    return undefined;
  };
  prefs.forEach((pref, pi) => {
    const whole = attempt(pi, undefined);
    if (whole) { drafts.push(whole); return; }
    pref.areas.forEach((_, ai) => { const d = attempt(pi, ai); if (d) drafts.push(d); else held.push(`area:${pref.code.value}/${pref.areas[ai]!.code.value}`); });
  });
  return { status: 'converted', title: msg.control.title, drafts, held };
}
const title = (msg: Message) => msg.control.title;

// ---- 気象警報・注意報（Ｒ０６） ------------------------------------------------------------------

type Common = [severity: string, urgency: string, certainty: string, responseTypes?: string[]];
interface Row { name: string; phenomena: string[]; rank: number; common: Common; scale?: { scheme: string; value: string } }
const level = (n: number, common: Common) => ({ common, rank: n === 5 ? 6 : n + 1, scale: { scheme: 'jp.jma.warning-level', value: String(n) } });
const LEVEL: Record<number, ReturnType<typeof level>> = {
  2: level(2, ['Moderate', 'Expected', 'Likely', ['Prepare']]), 3: level(3, ['Severe', 'Expected', 'Likely', ['Prepare']]),
  4: level(4, ['Extreme', 'Expected', 'Likely', ['Evacuate']]), 5: level(5, ['Extreme', 'Immediate', 'Likely', ['Shelter']]),
};
const CLASS = {
  advisory: { common: ['Moderate', 'Expected', 'Likely'] as Common, rank: 3, scale: { scheme: 'jp.jma.warning-class', value: 'advisory' } },
  warning: { common: ['Severe', 'Expected', 'Likely'] as Common, rank: 4, scale: { scheme: 'jp.jma.warning-class', value: 'warning' } },
  'special-warning': { common: ['Extreme', 'Expected', 'Likely'] as Common, rank: 6, scale: { scheme: 'jp.jma.warning-class', value: 'special-warning' } },
};
const ALL_CLEAR: Common = ['Minor', 'Past', 'Observed', ['AllClear']];
// public.jma.hazard §3.1: a Kind maps only when both Code and Name match a row. Earlier rows win ties.
const ROWS: Record<string, Row> = Object.fromEntries(([
  ['10', 'レベル２大雨注意報', ['rain'], LEVEL[2]], ['03', 'レベル３大雨警報', ['rain'], LEVEL[3]],
  ['43', 'レベル４大雨危険警報', ['rain'], LEVEL[4]], ['33', 'レベル５大雨特別警報', ['rain'], LEVEL[5]],
  ['29', 'レベル２土砂災害注意報', ['landslide'], LEVEL[2]], ['09', 'レベル３土砂災害警報', ['landslide'], LEVEL[3]],
  ['49', 'レベル４土砂災害危険警報', ['landslide'], LEVEL[4]], ['39', 'レベル５土砂災害特別警報', ['landslide'], LEVEL[5]],
  ['19', 'レベル２高潮注意報', ['storm-surge'], LEVEL[2]], ['08', 'レベル３高潮警報', ['storm-surge'], LEVEL[3]],
  ['48', 'レベル４高潮危険警報', ['storm-surge'], LEVEL[4]], ['38', 'レベル５高潮特別警報', ['storm-surge'], LEVEL[5]],
  ['15', '強風注意報', ['wind'], CLASS.advisory], ['05', '暴風警報', ['wind'], CLASS.warning], ['35', '暴風特別警報', ['wind'], CLASS['special-warning']],
  ['13', '風雪注意報', ['wind', 'snow'], CLASS.advisory], ['02', '暴風雪警報', ['wind', 'snow'], CLASS.warning],
  ['32', '暴風雪特別警報', ['wind', 'snow'], CLASS['special-warning']],
  ['16', '波浪注意報', ['wave'], CLASS.advisory], ['07', '波浪警報', ['wave'], CLASS.warning], ['37', '波浪特別警報', ['wave'], CLASS['special-warning']],
  ['12', '大雪注意報', ['snow'], CLASS.advisory], ['06', '大雪警報', ['snow'], CLASS.warning], ['36', '大雪特別警報', ['snow'], CLASS['special-warning']],
  ['14', '雷注意報', ['thunderstorm'], CLASS.advisory], ['17', '融雪注意報', ['snowmelt'], CLASS.advisory],
  ['20', '濃霧注意報', ['fog'], CLASS.advisory], ['21', '乾燥注意報', ['dry-air'], CLASS.advisory],
  ['22', 'なだれ注意報', ['avalanche'], CLASS.advisory], ['23', '低温注意報', ['cold'], CLASS.advisory],
  ['24', '霜注意報', ['frost'], CLASS.advisory], ['25', '着氷注意報', ['icing'], CLASS.advisory], ['26', '着雪注意報', ['snow-accretion'], CLASS.advisory],
] as [string, string, string[], { common: Common; rank: number; scale?: any }][]).map(([c, name, phenomena, r], i) =>
  [c, { name, phenomena, ...r, rank: r.rank * 100 - i }]));
const rowOf = (k: { name?: string; code?: { value: string } }) => { const r = ROWS[k.code?.value ?? '']; return r && r.name === k.name ? r : undefined; };
const rank = (name: string) => Object.values(ROWS).find(r => r.name === name)?.rank ?? 0;
const INACTIVE = new Set(['解除', '発表警報・注意報はなし']);
const PREFECTURE_TYPE = '気象警報・注意報（府県予報区等）';
const CITY_TYPE = '気象警報・注意報（市町村等）';

function weather(root: Node, msg: Message, ctx: Context, fits: Fits): Conversion {
  const body = child(root, NS.meteorology, 'Body');
  if (!body) throw new Error('Missing meteorology Body');
  const m = (el: Node, name: string) => text(child(el, NS.meteorology, name));
  const kindOf = (k: Node) => {
    const last = child(k, NS.meteorology, 'LastKind');
    const properties = children(k, NS.meteorology, 'Property').map((p, propertyIndex) => {
      const sig = child(child(child(p, NS.meteorology, 'SignificancyPart'), NS.meteorology, 'Base'), NS.meteorology, 'Significancy');
      return sig ? { propertyIndex, type: m(p, 'Type') ?? '', quantities: [{ element: 'Significancy', ...optional('type', attr(sig, 'type')),
        ...optional('name', m(sig, 'Name')), ...optional('code', code(child(sig, NS.meteorology, 'Code'))) }] } : undefined;
    }).filter(p => p !== undefined);
    return {
      ...optional('name', m(k, 'Name')), ...optional('code', code(child(k, NS.meteorology, 'Code'))),
      ...optional('condition', m(k, 'Condition')), ...optional('status', m(k, 'Status')),
      ...(last ? { lastKind: { ...optional('name', m(last, 'Name')), ...optional('code', code(child(last, NS.meteorology, 'Code'))) } } : {}),
      ...(properties.length ? { properties } : {}),
    };
  };
  const areaOf = (a: Node, codeType?: string) => ({ name: m(a, 'Name') ?? '', ...optional('code', code(child(a, NS.meteorology, 'Code'))), ...optional('codeType', codeType) });
  const warnings: any[] = [];
  children(body, NS.meteorology, 'Warning').forEach((w, warningIndex) => {
    const warningType = attr(w, 'type');
    if (warningType !== PREFECTURE_TYPE && warningType !== CITY_TYPE) return;
    children(w, NS.meteorology, 'Item').forEach((item, itemIndex) => {
      const areasEl = child(item, NS.meteorology, 'Areas');
      const areas = areasEl ? children(areasEl, NS.meteorology, 'Area').map(a => areaOf(a, attr(areasEl, 'codeType'))) : children(item, NS.meteorology, 'Area').map(a => areaOf(a));
      warnings.push({ warningIndex, itemIndex, warningType, kinds: children(item, NS.meteorology, 'Kind').map(kindOf), areas,
        ...optional('changeStatus', m(item, 'ChangeStatus')), ...optional('fullStatus', m(item, 'FullStatus')), ...optional('editingMark', m(item, 'EditingMark')) });
    });
  });
  const headlineItems: any[] = [];
  children(child(child(root, NS.head, 'Head'), NS.head, 'Headline'), NS.head, 'Information').forEach((info, informationIndex) => {
    if (attr(info, 'type') !== PREFECTURE_TYPE) return;
    children(info, NS.head, 'Item').forEach((item, itemIndex) => {
      const h = (el: Node, name: string) => text(child(el, NS.head, name));
      const areasEl = child(item, NS.head, 'Areas');
      headlineItems.push({ informationIndex, itemIndex, informationType: PREFECTURE_TYPE,
        kinds: children(item, NS.head, 'Kind').map(k => ({ ...optional('name', h(k, 'Name')), ...optional('code', code(child(k, NS.head, 'Code'))),
          ...optional('condition', h(k, 'Condition')) })),
        areas: children(areasEl, NS.head, 'Area').map(a => ({ name: h(a, 'Name') ?? '', ...optional('code', code(child(a, NS.head, 'Code'))),
          ...optional('codeType', attr(areasEl, 'codeType')) })) });
    });
  });
  const prefecture = warnings.find(w => w.warningType === PREFECTURE_TYPE)?.areas[0];
  const cities = warnings.filter(w => w.warningType === CITY_TYPE);
  if (!prefecture?.code || !cities.length) return { status: 'skipped', title: msg.control.title, reason: 'No 府県予報区等/市町村等 items' };

  // §5.1/§3.1: active kinds per city, and the cities whose kinds were lifted in this message.
  const active = new Map<string, { name: string; cities: string[] }>(), lifted = new Map<string, { name: string; cities: string[] }>();
  const activeCodes = new Set<string>(), liftedCodes = new Set<string>();
  let best: Row | undefined; const phenomena = new Set<string>(), liftedPhenomena = new Set<string>();
  let unmapped = false;
  for (const item of cities) {
    const city = item.areas[0]; if (!city) continue;
    for (const k of item.kinds) {
      if (k.status === '解除') {
        const name = k.lastKind?.name ?? k.name ?? '警報・注意報';
        lifted.set(name, { name, cities: [...(lifted.get(name)?.cities ?? []), city.name] });
        if (city.code) liftedCodes.add(city.code.value);
        rowOf(k.lastKind ?? {})?.phenomena.forEach(p => liftedPhenomena.add(p));
      } else if (!INACTIVE.has(k.status ?? '') && k.name) {
        active.set(k.name, { name: k.name, cities: [...(active.get(k.name)?.cities ?? []), city.name] });
        if (city.code) activeCodes.add(city.code.value);
        const row = rowOf(k);
        if (!row) { unmapped = true; continue; }
        row.phenomena.forEach(p => phenomena.add(p));
        if (!best || row.rank > best.rank) best = row;
      }
    }
  }
  const group = msg.control.title.slice(R06_PREFIX.length, -1);
  const lines = [`${infoMark(msg)}【気象庁 気象警報・注意報（${group}）】${prefecture.name} ${local(msg.head.reportDateTime)}発表`,
    ...(msg.head.headline ? [msg.head.headline] : []),
    ...(active.size ? ['発表中:', ...[...active.values()].sort((a, b) => (rank(b.name) - rank(a.name)))
      .map(a => `${a.name}: ${a.cities.join('、')}`)]
      : [`${prefecture.name}では、${group}の警報・注意報は現在発表されていません。`]),
    ...(lifted.size ? ['今回解除:', ...[...lifted.values()].map(a => `${a.name}: ${a.cities.join('、')}`)] : []),
    footer(msg)];
  const limitations = ['一次細分区域等・市町村等をまとめた地域等のItemは省略（市町村等のItemに同じ状態を保持）',
    '見出しのItemは府県予報区等のみ保持', 'Kind/PropertyはSignificancyPartのみ構造化、量的予想時系列（TimeSeriesInfo）は未構造化'];
  const notice = m(body, 'Notice');
  const extension = { $type: JMA_WEATHER, message: msg, selection: { kind: 'whole' }, headlineItems, warnings, meteorologicalItems: [],
    ...optional('notice', notice), limitations };
  const hazard: any[] = [];
  if (best) {
    const [severity, urgency, certainty, responseTypes] = best.common;
    hazard.push({ $type: HAZARD, status: 'Actual', category: [phenomena.has('landslide') || phenomena.has('avalanche') ? 'Geo' : 'Met'],
      severity, urgency, certainty, ...(responseTypes ? { responseTypes } : {}), event: ja(msg.head.title),
      ...(best.scale ? { scales: [best.scale] } : {}) }, { $type: `${HAZARD}.weather`, phenomena: [...phenomena].sort().slice(0, 8) });
  } else if (!active.size && lifted.size) {
    const [severity, urgency, certainty, responseTypes] = ALL_CLEAR;
    hazard.push({ $type: HAZARD, status: 'Actual', category: ['Met'], severity, urgency, certainty, responseTypes, event: ja(msg.head.title) },
      { $type: `${HAZARD}.weather`, phenomena: [...liftedPhenomena].sort().slice(0, 8) });
  }
  // Active kinds that no row maps leave the post without a severity rather than with a wrong one.
  if (unmapped && !best) hazard.length = 0;
  const codes = activeCodes.size ? activeCodes : liftedCodes;
  const cells = codes.size ? cover('warningCity', codes) : null;
  const level = best?.scale?.scheme === 'jp.jma.warning-level' ? Number(best.scale.value) : 0;
  const draft: Draft = { text: lines.join('\n'), ext: [extension, ...hazard, ...(cells ? [areaExtension(cells)] : []), source(msg, ctx)],
    priority: level >= 5 || best?.scale?.value === 'special-warning' ? 3 : level === 4 ? 2 : 1, part: `pref:${prefecture.code.value}` };
  return fits(draft) ? { status: 'converted', title: msg.control.title, drafts: [draft], held: [] }
    : { status: 'converted', title: msg.control.title, drafts: [], held: [draft.part] };
}
