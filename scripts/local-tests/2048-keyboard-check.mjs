import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { once } from 'node:events';
import { pathToFileURL } from 'node:url';

const [playwrightPath, executablePath] = process.argv.slice(2);
const { AGL_TEST_RESULT_PATH: output, AGL_TEST_RUN_ID: runId } = process.env;
if (!output || !runId || !playwrightPath) throw new Error('Register the Playwright module in the local test target');
const scripts = new Set((await fs.readdir('js')).filter(name => name.endsWith('.js')).map(name => '/js/' + name));
const files = await Promise.all(['/index.html', '/style/main.css', ...scripts].map(async name =>
  [name, await fs.readFile('.' + name)]));
const assets = new Map(files);
const server = http.createServer((request, response) => {
  const file = assets.get(request.url);
  if (request.method !== 'GET' || !file) { response.writeHead(404).end(); return; }
  const type = request.url.endsWith('.js') ? 'text/javascript' : request.url.endsWith('.css') ? 'text/css' : 'text/html';
  response.writeHead(200, { 'Content-Type': type + '; charset=utf-8' }).end(file);
});
server.listen(0, '127.0.0.1');
let browser;
try {
  await once(server, 'listening');
  const origin = `http://127.0.0.1:${server.address().port}`;
  const { chromium } = await import(pathToFileURL(path.resolve(playwrightPath)));
  browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true });
  const page = await browser.newPage();
  await page.route('**/*', route => route.request().url().startsWith(origin + '/') ? route.continue() : route.abort());
  await page.goto(origin + '/index.html', { timeout: 10000 });
  await page.locator('.tile').first().waitFor();
  await page.evaluate(() => {
    const original = window.KeyboardInputManager.prototype.emit;
    window.aglInputEvents = [];
    window.KeyboardInputManager.prototype.emit = function(event, data) {
      window.aglInputEvents.push([event, data]);
      return original.call(this, event, data);
    };
  });
  await page.keyboard.down('r');
  await page.keyboard.down('r');
  await page.keyboard.up('r');
  await page.keyboard.press('Shift+r');
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('d');
  const events = await page.evaluate(() => window.aglInputEvents);
  const restarts = events.filter(([name]) => name === 'restart').length;
  const moves = events.filter(([name]) => name === 'move').map(([, direction]) => direction);
  const checks = [
    { id: 'restart-held-key', status: restarts === 1 ? 'PASS' : 'FAIL', expected: 'One restart per held R key', actual: String(restarts) },
    { id: 'move-key-regression', status: moves.join(',') === '3,1' ? 'PASS' : 'FAIL',
      expected: 'ArrowLeft and D dispatch left and right', actual: moves.join(',') },
  ];
  await fs.mkdir(path.dirname(output), { recursive: true });
  await fs.writeFile(output, JSON.stringify({
    runId, status: checks.every(check => check.status === 'PASS') ? 'PASS' : 'FAIL', coverageComplete: true, checks,
    metrics: { restartEvents: restarts, directions: moves, browser: browser.version() },
  }));
} finally {
  try { await browser?.close(); } finally { server.close(); }
}
