import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { Lexicons, lexiconDoc } from '@atproto/lexicon';

const schema = JSON.parse(
  readFileSync(new URL('../protocol/mosimo/post.json', import.meta.url), 'utf8'),
);
const id = 'dev.jisaku.mosimo.post';
const lexicons = new Lexicons([structuredClone(schema)]);

function post(overrides = {}) {
  return {
    $type: id,
    text: '避難所Aは開設済みです。\n飲料水があります。',
    exp: '2026-10-18T12:34:56Z',
    ext: [],
    ...overrides,
  };
}

function validate(record) {
  lexicons.assertValidRecord(id, record);
  const result = lexicons.validate(id, record);
  assert.equal(result.success, true);
  return result.value;
}

test('schema is valid Lexicon with a TID key and open extension union', () => {
  assert.equal(lexiconDoc.safeParse(schema).success, true);
  assert.equal(schema.lexicon, 1);
  assert.equal(schema.id, id);
  const main = schema.defs.main;
  assert.equal(main.type, 'record');
  assert.equal(main.key, 'tid');
  assert.deepEqual(main.record.required, [
    'text', 'exp', 'ext',
  ]);
  assert.deepEqual(Object.keys(main.record.properties), ['text', 'exp', 'created', 'ext']);
  assert.deepEqual(main.record.properties.ext.items, {
    type: 'union', refs: [], closed: false,
  });
});

test('accepts a basic post and preserves text and datetime spelling', () => {
  const record = post({ text: '避難所\r\nか\u3099・が・🏠' });
  const original = structuredClone(record);
  assert.deepEqual(validate(record), original);
  assert.deepEqual(record, original);
});

test('accepts optional created with arbitrary fractional precision', () => {
  for (const created of [
    '2026-10-17T12:34:56Z',
    '2026-10-17T12:34:56.0Z',
    '2026-10-17T12:34:56.12345678912345Z',
  ]) {
    assert.equal(validate(post({ created })).created, created);
  }
  assert.equal(validate(post()).created, undefined);
});

test('preserves third-party types, unknown fields, duplicates, and array order', () => {
  const ext = [
    {
      $type: 'dev.jisaku.mosimo.appExtension#report',
      detail: { text: '断水', observed: true, count: 2, optional: null },
      readings: [1, 2, 3],
    },
    { $type: 'dev.jisaku.mosimo.geo#area', futureField: 'uninterpreted' },
    { $type: 'dev.jisaku.mosimo.appExtension#report', detail: {} },
  ];
  const record = post({ ext });
  const original = structuredClone(record);
  assert.deepEqual(validate(record), original);
  assert.deepEqual(record, original);
});

for (const field of ['$type', 'text', 'exp', 'ext']) {
  test(`rejects a missing required ${field}`, () => {
    const record = post();
    delete record[field];
    assert.throws(() => lexicons.assertValidRecord(id, record));
  });
  test(`rejects null for ${field}`, () => {
    assert.throws(() => lexicons.assertValidRecord(id, post({ [field]: null })));
  });
}

test('rejects another record type', () => {
  assert.throws(() => lexicons.assertValidRecord(id, post({ $type: 'app.bsky.feed.post' })));
});

for (const value of [42, true, [], {}]) {
  test(`rejects non-string text ${JSON.stringify(value)}`, () => {
    assert.throws(() => lexicons.assertValidRecord(id, post({ text: value })));
  });
}

for (const field of ['exp', 'created']) {
  test(`rejects malformed ${field}`, () => {
    for (const value of [42, '2026-10-17', 'not-a-date']) {
      assert.throws(() => lexicons.assertValidRecord(id, post({ [field]: value })));
    }
  });
}

test('rejects non-array ext and untyped union values', () => {
  for (const ext of [
    {}, 'text', [null], [1], ['text'], [[]], [{}], [{ $type: 1 }],
  ]) {
    assert.throws(() => lexicons.assertValidRecord(id, post({ ext })));
  }
});

test('pinned upstream validator also accepts a timezone-less datetime', () => {
  const exp = '2026-10-17T12:34:56';
  assert.equal(validate(post({ exp })).exp, exp);
});

test('Lexicon validation does not enforce the stricter protocol profile', () => {
  const record = post({
    exp: '2026-10-17T12:34:56+00:00',
    extraField: 'preserve rather than strip',
  });
  assert.deepEqual(validate(record), record);
});
