import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { Lexicons, lexiconDoc } from '@atproto/lexicon';

const schema = JSON.parse(
  readFileSync(new URL('./dev/oriom/mosimo/post.json', import.meta.url), 'utf8'),
);
const id = 'dev.oriom.mosimo.post';
const lexicons = new Lexicons([structuredClone(schema)]);

function post(overrides = {}) {
  return {
    $type: id,
    text: '避難所Aは開設済みです。\n飲料水があります。',
    createdAt: '2026-10-17T12:34:56.120000Z',
    deliveryExpiresAt: '2026-10-18T12:34:56Z',
    extensions: [],
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
    'text', 'createdAt', 'deliveryExpiresAt', 'extensions',
  ]);
  assert.deepEqual(Object.keys(main.record.properties), main.record.required);
  assert.deepEqual(main.record.properties.extensions.items, {
    type: 'union', refs: [], closed: false,
  });
});

test('accepts a basic post and preserves text and datetime spelling', () => {
  const record = post({ text: '避難所\r\nか\u3099・が・🏠' });
  const original = structuredClone(record);
  assert.deepEqual(validate(record), original);
  assert.deepEqual(record, original);
});

test('accepts whole seconds and arbitrary fractional precision', () => {
  for (const createdAt of [
    '2026-10-17T12:34:56Z',
    '2026-10-17T12:34:56.0Z',
    '2026-10-17T12:34:56.12345678912345Z',
  ]) {
    assert.equal(validate(post({ createdAt })).createdAt, createdAt);
  }
});

test('preserves third-party types, unknown fields, duplicates, and array order', () => {
  const extensions = [
    {
      $type: 'dev.oriom.mosimo.appExtension#report',
      detail: { text: '断水', observed: true, count: 2, optional: null },
      readings: [1, 2, 3],
    },
    { $type: 'dev.oriom.mosimo.defs#area', futureField: 'uninterpreted' },
    { $type: 'dev.oriom.mosimo.appExtension#report', detail: {} },
  ];
  const record = post({ extensions });
  const original = structuredClone(record);
  assert.deepEqual(validate(record), original);
  assert.deepEqual(record, original);
});

for (const field of ['$type', 'text', 'createdAt', 'deliveryExpiresAt', 'extensions']) {
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

for (const field of ['createdAt', 'deliveryExpiresAt']) {
  test(`rejects malformed ${field}`, () => {
    for (const value of [42, '2026-10-17', 'not-a-date']) {
      assert.throws(() => lexicons.assertValidRecord(id, post({ [field]: value })));
    }
  });
}

test('rejects non-array extensions and untyped union values', () => {
  for (const extensions of [
    {}, 'text', [null], [1], ['text'], [[]], [{}], [{ $type: 1 }],
  ]) {
    assert.throws(() => lexicons.assertValidRecord(id, post({ extensions })));
  }
});

test('pinned upstream validator also accepts a timezone-less datetime', () => {
  const createdAt = '2026-10-17T12:34:56';
  assert.equal(validate(post({ createdAt })).createdAt, createdAt);
});

test('Lexicon validation does not enforce the stricter protocol profile', () => {
  const record = post({
    createdAt: '2026-10-17T12:34:56+00:00',
    extraField: 'preserve rather than strip',
  });
  assert.deepEqual(validate(record), record);
});
