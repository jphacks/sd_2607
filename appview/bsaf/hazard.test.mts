import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { canonical } from '../../pds/attest.mjs';
import { hazardExtensions, JMA_BOT, KIKIKURU_BOT, HAZARD } from './hazard.mts';

const provenance = { uri: `at://${JMA_BOT}/app.bsky.feed.post/3abc`, createdAt: '2026-10-18T03:00:05.123Z',
  retrievedFrom: 'wss://jetstream.invalid/subscribe', retrievedAt: '2026-10-18T03:00:06.000Z', converter: 'did:plc:importerimporterimporter' };
const tags = (type: string, value: string, target = 'jp-kanto', time = '2026-10-18T03:00:00.000Z') =>
  ['bsaf:v1', `type:${type}`, `value:${value}`, `time:${time}`, `target:${target}`, 'source:jma'];
const of = (exts: any[], type: string) => exts.find(e => e.$type === type);
const prefectures = JSON.parse(readFileSync(new URL('../../protocol/mosimo/public/bsaf/hazard/prefectures.json', import.meta.url), 'utf8'));

test('JMA Bot earthquake: observed intensity, origin time and the pinned formatter lines', () => {
  const text = '🔴 地震情報\n18日12時00分ころ、石川県能登地方で地震\nマグニチュード：6.5（深さ約10km）\n最大震度：5強\nこの地震による津波の心配はありません。\n（出典：気象庁）';
  const exts = hazardExtensions(JMA_BOT, { text, tags: tags('earthquake', '5+') }, provenance);
  assert.deepEqual(of(exts, HAZARD), { $type: HAZARD, status: 'Actual', category: ['Geo'], severity: 'Severe', urgency: 'Past',
    certainty: 'Observed', onset: '2026-10-18T03:00:00.000Z', event: { text: '地震', lang: 'ja' },
    scales: [{ scheme: 'jp.jma.shindo', value: '5+', basis: 'observed' }] });
  assert.deepEqual(of(exts, `${HAZARD}.earthquake`), { $type: `${HAZARD}.earthquake`, kind: 'event',
    hypocenter: { name: { text: '石川県能登地方', lang: 'ja' }, depth: 10_000_000 }, magnitude: { value: 65 } });
  // JMA Bot's target is only the first region: never an area (§2.3).
  assert.equal(of(exts, 'dev.jisaku.mosimo.geo#area'), undefined);
  assert.deepEqual(of(exts, 'dev.jisaku.mosimo.public#source').document,
    { format: 'bsaf-v1', senderId: JMA_BOT, messageId: provenance.uri, sentAt: provenance.createdAt });
  for (const e of exts) { const { $type, ...fields } = e; canonical(fields); }
});

test('unknown magnitude/depth (0, 深さ不明) and lines that do not match exactly are omitted, not guessed', () => {
  const unknown = hazardExtensions(JMA_BOT, { text: '18日12時00分ころ、石川県能登地方で地震\nマグニチュード：0（深さ不明）', tags: tags('earthquake', '3') }, provenance);
  assert.deepEqual(of(unknown, `${HAZARD}.earthquake`), { $type: `${HAZARD}.earthquake`, kind: 'event', hypocenter: { name: { text: '石川県能登地方', lang: 'ja' } } });
  const reformatted = hazardExtensions(JMA_BOT, { text: '震源: 石川県能登地方 M6.5', tags: tags('earthquake', '7') }, provenance);
  assert.deepEqual(of(reformatted, `${HAZARD}.earthquake`), { $type: `${HAZARD}.earthquake`, kind: 'event' });
  assert.equal(of(reformatted, HAZARD).severity, 'Extreme');
});

test('tsunami height text is kept verbatim and only exact meters become a value', () => {
  const exact = hazardExtensions(JMA_BOT, { text: '🔴 津波警報\n予想される最大高さ：3m', tags: tags('tsunami', 'warning') }, provenance);
  assert.deepEqual(of(exact, `${HAZARD}.tsunami`).maxHeight, { value: 3000, text: { text: '3m', lang: 'ja' }, basis: 'forecast' });
  assert.deepEqual(of(exact, HAZARD).responseTypes, ['Evacuate']);
  assert.equal(of(exact, HAZARD).onset, undefined, 'tsunami time: is an issue time, not an onset');
  const open = hazardExtensions(JMA_BOT, { text: '予想される最大高さ：10m超', tags: tags('tsunami', 'special-warning') }, provenance);
  assert.deepEqual(of(open, `${HAZARD}.tsunami`).maxHeight, { text: { text: '10m超', lang: 'ja' }, basis: 'forecast' });
  assert.deepEqual(of(open, HAZARD).scales, [{ scheme: 'jp.jma.tsunami', value: 'major-warning' }]);
  assert.equal(of(hazardExtensions(JMA_BOT, { text: '', tags: tags('tsunami', 'info') }, provenance), HAZARD).scales, undefined);
});

test('eruption flash bulletin is observed; alert level comes only from the text', () => {
  const flash = hazardExtensions(JMA_BOT, { text: '🔴 噴火速報\n浅間山で噴火が発生\n18日12時00分発表', tags: tags('eruption', 'warning') }, provenance);
  assert.equal(of(flash, HAZARD).certainty, 'Observed'); assert.equal(of(flash, HAZARD).urgency, 'Immediate');
  assert.deepEqual(of(flash, `${HAZARD}.volcano`), { $type: `${HAZARD}.volcano`, phenomenon: 'eruption', volcano: { name: { text: '浅間山', lang: 'ja' } } });
  const levelled = hazardExtensions(JMA_BOT, { text: '🟣 噴火警報（居住地域）\n桜島（噴火警戒レベル5）', tags: tags('eruption', 'special-warning') }, provenance);
  assert.deepEqual(of(levelled, HAZARD).scales, [{ scheme: 'jp.jma.volcanic-alert-level', value: '5' }]);
  assert.equal(of(levelled, HAZARD).certainty, 'Likely');
});

