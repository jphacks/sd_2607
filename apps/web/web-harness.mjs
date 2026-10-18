// Minimal DOM for running the actual inline client with Node, not a browser substitute.
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { setTimeout as delay } from 'node:timers/promises';

class Element {
  children = [];
  dataset = {};
  style = {};
  textContent = '';
  hidden = false;
  disabled = false;
  listeners = {};
  constructor(tag) { this.tag = tag; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  querySelectorAll(selector) {
    const matches = element => selector === '[data-did]' ? 'did' in element.dataset
      : selector.startsWith('.') ? element.className === selector.slice(1) : element.tag === selector;
    return this.children.flatMap(child => [...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
}

export async function runWeb(fetch, search = '') {
  const elements = Object.fromEntries(['feed', 'status', 'more'].map(id => [id, new Element('div')]));
  const context = vm.createContext({
    URL, URLSearchParams, AbortSignal, Date, Map,
    location: { search }, fetch, setInterval() {},
    document: { getElementById: id => elements[id], createElement: tag => new Element(tag) },
  });
  const html = readFileSync(new URL('./index.html', import.meta.url), 'utf8');
  vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
  async function idle() {
    const deadline = Date.now() + 15000;
    while (vm.runInContext('busy', context)) {
      if (Date.now() > deadline) throw new Error('Client did not release busy state');
      await delay(5);
    }
  }
  await idle();
  return {
    ...elements,
    async refresh() { await vm.runInContext('load()', context); },
    async morePosts() { elements.more.listeners.click(); await idle(); },
    articles() { return elements.feed.querySelectorAll('article'); },
  };
}
