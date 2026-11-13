// dev.jisaku.mosimo.public.bsaf.hazard revision 1: BSAF tags of the pinned JMA and Kikikuru Bots to the common
// hazard extensions. Tables mirror protocol/mosimo/public/bsaf/hazard/README.md; anything not listed maps to nothing.
import { readFileSync } from 'node:fs';
import { parseTime } from '../../pds/attest.mjs';

export const RULE = 'dev.jisaku.mosimo.public.bsaf.hazard';
export const REVISION = '1';
export const HAZARD = 'dev.jisaku.mosimo.hazard';
export const JMA_BOT = 'did:plc:vwi7zhfyamyvrg5v6ycjgzxi';
export const KIKIKURU_BOT = 'did:plc:kxwz5cz7o6g4jlmxh6doyfsm';

const prefectures: Record<string, string[]> = JSON.parse(readFileSync(
  new URL('../../protocol/mosimo/public/bsaf/hazard/prefectures.json', import.meta.url), 'utf8'));

type Common = [severity: string, urgency: string, certainty: string, responseTypes?: string[]];
type Scale = { scheme: string; value: string; basis?: string };
interface Row { common: Common; scale?: Scale }
interface TypeRow { label: string; category: string; kind?: any; onset?: boolean; values: Record<string, Row>;
  overrides?: Record<string, Partial<{ urgency: string; certainty: string; responseTypes: string[] }>> }

const shindo = (severity: string): Row => ({ common: [severity, 'Past', 'Observed'] });
const jma: Record<string, TypeRow> = {
  earthquake: { label: '地震', category: 'Geo', kind: { $type: `${HAZARD}.earthquake`, kind: 'event' }, onset: true,
    values: { 1: shindo('Minor'), 2: shindo('Minor'), 3: shindo('Moderate'), 4: shindo('Moderate'), '5-': shindo('Severe'),
      '5+': shindo('Severe'), '6-': shindo('Extreme'), '6+': shindo('Extreme'), 7: shindo('Extreme') } },
  tsunami: { label: '津波', category: 'Geo', kind: { $type: `${HAZARD}.tsunami` }, values: {
    'special-warning': { common: ['Extreme', 'Immediate', 'Likely', ['Evacuate']], scale: { scheme: 'jp.jma.tsunami', value: 'major-warning' } },
    warning: { common: ['Severe', 'Immediate', 'Likely', ['Evacuate']], scale: { scheme: 'jp.jma.tsunami', value: 'warning' } },
    advisory: { common: ['Moderate', 'Expected', 'Likely', ['Avoid']], scale: { scheme: 'jp.jma.tsunami', value: 'advisory' } },
    info: { common: ['Minor', 'Unknown', 'Unknown'] } } },
  eruption: { label: '噴火', category: 'Geo', kind: { $type: `${HAZARD}.volcano`, phenomenon: 'eruption' }, values: {
    'special-warning': { common: ['Extreme', 'Immediate', 'Likely', ['Evacuate']] },
    warning: { common: ['Severe', 'Expected', 'Likely', ['Avoid']] },
    advisory: { common: ['Moderate', 'Future', 'Possible', ['Avoid']] } } },
  ashfall: { label: '降灰', category: 'Geo', kind: { $type: `${HAZARD}.volcano`, phenomenon: 'ashfall' }, values: {
    advisory: { common: ['Moderate', 'Expected', 'Likely', ['Avoid']] },
    info: { common: ['Minor', 'Future', 'Possible'] } } },
  'nankai-trough': { label: '南海トラフ臨時', category: 'Geo', kind: { $type: `${HAZARD}.earthquake`, kind: 'assessment' }, values: {
    'special-warning': { common: ['Extreme', 'Future', 'Possible', ['Prepare']], scale: { scheme: 'jp.jma.nankai-trough', value: 'alert' } },
    warning: { common: ['Severe', 'Future', 'Possible', ['Prepare']], scale: { scheme: 'jp.jma.nankai-trough', value: 'caution' } },
    advisory: { common: ['Moderate', 'Future', 'Possible', ['Monitor']] } } },
};
for (const value of Object.keys(jma.earthquake!.values)) jma.earthquake!.values[value]!.scale = { scheme: 'jp.jma.shindo', value, basis: 'observed' };

