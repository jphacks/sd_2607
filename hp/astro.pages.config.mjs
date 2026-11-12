import { defineConfig } from 'astro/config';
import { unified } from '@astrojs/markdown-remark';
import remarkProtocol from './src/lib/remark-protocol.mjs';
import rehypeProtocol from './src/lib/rehype-protocol.mjs';

export default defineConfig({
  site: 'https://mosimo.jisaku.dev',
  // 仕様書（../protocol）と用語集（../CONTEXT.md）を開発サーバーでも読めるようにする
  vite: { server: { fs: { allow: ['..'] } } },
  trailingSlash: 'ignore',
  markdown: {
    processor: unified({
      remarkPlugins: [remarkProtocol],
      rehypePlugins: [rehypeProtocol],
      smartypants: false,
    }),
    shikiConfig: { theme: 'github-light' },
  },
  redirects: {
    '/reference/mobile/android/policy': {
      destination: '/reference/mobile/policy',
      status: 301,
    },
    '/reference/mobile/ios/policy': {
      destination: '/reference/mobile/policy',
      status: 301,
    },
    '/reference/mobile/hanashi/android/policy': {
      destination: '/reference/mobile/hanashi/policy',
      status: 301,
    },
    '/reference/mobile/hanashi/ios/policy': {
      destination: '/reference/mobile/hanashi/policy',
      status: 301,
    },
  },
});
