import { renderMermaidSVG } from 'beautiful-mermaid';

const COLORS = {
  bg: '#f2f0e7',
  fg: '#191b18',
  line: '#191b18',
  accent: '#191b18',
  muted: '#4b4c46',
  surface: '#ffffff',
  border: '#191b18',
};

/**
 * 先にラベルなしで参照され、後からラベル付きで定義されるノード（`P --> A` の後の `A[...]`）を、
 * 最初の参照からラベル付きにする。レンダラーが最初の出現のラベルだけを採るためである。
 */
export function hoistNodeLabels(source) {
  const lines = source.split('\n');
  const defs = new Map();
  const defRe = /(^|[\s>|&-])([A-Za-z_][\w]*)(\[[^\]]*\]|\([^)]*\)|\{[^}]*\})/g;
  for (const line of lines) {
    for (const m of line.matchAll(defRe)) if (!defs.has(m[2])) defs.set(m[2], m[3]);
  }
  if (defs.size === 0) return source;
  return lines
    .map((line) => {
      if (/^\s*(flowchart|graph|subgraph|end\b|classDef|style|sequenceDiagram|stateDiagram|participant|Note)/.test(line)) return line;
      // 角括弧・丸括弧・波括弧・縦棒の内側は置き換えない
      let out = '';
      let depth = 0;
      let inPipe = false;
      let i = 0;
      while (i < line.length) {
        const ch = line[i];
        if (!inPipe && depth === 0 && /[A-Za-z_]/.test(ch) && (i === 0 || !/[\w]/.test(line[i - 1]))) {
          const m = line.slice(i).match(/^[A-Za-z_]\w*/);
          const id = m[0];
          const next = line[i + id.length] ?? '';
          if (defs.has(id) && !/[\[({]/.test(next)) {
            out += id + defs.get(id);
            i += id.length;
            continue;
          }
          out += id;
          i += id.length;
          continue;
        }
        if (ch === '|') inPipe = !inPipe;
        else if (!inPipe && /[\[({]/.test(ch)) depth++;
        else if (!inPipe && /[\])}]/.test(ch)) depth = Math.max(0, depth - 1);
        out += ch;
        i++;
      }
      return out;
    })
    .join('\n');
}

/**
 * mermaid のソースをビルド時に SVG へ変換し、サイトの様式に合わせて整える。
 * 外部フォントの読み込みを除き、線を太く、角を直角にする。
 */
export function renderDiagram(source, { caption } = {}) {
  if (/^\s*(flowchart|graph)\b/.test(source)) source = hoistNodeLabels(source);
  let svg = renderMermaidSVG(source, {
    ...COLORS,
    transparent: true,
    font: '"Noto Sans JP", "Helvetica Neue", system-ui, sans-serif',
    padding: 16,
  });

  svg = svg.replace(/@import url\([^)]*\);?\s*/g, '');
  svg = svg.replace(/stroke-width="1"/g, 'stroke-width="2"');
  svg = svg.replace(/stroke-width="0\.75"/g, 'stroke-width="1.5"');
  svg = svg.replace(/ rx="[^"]*" ry="[^"]*"/g, ' rx="0" ry="0"');

  const vb = svg.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/);
  const width = vb ? Math.ceil(Number(vb[1])) : 800;
  svg = svg.replace(/<svg ([^>]*?) width="[^"]*" height="[^"]*"/, '<svg $1 role="img"');

  const cap = caption ? `<figcaption>${escapeHtml(caption)}</figcaption>` : '';
  return `<figure class="diagram"><div class="diagram-scroll"><div class="diagram-box" style="width:${width}px">${svg}</div></div>${cap}</figure>`;
}

function escapeHtml(s) {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
}
