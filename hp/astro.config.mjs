import { defineConfig } from 'astro/config';
import cloudflare from '@astrojs/cloudflare';

export default defineConfig({
  site: 'https://mosimo.jisaku.dev',
  output: 'server',
  adapter: cloudflare(),
});
