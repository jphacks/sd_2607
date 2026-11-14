import assert from 'node:assert/strict';
import test from 'node:test';
import { normalize, cover, useTables, MAX_CELLS } from './area.mts';

test('normalize drops covered cells and merges complete sibling sets', () => {
  assert.deepEqual(normalize(['xn5', 'xn5q', 'xn7']), ['xn5', 'xn7']);
  assert.deepEqual(normalize([...'0123456789bcdefghjkmnpqrstuvwxyz'].map(c => `xn${c}`)), ['xn']);
});

test('cover needs every code and coarsens instead of dropping cells', () => {
  const many = Object.fromEntries(Array.from({ length: 40 }, (_, i) => [String(i), [...'0123456789bcdefg'].map(c => `x${'0123456789bcdefghjkmnpqrstuvwxyz'[i % 32]}${c}${i}`)]));
  useTables({ warningCity: { a: ['xn5q1'], b: ['xn5q2'], ...many }, quakeArea: {} });
  assert.deepEqual(cover('warningCity', ['a', 'b']), ['xn5q1', 'xn5q2']);
  assert.equal(cover('warningCity', ['a', 'unknown']), null);
  assert.equal(cover('quakeArea', []), null);
  const big = cover('warningCity', Object.keys(many))!;
  assert.ok(big.length <= MAX_CELLS);
  for (const cells of Object.values(many)) for (const cell of cells) assert.ok(big.some(c => cell.startsWith(c)), cell);
});
