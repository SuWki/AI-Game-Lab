import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [candidateId, playwrightModule, executablePath, origin = 'http://127.0.0.1:8080'] = process.argv.slice(2);
if (!candidateId?.match(/^[a-f0-9-]{36}$/) || !playwrightModule) {
  throw new Error('Usage: node candidate-browser.mjs <candidate-id> <playwright-index.mjs> [browser-executable] [local-api-origin]');
}
if (!/^http:\/\/127\.0\.0\.1:[0-9]{1,5}$/.test(origin)) throw new Error('Use an explicit loopback API origin');
const candidate = `/api/production-candidates/${candidateId}`;
const evidenceEndpoint = candidate + '/browser-evidence';

async function api(endpoint, body) {
  const response = await fetch(origin + endpoint, {
    ...(body === undefined ? {} : {
      method: 'POST', body: JSON.stringify(body),
      headers: { 'Content-Type': 'application/json', 'X-AGL-Local-Test': '1' },
    }),
    redirect: 'error',
    signal: AbortSignal.timeout(15000),
  });
  const result = await response.json();
  if (!response.ok || result.code !== 0) throw new Error(result.message || `HTTP ${response.status}`);
  return result.data;
}

const plan = await api(evidenceEndpoint);
if (plan.candidateId !== candidateId || plan.profile !== 'survival-web-restart-frame-v1') {
  throw new Error('The API returned a different browser acceptance plan');
}
if (!plan.checks.some(check => check.field) || plan.duration < 1 || plan.duration > 30) {
  console.log(JSON.stringify({ candidateId, outcome: 'NOT_TESTED', reason: 'No supported probe or round outside 1–30 seconds' }));
} else {
  const { chromium } = await import(pathToFileURL(path.resolve(playwrightModule)));
  const preview = await api(candidate + '/preview', {});
  if (preview.candidateId !== candidateId || !/^http:\/\/127\.0\.0\.1:[0-9]{1,5}\/[a-f0-9-]{36}\/$/.test(preview.url)) {
    throw new Error('The API returned a different preview');
  }
  const browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true });
  try {
    // The preview's opaque CSP sandbox already disables service workers. Playwright's
    // service-worker interception would itself raise an error when accessing that API.
    const context = await browser.newContext({ acceptDownloads: false });
    const allowed = new Set(['', 'index.html', 'game.mjs', 'engine.mjs', 'style.css', 'config.json']
      .map(file => new URL(file, preview.url).href));
    await context.route('**/*', route => {
      const request = route.request();
      return request.method() === 'GET' && allowed.has(request.url()) ? route.continue() : route.abort();
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(preview.url, { timeout: 10000 });
    await page.getByRole('button', { name: '开始游戏', exact: true }).click({ timeout: 5000 });
    await page.waitForFunction(() => /胜利|失败/.test(document.querySelector('#status')?.textContent), null,
      { timeout: (plan.duration + 8) * 1000 });
    const settledStatus = await page.locator('#status').innerText();
    // Observe the first HUD mutation after a real click. MutationObserver runs before the next frame.
    await page.evaluate(() => {
      const hud = document.querySelector('#hud');
      document.querySelector('#restart').addEventListener('click', () => {
        const observer = new MutationObserver(() => {
          observer.disconnect();
          window.__aglRestartObservation = {
            hud: hud.textContent,
            restartStatus: document.querySelector('#status').textContent,
            observedAt: new Date().toISOString(),
          };
        });
        observer.observe(hud, { childList: true, characterData: true, subtree: true });
      }, { capture: true, once: true });
    });
    await page.getByRole('button', { name: '重新开始', exact: true }).click({ timeout: 5000 });
    await page.waitForFunction(() => window.__aglRestartObservation, null, { timeout: 5000 });
    const observation = await page.evaluate(() => window.__aglRestartObservation);
    if (errors.length) throw new Error(`Browser execution failed: ${errors[0]}`);
    const saved = await api(evidenceEndpoint, {
      executionId: randomUUID(),
      manifestHash: plan.manifestHash,
      archiveSha256: plan.archiveSha256,
      browser: `Chromium ${browser.version()}`,
      settledStatus,
      ...observation,
    });
    console.log(JSON.stringify(saved));
  } finally {
    await browser.close();
  }
}
