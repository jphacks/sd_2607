import { visit } from 'unist-util-visit';

/**
 * 表を横スクロール可能な枠で包み、外部リンクに rel を付ける。
 */
export default function rehypeProtocol() {
  return (tree) => {
    visit(tree, 'element', (node, index, parent) => {
      if (node.tagName === 'table' && parent && index !== undefined && parent.properties?.className?.includes?.('table-wrap') !== true) {
        parent.children[index] = {
          type: 'element',
          tagName: 'div',
          properties: { className: ['table-wrap'] },
          children: [node],
        };
        return 'skip';
      }
      if (node.tagName === 'a' && typeof node.properties?.href === 'string' && /^https?:\/\//.test(node.properties.href)) {
        node.properties.rel = 'noopener';
      }
    });
  };
}
