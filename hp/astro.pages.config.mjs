import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://mosimo.jisaku.dev',
  redirects: {
    '/reference/mobile/android/policy': {
      destination: '/reference/mobile/policy',
      status: 301,
    },
    '/reference/mobile/ios/policy': {
      destination: '/reference/mobile/policy',
      status: 301,
    },
  },
});
