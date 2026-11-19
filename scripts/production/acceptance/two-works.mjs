import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';

const [destination, playwrightModule, executablePath, origin = 'http://127.0.0.1:8080'] = process.argv.slice(2);
if (!destination || !playwrightModule || !/^http:\/\/127\.0\.0\.1:[0-9]{1,5}$/.test(origin)) {
  throw new Error('Usage: node two-works.mjs <new-output-directory> <playwright-index.mjs> [browser] [local-api-origin]');
}
await fs.mkdir(destination);
const exec = promisify(execFile);
const probeScript = fileURLToPath(new URL('./candidate-browser.mjs', import.meta.url));
const headers = { 'Content-Type': 'application/json', 'X-AGL-Local-Test': '1' };
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const runId = randomUUID().slice(0, 8);
const works = [
  { key: 'heat', title: '余热回廊', mechanic: 'heat-burst', field: 'heat', resourceLabel: '热量', seed: 542,
    description: '移动避敌，趁热量低时使用爆发，再通过走位渡过高热阶段。' },
  { key: 'shield', title: '屏障穿梭', mechanic: 'shield-dash', field: 'energy', resourceLabel: '能量', seed: 731,
    description: '走位等待能量恢复，用护盾冲刺打开退路。' },
];

async function request(route, body) {
  const response = await fetch(origin + '/api' + route, {
    headers, ...(body === undefined ? {} : { method: 'POST', body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(15000), redirect: 'error',
  });
  if (!response.ok) throw new Error(`${route}: HTTP ${response.status}: ${await response.text()}`);
  return response;
}
async function api(route, body) {
  const result = await (await request(route, body)).json();
  assert.equal(result.code, 0, result.message);
  return result.data;
}
async function waitForProduction(route, isDone) {
  for (let index = 0; index < 100; index++) {
    const job = await api(route);
    if (isDone(job)) return job;
    if (['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(job.status)
        || ['FAIL', 'INCONCLUSIVE'].includes(job.validationDecision)) throw new Error(JSON.stringify(job));
    await delay(200);
  }
  throw new Error('Production timed out: ' + route);
}
function criterion(id, field, expected, text) {
  return { id, requirementId: 'restart', expected: text, method: 'browser',
    browserProbe: { profile: 'survival-web-restart-frame-v1', field, expected } };
}

async function playMechanic(browser, url, work) {
  assert.match(url, /^http:\/\/127\.0\.0\.1:[0-9]{1,5}\/[a-f0-9-]{36}\/$/);
  const context = await browser.newContext({ acceptDownloads: false, viewport: { width: 1024, height: 860 } });
  try {
    const files = new Set(['', 'index.html', 'game.mjs', 'engine.mjs', 'config.json', 'style.css'].map(file => new URL(file, url).href));
    await context.route('**/*', route => route.request().method() === 'GET' && files.has(route.request().url()) ? route.continue() : route.abort());
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(url);
    await page.getByRole('heading', { name: work.title, exact: true }).waitFor();
    await page.getByRole('button', { name: '开始游戏', exact: true }).click();
    const threshold = work.key === 'shield' ? 65 : 0;
    await page.waitForFunction(({ label, threshold }) => {
      const text = document.querySelector('#hud').textContent;
      return Number(text.match(new RegExp(label + ' (\\d+)'))?.[1]) >= threshold;
    }, { label: work.resourceLabel, threshold });
    const read = async () => Number((await page.locator('#hud').innerText()).match(new RegExp(work.resourceLabel + ' (\\d+)'))[1]);
    const before = await read();
    if (work.key === 'shield') await page.keyboard.down('d');
    await page.keyboard.press('Space');
    await page.waitForFunction(({ label, before, heat }) => {
      const value = Number(document.querySelector('#hud').textContent.match(new RegExp(label + ' (\\d+)'))?.[1]);
      return heat ? value >= before + 30 : value <= before - 40;
    }, { label: work.resourceLabel, before, heat: work.key === 'heat' }, { timeout: 2500 });
    if (work.key === 'shield') await page.keyboard.up('d');
    const after = await read();
    await page.screenshot({ path: path.join(destination, work.key + '-activation.png') });
    await page.waitForFunction(() => /胜利|失败/.test(document.querySelector('#status').textContent), null, { timeout: 12000 });
    assert.deepEqual(errors, []);
    return { before, after, resource: work.resourceLabel, settlement: await page.locator('#status').innerText() };
  } finally {
    await context.close();
  }
}

const { chromium } = await import(pathToFileURL(path.resolve(playwrightModule)));
const browser = await chromium.launch({ executablePath, headless: true, chromiumSandbox: true });
const summary = [];
try {
  const template = await api('/productions/template');
  for (const work of works) {
    const started = Date.now();
    const task = await api('/tasks', { title: work.title, goal: work.description, agentType: 'DESIGNER' });
    const brief = await api('/production-artifacts', {
      taskId: task.id, kind: 'ProductionBrief', content: { title: work.title, mode: 'NEW', genre: 'survival', targetPlatform: 'web',
        targetPlayer: '短局玩家', scope: '一张场景、一个专属资源机制和完整重开流程' }, inputRefs: [],
    });
    const spec = await api('/production-artifacts', {
      taskId: task.id, kind: 'GameSpec', inputRefs: [{ id: brief.artifactId, revision: brief.revision, hash: brief.contentHash }],
      content: { title: work.title, briefRef: brief.artifactId, genre: 'survival', targetPlatform: 'web', coreLoop: work.description,
        controls: ['方向键移动', '空格激活'], states: ['playing', 'victory', 'defeat'], endConditions: '存活 6 秒或生命耗尽',
        restartBehavior: '重开首帧资源、得分和时间为 0，生命为 100',
        systems: { survivalWeb: { mechanic: work.mechanic, duration: 6, enemySpeed: 70, spawnInterval: 1, seed: work.seed } },
        contentScope: ['单场景', work.mechanic], assetNeeds: ['模板几何图形'], exclusions: ['多人', '存档', '正式素材'], openQuestions: [],
        requirements: [{ id: 'restart', description: '重开首帧状态正确', acceptanceIds: ['resource', 'score', 'health', 'time'] },
          { id: 'mechanic', description: work.description, acceptanceIds: ['play-mechanic'] }],
        acceptanceCriteria: [criterion('resource', work.field, 0, '首帧资源为 0'), criterion('score', 'score', 0, '首帧得分为 0'),
          criterion('health', 'health', 100, '首帧生命为 100'), criterion('time', 'time', 0, '首帧时间为 0'),
          { id: 'play-mechanic', requirementId: 'mechanic', expected: '实际输入空格时可观察到资源变化', method: 'browser' }],
      },
    });
    const scope = await api('/production-artifacts/manufacturing-scope', {
      taskId: task.id, specId: spec.artifactId, revision: spec.revision, contentHash: spec.contentHash,
    });
    const submitted = await api('/productions', { taskId: task.id, specId: spec.artifactId, specRevision: spec.revision, requestKey: `${runId}-${work.key}` });
    const productionRoute = '/productions/' + submitted.id;
    await waitForProduction(productionRoute, job => job.status === 'BUILT');
    await api(productionRoute + '/validate', {});
    const verified = await waitForProduction(productionRoute, job => job.iteration?.phase === 'REVIEW');
    const candidate = await api(productionRoute + '/candidates', { validationRunId: verified.validationRunId });
    const candidateRoute = '/production-candidates/' + candidate.id;
    try {
      const { stdout } = await exec(process.execPath, [probeScript, candidate.id, playwrightModule, executablePath || '', origin], { timeout: 65000 });
      const report = JSON.parse(stdout);
      assert.deepEqual(report.checks.map(row => row.outcome), ['PASS', 'PASS', 'PASS', 'PASS', 'NOT_TESTED']);
      const preview = await api(candidateRoute + '/preview', {});
      const play = await playMechanic(browser, preview.url, work);
      const archive = Buffer.from(await (await request(candidateRoute + '/download')).arrayBuffer());
      assert.equal(sha(archive), candidate.archiveSha256);
      await fs.writeFile(path.join(destination, work.key + '.zip'), archive, { flag: 'wx' });
      await fs.writeFile(path.join(destination, work.key + '-game-spec.json'), JSON.stringify(spec, null, 2), { flag: 'wx' });
      await fs.writeFile(path.join(destination, work.key + '-browser.json'), JSON.stringify({ report, play }, null, 2), { flag: 'wx' });
      const review = await api(candidateRoute + '/review');
      summary.push({ name: work.title, mechanic: work.mechanic, templateVersion: template.version, templateHash: template.hash,
        specification: { id: spec.artifactId, revision: spec.revision, hash: spec.contentHash }, scopeConfirmedAt: scope.confirmedAt,
        productionId: submitted.id, candidateId: candidate.id, archiveSha256: candidate.archiveSha256, browserExecutionId: report.executionId,
        engineStatus: verified.validationDecision, browserChecks: report.checks, play, elapsedMs: Date.now() - started,
        deliveryEligible: review.gate.eligibleForDelivery, modelRequests: 0 });
    } finally {
      await api(candidateRoute + '/preview/stop', {});
    }
  }
  assert.equal(summary[0].templateHash, summary[1].templateHash);
  assert.notEqual(summary[0].archiveSha256, summary[1].archiveSha256);
  await fs.writeFile(path.join(destination, 'summary.json'), JSON.stringify(summary, null, 2), { flag: 'wx' });
  console.log(JSON.stringify(summary));
} finally {
  await browser.close();
}
