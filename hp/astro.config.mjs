import { defineConfig } from 'astro/config';
import cloudflare from '@astrojs/cloudflare';
import config from './astro.pages.config.mjs';

export default defineConfig({
  ...config,
  output: 'server',
  adapter: cloudflare(),
});
