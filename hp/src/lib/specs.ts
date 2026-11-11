import type { MarkdownInstance } from 'astro';

// 仕様書は protocol/mosimo 以下の README.md をビルド時に直接取り込む。
// hp/ 側に本文の複製は置かない。
const specs = import.meta.glob<MarkdownInstance<Record<string, unknown>>>('../../../protocol/mosimo/**/README.md', { eager: true });
const glossary = import.meta.glob<MarkdownInstance<Record<string, unknown>>>('../../../CONTEXT.md', { eager: true });

/** slug（'' はコア）に対応する README モジュール */
export function specOf(slug: string): MarkdownInstance<Record<string, unknown>> {
  const key = `../../../protocol/mosimo/${slug ? `${slug}/` : ''}README.md`;
  const mod = specs[key];
  if (!mod) throw new Error(`protocol/mosimo/${slug ? `${slug}/` : ''}README.md が見つかりません`);
  return mod;
}

export function glossaryDoc(): MarkdownInstance<Record<string, unknown>> {
  const mod = glossary['../../../CONTEXT.md'];
  if (!mod) throw new Error('CONTEXT.md が見つかりません');
  return mod;
}
