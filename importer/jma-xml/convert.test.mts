import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { convert, JMA_EARTHQUAKE, JMA_WEATHER, HAZARD } from './convert.mts';
import { useTables } from './area.mts';

// Every code resolves to one fake cell, so the tests do not depend on the generated table.
const any = (prefix: string) => new Proxy({}, { has: () => true, get: (_t, k) => [`${prefix}${String(k).slice(-3)}`],
  getOwnPropertyDescriptor: () => ({ value: [], configurable: true, enumerable: true }) }) as Record<string, string[]>;
useTables({ warningCity: any('xn'), quakeArea: any('xp') });
const ctx = { url: 'https://www.data.jma.go.jp/developer/xml/data/x.xml', retrievedAt: '2026-11-14T09:00:00Z', digest: new Uint8Array(32), converter: 'did:plc:aaaaaaaaaaaaaaaaaaaaaaaa' };
const fixture = (name: string) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url));
const ext = (d: any, type: string) => d.ext.filter((e: any) => e.$type === type);
const run = (name: string, fits = (_: any) => true) => { const r = convert(fixture(name), ctx, fits); assert.equal(r.status, 'converted'); return r as Extract<typeof r, { status: 'converted' }>; };

test('R06 warning: one whole post, city-level area of the active kinds, level scale and readable text', () => {
  const { drafts } = run('VPWW56_nagasaki.xml');
  assert.equal(drafts.length, 1);
  const [d] = drafts as any[];
  const [jma] = ext(d, JMA_WEATHER);
  assert.deepEqual(jma.selection, { kind: 'whole' });
  assert.equal(jma.message.control.title, '気象警報・注意報（Ｒ０６）（土砂）');
  assert.ok(jma.warnings.some((w: any) => w.warningType === '気象警報・注意報（府県予報区等）' && w.areas[0].code.value === '420000'));
  const [hazard] = ext(d, HAZARD);
  assert.deepEqual([hazard.severity, hazard.scales[0].value], ['Moderate', '2']);
  assert.deepEqual(ext(d, `${HAZARD}.weather`)[0].phenomena, ['landslide']);
  assert.equal(ext(d, 'dev.jisaku.mosimo.geo#area')[0].regions.length, 2); // 佐世保市（宇久地域を除く）and 平戸市
  assert.match(d.text, /レベル２土砂災害注意報: 佐世保市（宇久地域を除く）、平戸市/);
});

test('R06 warning: lifted-only message is an all-clear; mixed message keeps the most severe active kind', () => {
  const [clear] = run('R06_allclear.xml').drafts as any[];
  assert.deepEqual(ext(clear, HAZARD)[0].responseTypes, ['AllClear']);
  assert.match(clear.text, /今回解除/);
  const [mixed] = run('R06_mixed.xml').drafts as any[];
  assert.equal(ext(mixed, HAZARD)[0].scales[0].value, 'warning');
  assert.match(mixed.text, /発表中:\n暴風警報: 小笠原村\n強風注意報/);
});

test('earthquake: one post per Pref with the national overview, hypocenter, magnitude and observed shindo', () => {
  const { drafts } = run('sample_VXSE53_large.xml');
  assert.equal(drafts.length, 19);
  const d: any = drafts[0];
  const [jma] = ext(d, JMA_EARTHQUAKE);
  assert.deepEqual(jma.selection, { kind: 'region', path: [0] });
  assert.equal(jma.nationalOverview.prefectures.length, 19);
  assert.equal(jma.body.observation.prefs.length, 1);
  const [hazard] = ext(d, HAZARD), [kind] = ext(d, `${HAZARD}.earthquake`);
  assert.deepEqual(hazard.scales, [{ scheme: 'jp.jma.shindo', value: '6+', basis: 'observed' }]);
  assert.deepEqual(kind.hypocenter, { name: { text: '岩手県内陸南部', lang: 'ja' }, lat: 39000000, lon: 140900000, depth: 10000000 });
  assert.deepEqual(kind.magnitude, { value: 70, type: 'Mj' });
  assert.equal(d.priority, 3);
});

test('earthquake: an oversized Pref splits into Areas, then omits station details, and says so', () => {
  const size = (d: any) => JSON.stringify(d.ext).length;
  const limit = 9000;
  const { drafts } = run('sample_VXSE53_large.xml', d => size(d) <= limit);
  assert.ok(drafts.every((d: any) => size(d) <= limit));
  const split = drafts.find((d: any) => ext(d, JMA_EARTHQUAKE)[0].selection.path.length === 2) as any;
  assert.ok(split);
  const reduced = drafts.find((d: any) => ext(d, JMA_EARTHQUAKE)[0].reduction) as any;
  if (reduced) assert.match(reduced.text, /容量上限のため/);
});

test('cancellation is one whole post without hazard; other titles and exercises are skipped', () => {
  const [d] = run('sample_VXSE53_cancel.xml').drafts as any[];
  assert.deepEqual(ext(d, JMA_EARTHQUAKE)[0].selection, { kind: 'whole' });
  assert.equal(ext(d, HAZARD).length, 0);
  assert.match(d.text, /^【取消】/);
  const exercise = Buffer.from(fixture('VXSE53_hokkaido.xml').toString().replace('<Status>通常</Status>', '<Status>訓練</Status>'));
  assert.equal(convert(exercise, ctx, () => true).status, 'skipped');
  assert.throws(() => convert(Buffer.from('<!DOCTYPE x><x/>'), ctx, () => true), /DTD/);
});
