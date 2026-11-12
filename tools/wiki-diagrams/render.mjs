import { build } from 'esbuild';
import { createServer } from 'node:http';
import { access, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import puppeteer from 'puppeteer-core';

const toolRoot = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(toolRoot, '../..');
const diagramRoot = join(repoRoot, 'docs/wiki/diagrams');

// Exported for local generation/QA; normal maintenance only needs `npm run render`.
export async function withRenderer(work) {
  const scratch = await mkdtemp(join(tmpdir(), 'limited-sale-excalidraw-'));
  let server;
  let browser;
  try {
    await build({
      stdin: {
        contents: 'import * as api from "@excalidraw/excalidraw"; window.diagramApi = api;',
        resolveDir: toolRoot,
      },
      outfile: join(scratch, 'bundle.js'),
      bundle: true,
      define: { 'process.env.NODE_ENV': '"production"' },
      logLevel: 'silent',
    });
    server = createServer(async (request, response) => {
      try {
        const path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
        if (path === '/') {
          response.setHeader('Content-Type', 'text/html; charset=utf-8');
          response.end('<!doctype html><meta charset="utf-8"><script>window.EXCALIDRAW_ASSET_PATH="/assets/";</script><script src="/bundle.js"></script>');
          return;
        }
        let file;
        if (path === '/bundle.js') {
          file = join(scratch, 'bundle.js');
          response.setHeader('Content-Type', 'text/javascript');
        } else if (path.startsWith('/assets/fonts/') && !path.includes('..')) {
          file = join(toolRoot, 'node_modules/@excalidraw/excalidraw/dist/prod', path.slice('/assets/'.length));
          response.setHeader('Content-Type', 'font/woff2');
        } else {
          response.writeHead(404).end();
          return;
        }
        response.end(await readFile(file));
      } catch {
        response.writeHead(404).end();
      }
    });
    await new Promise((done, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', done);
    });
    const chrome = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
    await access(chrome);
    browser = await puppeteer.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${server.address().port}`, { waitUntil: 'networkidle0' });
    await page.waitForFunction(() => Boolean(window.diagramApi));
    return await work(page);
  } finally {
    if (browser) await browser.close();
    if (server) await new Promise(done => server.close(done));
    await rm(scratch, { recursive: true, force: true });
  }
}

async function main() {
  const files = (await readdir(diagramRoot)).filter(name => name.endsWith('.excalidraw')).sort();
  if (!files.length) throw new Error('No Excalidraw scenes found');
  await withRenderer(async page => {
    for (const file of files) {
      const scene = JSON.parse(await readFile(join(diagramRoot, file), 'utf8'));
      if (scene.type !== 'excalidraw' || !scene.elements.length) throw new Error(`Invalid scene: ${file}`);
      const svg = await page.evaluate(async scene => {
        const api = window.diagramApi;
        const elements = api.restoreElements(scene.elements, null);
        const svg = await api.exportToSvg({
          elements,
          appState: { ...scene.appState, exportBackground: true, exportWithDarkMode: false },
          files: scene.files || {},
          exportPadding: 28,
        });
        return svg.outerHTML;
      }, scene);
      await writeFile(join(diagramRoot, file.replace('.excalidraw', '.svg')), `${svg}\n`);
      process.stdout.write(`Rendered ${file}\n`);
    }
  });
  process.stdout.write(`${files.length} SVGs rendered\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    process.stderr.write(`${error.stack}\n`);
    process.exitCode = 1;
  });
}
