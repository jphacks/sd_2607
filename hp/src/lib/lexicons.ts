export interface LexiconFile {
  /** サイト上のパス。例: /protocol/attest/chain/lexicon.json */
  url: string;
  /** リポジトリ内の相対パス */
  file: string;
  /** 所属するプロトコルの slug */
  slug: string;
  id: string;
  defs: string[];
  /** 原文そのまま */
  json: string;
}

const PREFIX = '../../../protocol/mosimo/';
const files = import.meta.glob<string>('../../../protocol/mosimo/**/*.json', { eager: true, query: '?raw', import: 'default' });

/** protocol/mosimo 以下の全 Lexicon JSON を列挙する。 */
export function listLexicons(): LexiconFile[] {
  return Object.entries(files)
    .map(([key, json]) => {
      const rel = key.slice(PREFIX.length);
      const data = JSON.parse(json) as { id: string; defs?: Record<string, unknown> };
      const slug = rel.includes('/') ? rel.slice(0, rel.lastIndexOf('/')) : '';
      return { url: `/protocol/${rel}`, file: `protocol/mosimo/${rel}`, slug, id: data.id, defs: Object.keys(data.defs ?? {}), json };
    })
    .sort((a, b) => a.id.localeCompare(b.id));
}

export function lexiconsOf(slug: string): LexiconFile[] {
  return listLexicons().filter((l) => l.slug === slug);
}
