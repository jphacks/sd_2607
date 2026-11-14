// Bounded, namespace-aware reading of JMA XML (public.jma §10): no DTD or external entity, limited size, depth and
// element count. Elements are matched by namespace URI and local name, never by prefix or text patterns.
import { DOMParser } from '@xmldom/xmldom';

export const NS = {
  report: 'http://xml.kishou.go.jp/jmaxml1/',
  head: 'http://xml.kishou.go.jp/jmaxml1/informationBasis1/',
  seismology: 'http://xml.kishou.go.jp/jmaxml1/body/seismology1/',
  meteorology: 'http://xml.kishou.go.jp/jmaxml1/body/meteorology1/',
  element: 'http://xml.kishou.go.jp/jmaxml1/elementBasis1/',
  xsi: 'http://www.w3.org/2001/XMLSchema-instance',
} as const;

export const MAX_XML_BYTES = 4 * 1024 * 1024;
const MAX_ELEMENTS = 200000;
const MAX_DEPTH = 32;

export type Node = any;

/** Parses one original message; throws on anything a conforming JMA XML does not contain. */
export function parseXml(bytes: Uint8Array): Node {
  if (bytes.length > MAX_XML_BYTES) throw new Error('XML size limit');
  const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes);
  if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw new Error('DTD not allowed');
  const errors: string[] = [];
  const doc = new DOMParser({ errorHandler: { warning: () => {}, error: (m: string) => errors.push(m), fatalError: (m: string) => errors.push(m) } })
    .parseFromString(text, 'text/xml');
  if (errors.length || !doc?.documentElement) throw new Error(`XML parse: ${errors[0] ?? 'no document'}`);
  let count = 0;
  const walk = (node: Node, depth: number) => {
    if (depth > MAX_DEPTH) throw new Error('XML depth limit');
    for (let c = node.firstChild; c; c = c.nextSibling) {
      if (c.nodeType !== 1) continue;
      if (++count > MAX_ELEMENTS) throw new Error('XML element limit');
      walk(c, depth + 1);
    }
  };
  walk(doc.documentElement, 1);
  return doc.documentElement;
}

export function children(el: Node | undefined, ns: string, local: string): Node[] {
  const out: Node[] = [];
  for (let c = el?.firstChild; c; c = c.nextSibling) if (c.nodeType === 1 && c.namespaceURI === ns && c.localName === local) out.push(c);
  return out;
}
export const child = (el: Node | undefined, ns: string, local: string): Node | undefined => children(el, ns, local)[0];
/** Element text exactly as sent (public.jma §3.1); undefined when the element is absent. */
export const text = (el: Node | undefined): string | undefined => el ? String(el.textContent ?? '') : undefined;
export const attr = (el: Node | undefined, name: string): string | undefined => el?.hasAttribute(name) ? String(el.getAttribute(name)) : undefined;

/** JmaValue: the element string plus its type, unit, description and condition attributes and xsi:nil. */
export function value(el: Node | undefined) {
  if (!el) return undefined;
  const nil = el.getAttributeNS(NS.xsi, 'nil');
  const out: Record<string, unknown> = { value: text(el)! };
  for (const name of ['type', 'unit', 'description', 'condition']) { const v = attr(el, name); if (v !== undefined) out[name] = v; }
  if (nil === 'true' || nil === '1') out.nil = true;
  return out as { value: string; type?: string; unit?: string; description?: string; condition?: string; nil?: boolean };
}
/** JmaCode: the code string with its scheme, from the element's type attribute or the given CodeDefine type. */
export function code(el: Node | undefined, type?: string) {
  if (!el) return undefined;
  const t = attr(el, 'type') ?? type;
  return { value: text(el)!, ...(t !== undefined ? { type: t } : {}) };
}
