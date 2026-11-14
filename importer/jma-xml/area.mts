// public §10 / geo §4.3: the Geohash cover of a set of JMA area codes. Every code must resolve, or there is no area;
// cells are merged into parents and coarsened (never dropped) until the cover fits MAX_CELLS.
import { readFileSync } from 'node:fs';

export type AreaTable = 'warningCity' | 'quakeArea';
export const MAX_CELLS = 256;
const BASE32 = '0123456789bcdefghjkmnpqrstuvwxyz';

let tables: Record<AreaTable, Record<string, string[]>> | undefined;
function load() {
  return tables ??= JSON.parse(readFileSync(new URL('./areas.json', import.meta.url), 'utf8'));
}
/** For tests: replace the generated table. */
export function useTables(value: Record<AreaTable, Record<string, string[]>>) { tables = value; }

/** Union of disjoint cells: drop cells inside another, then replace each complete set of 32 siblings by their parent. */
export function normalize(cells: Iterable<string>): string[] {
  let set = new Set(cells);
  for (const cell of [...set]) for (let n = 1; n < cell.length; n++) if (set.has(cell.slice(0, n))) { set.delete(cell); break; }
  for (let merged = true; merged;) {
    merged = false;
    const byParent = new Map<string, number>();
    for (const cell of set) if (cell.length > 1) byParent.set(cell.slice(0, -1), (byParent.get(cell.slice(0, -1)) ?? 0) + 1);
    for (const [parent, n] of byParent) if (n === 32) {
      for (const ch of BASE32) set.delete(parent + ch);
      set.add(parent); merged = true;
    }
  }
  return [...set].sort();
}

/** Cover of every code in `codes`, at most MAX_CELLS cells, or null when any code is unknown to the table. */
export function cover(table: AreaTable, codes: Iterable<string>): string[] | null {
  const source = load()[table];
  const cells: string[] = [];
  for (const c of codes) {
    if (!Object.hasOwn(source, c)) return null;
    cells.push(...source[c]!);
  }
  if (!cells.length) return null;
  let out = normalize(cells);
  while (out.length > MAX_CELLS) {
    const longest = Math.max(...out.map(c => c.length));
    if (longest <= 1) break;
    out = normalize(out.map(c => c.length === longest ? c.slice(0, -1) : c));
  }
  return out;
}

export const areaExtension = (cells: string[]) =>
  ({ $type: 'dev.jisaku.mosimo.geo#area', regions: cells.map(hash => ({ shape: 'geohash', hash })), approximation: true });