test('Kikikuru per-prefecture posts carry the prefecture cover; jp and unknown targets carry none', () => {
  const exts = hazardExtensions(KIKIKURU_BOT, { text: '竜巻注意情報', tags: tags('tornado-warning', 'warning', 'jp-chiba') }, provenance);
  const hazard = of(exts, HAZARD);
  assert.equal(hazard.urgency, 'Immediate'); assert.deepEqual(hazard.responseTypes, ['Shelter']);
  assert.deepEqual(hazard.event, { text: '竜巻注意情報', lang: 'ja' });
  assert.deepEqual(of(exts, `${HAZARD}.weather`), { $type: `${HAZARD}.weather`, phenomena: ['tornado'] });
  const area = of(exts, 'dev.jisaku.mosimo.geo#area');
  assert.equal(area.approximation, true);
  assert.deepEqual(area.regions.map((r: any) => r.hash), prefectures['jp-chiba']);
  assert.equal(of(hazardExtensions(KIKIKURU_BOT, { text: '', tags: tags('typhoon', 'strong', 'jp') }, provenance), 'dev.jisaku.mosimo.geo#area'), undefined);
  assert.equal(of(hazardExtensions(KIKIKURU_BOT, { text: '', tags: tags('wind-warning', 'warning', 'jp-atlantis') }, provenance), 'dev.jisaku.mosimo.geo#area'), undefined);
});

test('Kikikuru levels, all-clear, overrides and typhoon text', () => {
  const l4 = of(hazardExtensions(KIKIKURU_BOT, { text: '', tags: tags('landslide-warning', 'level4', 'jp-hiroshima') }, provenance), HAZARD);
  assert.deepEqual([l4.category, l4.severity, l4.responseTypes, l4.scales], [['Geo'], 'Extreme', ['Evacuate'], [{ scheme: 'jp.jma.warning-level', value: '4' }]]);
  const clear = of(hazardExtensions(KIKIKURU_BOT, { text: '', tags: tags('heavy-rain-warning', 'cancelled', 'jp-tokyo') }, provenance), HAZARD);
  assert.deepEqual([clear.urgency, clear.responseTypes, clear.scales], ['Past', ['AllClear'], undefined]);
  assert.equal(of(hazardExtensions(KIKIKURU_BOT, { text: '', tags: tags('linear-rainband-warning', 'level5', 'jp-kumamoto') }, provenance), HAZARD).certainty, 'Observed');
  assert.equal(of(hazardExtensions(KIKIKURU_BOT, { text: '', tags: tags('heavy-rain', 'warning', 'jp-kumamoto') }, provenance), HAZARD).certainty, 'Observed');
  const typhoon = hazardExtensions(KIKIKURU_BOT, { text: '🌀 台風第10号（サンサン）\n\n強さ：強い／大きさ：大型\n中心気圧：970hPa\n最大風速：35m/s（最大瞬間50m/s）\n中心位置：日本の南',
    tags: tags('typhoon', 'tropical-storm', 'jp') }, provenance);
  assert.deepEqual(of(typhoon, `${HAZARD}.cyclone`), { $type: `${HAZARD}.cyclone`, name: { text: 'サンサン', lang: 'ja-Kana' }, pressure: 97000, maxWind: 35000 });
  assert.deepEqual(of(typhoon, HAZARD).scales, [{ scheme: 'jp.jma.typhoon-intensity', value: 'none' }]);
  const other = hazardExtensions(KIKIKURU_BOT, { text: '', tags: tags('other-warning', 'warning', 'jp-osaka') }, provenance);
  assert.deepEqual(other.map(e => e.$type), [HAZARD, 'dev.jisaku.mosimo.geo#area', 'dev.jisaku.mosimo.public#source']);
});

test('unlisted Bots, types and values, and ambiguous tags map to nothing', () => {
  assert.deepEqual(hazardExtensions('did:plc:aaaaaaaaaaaaaaaaaaaaaaaa', { text: '', tags: tags('earthquake', '5+') }, provenance), []);
  assert.deepEqual(hazardExtensions(JMA_BOT, { text: '', tags: tags('wind-warning', 'warning') }, provenance), []);
  assert.deepEqual(hazardExtensions(JMA_BOT, { text: '', tags: tags('earthquake', 'level4') }, provenance), []);
  assert.deepEqual(hazardExtensions(JMA_BOT, { text: '', tags: tags('earthquake', 'constructor') }, provenance), []);
  assert.deepEqual(hazardExtensions(JMA_BOT, { text: '', tags: [...tags('earthquake', '5+'), 'value:7'] }, provenance), []);
  // A non-Timestamp time: is dropped from onset, never repaired.
  assert.equal(of(hazardExtensions(JMA_BOT, { text: '', tags: tags('earthquake', '4', 'jp-kanto', '2026-10-18 12:00') }, provenance), HAZARD).onset, undefined);
});

test('prefecture table covers all 47 prefectures within the cell budget', () => {
  assert.equal(Object.keys(prefectures).length, 47);
  for (const cells of Object.values(prefectures) as string[][]) {
    assert.ok(cells.length >= 1 && cells.length <= 64);
    for (const c of cells) assert.match(c, /^[0-9b-hjkmnp-z]{2,4}$/);
  }
});
