import { readFile } from 'node:fs/promises';
import { validateConfig } from './config.mjs';
import { openStore } from './store.mjs';
import { createIdentityResolver } from './identity.mjs';
import { createIngestion } from './ingest.mjs';
import { createServer } from './server.mjs';
import { startNetwork } from './network.mjs';

const config = validateConfig(JSON.parse(await readFile(process.env.CONFIG_FILE, 'utf8')));
if (!process.env.DATABASE_PATH && !process.env.DATABASE_URL) throw new Error('DATABASE_PATH or DATABASE_URL is required');
const port = Number(process.env.PORT ?? 2583);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
const store = await openStore(process.env.DATABASE_URL);
const resolveIdentity = createIdentityResolver(config);
const { ingest, processOne } = createIngestion({ config, store, resolveIdentity });
const network = startNetwork({ config, store, ingest });
const app = createServer({ config, store, ingest, resolveIdentity, onNotify: network.onNotify });
let stopping = false;
let worker;
async function runWorker() {
  while (!stopping) {
    try {
      if (await processOne()) continue;
    } catch (error) { console.error('Repository worker:', error.message); }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
}
app.server.listen(port, process.env.HOST ?? '0.0.0.0', () => console.log(`Mosimo PDS listening on ${port}`));
worker = runWorker();
async function shutdown() {
  if (stopping) return;
  stopping = true;
  const deadline = setTimeout(() => process.exit(1), 30000);
  deadline.unref();
  await app.close();
  await network.close();
  await worker;
  await store.close();
  clearTimeout(deadline);
}
process.on('SIGINT', () => shutdown().catch(error => { console.error(error.message); process.exitCode = 1; }));
process.on('SIGTERM', () => shutdown().catch(error => { console.error(error.message); process.exitCode = 1; }));