const level = (n: number, common: Common): Row => ({ common, scale: { scheme: 'jp.jma.warning-level', value: String(n) } });
const warningClass = (value: string, severity: string): Row => ({ common: [severity, 'Expected', 'Likely'], scale: { scheme: 'jp.jma.warning-class', value } });
const typhoon = (value: string, severity: string): Row => ({ common: [severity, 'Expected', 'Observed'], scale: { scheme: 'jp.jma.typhoon-intensity', value } });
const kikikuruValues: Record<string, Row> = {
  level2: level(2, ['Moderate', 'Expected', 'Likely', ['Prepare']]),
  level3: level(3, ['Severe', 'Expected', 'Likely', ['Prepare']]),
  level4: level(4, ['Extreme', 'Expected', 'Likely', ['Evacuate']]),
  level5: level(5, ['Extreme', 'Immediate', 'Likely', ['Shelter']]),
  advisory: warningClass('advisory', 'Moderate'),
  warning: warningClass('warning', 'Severe'),
  'special-warning': warningClass('special-warning', 'Extreme'),
  cancelled: { common: ['Minor', 'Past', 'Observed', ['AllClear']] },
  violent: typhoon('violent', 'Extreme'),
  'very-strong': typhoon('very-strong', 'Extreme'),
  strong: typhoon('strong', 'Severe'),
  'tropical-storm': typhoon('none', 'Moderate'),
  info: { common: ['Minor', 'Unknown', 'Unknown'] },
};
const weather = (label: string, phenomena: string[], category = 'Met', overrides?: TypeRow['overrides']): TypeRow =>
  ({ label, category, kind: { $type: `${HAZARD}.weather`, phenomena }, values: kikikuruValues, overrides });
const kikikuru: Record<string, TypeRow> = {
  'heavy-rain-warning': weather('大雨', ['rain']),
  'heavy-rain': weather('記録的短時間大雨', ['rain'], 'Met', { warning: { urgency: 'Immediate', certainty: 'Observed' } }),
  'landslide-warning': weather('土砂災害（警戒レベル情報・警戒情報）', ['landslide'], 'Geo'),
  'tornado-warning': weather('竜巻注意情報', ['tornado'], 'Met', { warning: { urgency: 'Immediate', responseTypes: ['Shelter'] } }),
  'storm-surge-warning': weather('高潮', ['storm-surge']),
  'wind-warning': weather('暴風・暴風雪・強風', ['wind']),
  'wave-warning': weather('波浪', ['wave']),
  'snow-warning': weather('大雪', ['snow']),
  'thunderstorm-warning': weather('雷', ['thunderstorm']),
  'melting-snow-warning': weather('融雪', ['snowmelt']),
  'dense-fog-warning': weather('濃霧', ['fog']),
  'dry-air-warning': weather('乾燥', ['dry-air']),
  'avalanche-warning': weather('なだれ', ['avalanche'], 'Geo'),
  'low-temperature-warning': weather('低温', ['cold']),
  'frost-warning': weather('霜', ['frost']),
  'icing-warning': weather('着氷', ['icing']),
  'snow-accretion-warning': weather('着雪', ['snow-accretion']),
  'flood-warning': weather('洪水（指定河川洪水予報）', ['flood']),
  'linear-rainband-warning': weather('線状降水帯', ['rain'], 'Met', { level5: { certainty: 'Observed' } }),
  'significant-snowfall-warning': weather('顕著な大雪', ['snow']),
  typhoon: { label: '台風', category: 'Met', kind: { $type: `${HAZARD}.cyclone` }, values: kikikuruValues },
  'weather-info': weather('気象情報（見通し・解説）', []),
  'other-warning': { label: 'その他', category: 'Met', values: kikikuruValues },
};
const bots: Record<string, Record<string, TypeRow>> = { [JMA_BOT]: jma, [KIKIKURU_BOT]: kikikuru };

const ja = (text: string) => ({ text, lang: 'ja' });
const isTimestamp = (value: unknown): value is string => { try { parseTime(value); return true; } catch { return false; } };
/** Exactly one value per core tag; anything else is not mapped. */
function tag(tags: string[], name: string) {
  const found = tags.filter(t => t.startsWith(`${name}:`));
  return found.length === 1 ? found[0]!.slice(name.length + 1) : undefined;
}
/** §5: whole-line matches only, against the pinned formatters' output. */
function lineMatch(lines: string[], pattern: RegExp) {
  for (const line of lines) { const m = pattern.exec(line); if (m) return m; }
  return null;
}
const tenths = (decimal: string) => Math.round(Number(decimal) * 10);

