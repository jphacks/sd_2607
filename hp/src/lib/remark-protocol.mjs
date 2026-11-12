import path from 'node:path';
import { visit } from 'unist-util-visit';
import { renderDiagram } from './mermaid.mjs';

const REPO = 'https://github.com/jphacks/sd_2607/blob/main/';

/** vfile のパスから、protocol/mosimo を基準とするディレクトリを得る。対象外なら null。 */
function protocolDir(file) {
  const p = (file.path ?? file.history?.[0] ?? '').replace(/\\/g, '/');
  const m = p.match(/\/protocol\/mosimo(?:\/(.*))?\/README\.md$/);
  if (m) return m[1] ?? '';
  if (/\/CONTEXT\.md$/.test(p)) return '__glossary__';
  return null;
}

/** README 間の相対リンクをサイトの URL に書き換える。 */
export function rewriteLink(url, dir) {
  if (/^[a-z][a-z0-9+.-]*:/i.test(url) || url.startsWith('#') || url.startsWith('/')) return url;
  const [rawPath, hash = ''] = url.split('#');
  const suffix = hash ? `#${hash}` : '';
  const base = dir === '__glossary__' ? '..' : dir;
  const resolved = path.posix.normalize(path.posix.join(base, rawPath));

  if (resolved === '../CONTEXT.md') return `/protocol/glossary/${suffix}`;
  if (resolved === '../README.md') return `/protocol/${suffix}`;
  if (resolved.startsWith('..')) return REPO + path.posix.normalize(path.posix.join('protocol/mosimo', resolved));

  if (/(^|\/)README\.md$/.test(resolved)) {
    const d = resolved.replace(/\/?README\.md$/, '');
    return `/protocol/${d ? `${d}/` : ''}${suffix}`;
  }
  if (resolved.endsWith('.json')) return `/protocol/${resolved}`;
  return REPO + path.posix.join('protocol/mosimo', resolved);
}

function textOf(node) {
  if (!node) return '';
  if (node.type === 'text' || node.type === 'inlineCode') return node.value;
  return (node.children ?? []).map(textOf).join('');
}

/**
 * 仕様書の README を Web 掲載用に整える remark プラグイン。
 * - 先頭の見出しと「識別子:」の行はページのヘッダーで示すため本文から除く
 * - 「未確定事項」の節を除く
 * - mermaid を SVG に変換する
 * - README 間の相対リンクをサイト内の URL に書き換える
 */
export default function remarkProtocol() {
  return (tree, file) => {
    const dir = protocolDir(file);
    if (dir === null) return;

    if (dir !== '__glossary__') {
      const out = [];
      let skipping = false;
      let droppedTitle = false;
      for (const node of tree.children) {
        if (node.type === 'heading' && node.depth <= 2) {
          skipping = node.depth === 2 && textOf(node).trim() === '未確定事項';
        }
        if (skipping) continue;
        if (!droppedTitle && node.type === 'heading' && node.depth === 1) {
          droppedTitle = true;
          continue;
        }
        if (node.type === 'paragraph' && /^識別子\s*[:：]/.test(textOf(node).trim())) continue;
        out.push(node);
      }
      tree.children = out;

      // 本文中の「〜は未確定事項である。」「〜は未確定事項だが、」の言及を除く
      visit(tree, 'text', (node, _i, parent) => {
        if (parent?.type === 'tableCell' && node.value.trim() === '未確定') node.value = '—';
        node.value = node.value.replace(/[^。]*未確定事項だが、/g, '').replace(/[^。]*未確定事項である。/g, '');
      });

      visit(tree, 'code', (node, index, parent) => {
        if (node.lang !== 'mermaid' || !parent || index === undefined) return;
        parent.children[index] = { type: 'html', value: renderDiagram(node.value) };
      });
    }

    visit(tree, ['link', 'definition'], (node) => {
      node.url = rewriteLink(node.url, dir);
    });
  };
}