function enrich(botDid: string, type: string, kind: any, text: string, scales: Scale[], common: any) {
  const lines = text.split('\n');
  if (botDid === JMA_BOT && type === 'earthquake') {
    const hypocenter: any = {};
    const place = lineMatch(lines, /^\d{1,2}日\d{1,2}時\d{2}分ころ、(.{1,64})で地震$/u);
    if (place) hypocenter.name = ja(place[1]!);
    const mag = lineMatch(lines, /^マグニチュード：(\d{1,2}(?:\.\d)?)（(?:深さ約(\d{1,3})km|深さ不明)）$/u);
    if (mag && Number(mag[2]) > 0) hypocenter.depth = Number(mag[2]) * 1_000_000;
    if (Object.keys(hypocenter).length) kind.hypocenter = hypocenter;
    if (mag && Number(mag[1]) > 0) kind.magnitude = { value: tenths(mag[1]!) };
  } else if (botDid === JMA_BOT && type === 'tsunami') {
    const height = lineMatch(lines, /^予想される最大高さ：(.{1,32})$/u);
    if (height) {
      const meters = /^(\d{1,2}(?:\.\d)?)m$/.exec(height[1]!);
      kind.maxHeight = { ...(meters ? { value: Math.round(Number(meters[1]) * 1000) } : {}), text: ja(height[1]!), basis: 'forecast' };
    }
  } else if (botDid === JMA_BOT && type === 'eruption') {
    const flash = /^\S+ 噴火速報$/u.test(lines[0] ?? '');
    const erupted = flash ? /^(.{1,32})で噴火が発生$/u.exec(lines[1] ?? '') : null;
    const levelled = /^(.{1,32})（噴火警戒レベル([1-5])）$/u.exec(lines[1] ?? '');
    if (flash) { common.certainty = 'Observed'; common.urgency = 'Immediate'; }
    if (erupted) kind.volcano = { name: ja(erupted[1]!) };
    else if (levelled) {
      kind.volcano = { name: ja(levelled[1]!) };
      scales.push({ scheme: 'jp.jma.volcanic-alert-level', value: levelled[2]! });
    }
  } else if (botDid === KIKIKURU_BOT && type === 'typhoon') {
    const name = /^🌀 台風第\d{1,2}号（(.{1,32})）$/u.exec(lines[0] ?? '');
    if (name) kind.name = { text: name[1]!, lang: 'ja-Kana' };
    const pressure = lineMatch(lines, /^中心気圧：(\d{3,4})hPa$/u);
    if (pressure) kind.pressure = Number(pressure[1]) * 100;
    const wind = lineMatch(lines, /^最大風速：(\d{1,3})m\/s(?:（最大瞬間\d{1,3}m\/s）)?$/u);
    if (wind) kind.maxWind = Number(wind[1]) * 1000;
  }
}

export interface Provenance { uri: string; createdAt: unknown; retrievedFrom: string; retrievedAt: string; converter: string }

/**
 * The hazard, type-specific, area and source extensions for one BSAF post, or [] when the Bot/type/value is not in
 * the rule's tables (the post is then re-posted with its BSAF extension only).
 */
export function hazardExtensions(botDid: string, record: { text: string; tags: string[] }, provenance: Provenance): any[] {
  const row = bots[botDid]?.[tag(record.tags, 'type') ?? ''];
  const type = tag(record.tags, 'type')!;
  const value = tag(record.tags, 'value');
  const entry = value === undefined ? undefined : Object.hasOwn(row?.values ?? {}, value) ? row!.values[value] : undefined;
  if (!row || !entry) return [];
  const [severity, urgency, certainty, responseTypes] = entry.common;
  const common: any = { severity, urgency, certainty, ...(responseTypes ? { responseTypes } : {}),
    ...(row.overrides?.[value!] ?? {}) };
  const scales: Scale[] = entry.scale ? [{ ...entry.scale }] : [];
  const kind = row.kind ? structuredClone(row.kind) : undefined;
  if (kind) enrich(botDid, type, kind, record.text, scales, common);
  const time = tag(record.tags, 'time');
  const hazard = {
    $type: HAZARD, status: 'Actual', category: [row.category],
    severity: common.severity, urgency: common.urgency, certainty: common.certainty,
    ...(common.responseTypes ? { responseTypes: common.responseTypes } : {}),
    ...(row.onset && isTimestamp(time) ? { onset: time } : {}),
    event: ja(row.label),
    ...(scales.length ? { scales } : {}),
  };
  const out: any[] = [hazard];
  if (kind) out.push(kind);
  const target = tag(record.tags, 'target');
  // §2.3: only Kikikuru's per-prefecture posts carry a complete area; JMA Bot's target is just the first region.
  if (botDid === KIKIKURU_BOT && target !== undefined && Object.hasOwn(prefectures, target)) {
    out.push({ $type: 'dev.jisaku.mosimo.geo#area', regions: prefectures[target]!.map(hash => ({ shape: 'geohash', hash })), approximation: true });
  }
  out.push({
    $type: 'dev.jisaku.mosimo.public#source',
    document: { format: 'bsaf-v1', senderId: botDid, messageId: provenance.uri,
      ...(isTimestamp(provenance.createdAt) ? { sentAt: provenance.createdAt } : {}) },
    retrievedFrom: provenance.retrievedFrom, retrievedAt: provenance.retrievedAt, converter: provenance.converter,
    conversion: { rule: RULE, revision: REVISION },
  });
  return out;
}
