import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const [action, output, ...args] = process.argv.slice(2);
const origin = process.env.AGL_ACCEPTANCE_ORIGIN || 'http://127.0.0.1:8080';
const originPattern = /^http:\/\/127\.0\.0\.1:[0-9]{1,5}$/;
if (!['preflight', 'design', 'build', 'status'].includes(action) || !originPattern.test(origin)
    || (action !== 'preflight' && !output)) {
  throw new Error('Usage: provider-acceptance.mjs preflight | design <new-directory> <goal.txt> | build <directory> <spec-id> <revision> | status <directory>');
}
const pendingStates = new Set(['PENDING', 'RUNNING']);
const headers = { 'Content-Type': 'application/json', 'X-AGL-Local-Test': '1' };
async function api(route, body) {
  const response = await fetch(origin + '/api' + route, {
    headers, ...(body === undefined ? {} : { method: 'POST', body: JSON.stringify(body) }),
    redirect: 'error', signal: AbortSignal.timeout(10000),
  });
  const result = await response.json();
  if (!response.ok || result.code !== 0) throw new Error(`${route} HTTP ${response.status}: ${result.message}`);
  return result.data;
}
const read = name => fs.readFile(path.join(output, name), 'utf8').then(JSON.parse);
const save = (name, value) => fs.writeFile(path.join(output, name), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });

function blockers(runtime) {
  const reasons = [];
  if (!runtime.gatewayConfigured) reasons.push('供应商凭据未配置');
  if (runtime.gatewayMode === 'cc-switch-claude' && runtime.proxyHealthy !== true) {
    reasons.push(`CC Switch 本地代理未就绪，请检查 127.0.0.1:${runtime.proxyPort} 的 /health`);
  }
  if (runtime.retryEnabled) reasons.push('本次验收需停用 Gateway 自动重试');
  if (runtime.tokenBudget < 1 || runtime.tokenBudget > 20000) reasons.push('Run token 预算需设为 1–20000');
  if (runtime.maxResponseTokens < 1 || runtime.maxResponseTokens > 4096) reasons.push('单轮输出上限需设为 1–4096');
  if (runtime.timeoutMs < 1 || runtime.timeoutMs > 300000) reasons.push('Run 时限需设为 1–300000 毫秒');
  if (runtime.httpTimeoutSeconds > 45) reasons.push('HTTP 超时需设为 45 秒以内');
  return reasons;
}

async function wait(runId, runtime) {
  const deadline = Date.now() + runtime.timeoutMs + runtime.httpTimeoutSeconds * 2000 + 5000;
  while (Date.now() < deadline) {
    const detail = await api('/runs/' + runId);
    if (!pendingStates.has(detail.run.status)) return;
    await delay(750);
  }
}

function summarize(detail) {
  const steps = detail.steps.map(({ step }) => step);
  const events = detail.modelEvents.map(event => ({ type: event.type, ...JSON.parse(event.payloadJson) }));
  const sent = events.filter(event => event.type === 'LLM_REQUEST_SENT');
  const received = events.filter(event => event.type === 'LLM_RESPONSE_RECEIVED');
  const usageIds = new Set(received.filter(event => event.usageReported).map(event => event.stepId));
  const counted = steps.filter(step => usageIds.has(step.id));
  return {
    id: detail.run.id, taskId: detail.run.taskId, requestedModel: detail.run.model, status: detail.run.status,
    maxSteps: detail.run.maxSteps, tokenBudget: detail.run.tokenBudget, turnsRecorded: steps.length,
    gatewayDispatches: sent.length, turnsWithResponse: received.length, turnsWithReportedUsage: counted.length,
    unsettledTurns: sent.length - received.length,
    responseModels: [...new Set(received.map(event => event.responseModel).filter(Boolean))],
    modelEvents: events,
    promptTokens: counted.reduce((sum, step) => sum + step.promptTokens, 0),
    completionTokens: counted.reduce((sum, step) => sum + step.completionTokens, 0),
    totalTokens: detail.run.tokensUsed,
    elapsedMs: detail.run.startedAt && detail.run.finishedAt
      ? new Date(detail.run.finishedAt) - new Date(detail.run.startedAt) : null,
    tools: detail.steps.flatMap(row => row.toolCalls.map(call => ({ name: call.toolName, status: call.status }))),
    builderBudget: detail.builderBudget ?? null,
  };
}

async function report() {
  const runtime = await read('runtime.json');
  if (origin !== runtime.origin) throw new Error('Use the original acceptance backend');
  const report = { origin, runtime: runtime.profile, supplierBillingVerified: false };
  for (const kind of ['designer', 'builder']) {
    let run;
    try { run = await read(kind + '-run.json'); } catch (err) {
      if (err.code === 'ENOENT') continue;
      throw err;
    }
    const detail = await api('/runs/' + run.id);
    assert.equal(detail.run.taskId, run.taskId);
    report[kind] = summarize(detail);
  }
  const productionId = report.builder?.builderBudget?.productionId;
  if (productionId) {
    const production = await api('/productions/' + productionId);
    const candidates = await api('/productions/' + productionId + '/candidates');
    report.production = {
      id: productionId, status: production.status, specId: production.specId,
      specRevision: production.specRevision, specHash: production.specHash,
      phase: production.iteration?.phase,
      repairs: production.iteration?.attempts?.map(({ proposalId, status }) => ({ proposalId, status })) ?? [],
      candidates: await Promise.all(candidates.map(async candidate => {
        const result = { id: candidate.id, status: candidate.status, archiveSha256: candidate.archiveSha256 };
        if (candidate.status !== 'READY') return result;
        try {
          const review = await api('/production-candidates/' + candidate.id + '/review');
          return { ...result, reviewDecision: review.decision, reviewRevision: review.revision,
            browserExecutionId: review.browserEvidence?.executionId ?? null,
            eligibleForDelivery: review.gate.eligibleForDelivery };
        } catch {
          return { ...result, reviewDecision: 'UNAVAILABLE', eligibleForDelivery: null };
        }
      })),
    };
  }
  const filename = `report-${Date.now()}.json`;
  await save(filename, report);
  console.log(JSON.stringify({ report: path.resolve(output, filename), ...report }, null, 2));
}

if (action === 'status') {
  await report();
} else {
  const runtime = await api('/model-runtime');
  const reasons = blockers(runtime);
  if (action === 'preflight' || reasons.length) {
    console.log(JSON.stringify({ ready: reasons.length === 0, reasons, runtime }, null, 2));
    if (reasons.length) process.exitCode = 2;
  } else if (action === 'design') {
    const goal = (await fs.readFile(args[0], 'utf8')).trim();
    if (!goal || goal.length > 6000) throw new Error('Design goal must be 1–6000 characters');
    await fs.mkdir(output);
    await save('runtime.json', { origin, profile: runtime });
    const task = await api('/tasks', { title: '供应商验收 · Designer', goal, agentType: 'DESIGNER', productionDraft: true, maxSteps: 1 });
    await save('designer-task.json', { id: task.id });
    const id = await api('/tasks/' + task.id + '/runs', {});
    await save('designer-run.json', { id, taskId: task.id });
    await wait(id, runtime);
    await report();
  } else {
    const original = await read('runtime.json');
    assert.equal(origin, original.origin);
    // Older direct-mode checkpoints predate proxy diagnostics.
    const originalProfile = { gatewayMode: 'direct', proxyPort: null, proxyHealthy: null, ...original.profile };
    assert.deepEqual({ gatewayMode: 'direct', proxyPort: null, proxyHealthy: null, ...runtime },
      originalProfile, 'Acceptance runtime configuration changed');
    const designer = await read('designer-run.json');
    const detail = await api('/runs/' + designer.id);
    assert.equal(detail.run.status, 'COMPLETED');
    const [id, revision] = args;
    if (!id?.match(/^[a-f0-9-]{36}$/) || !/^[1-9][0-9]*$/.test(revision)) throw new Error('Use the exact specification ID and revision');
    const route = `/production-artifacts/${id}/revisions/${revision}`;
    const spec = await api(route);
    assert.equal(spec.taskId, designer.taskId);
    assert.equal(spec.producerRunId, designer.id, 'Specification was not imported from this Designer Run');
    const scope = await api(route + '/manufacturing-scope');
    if (scope?.contentHash !== spec.contentHash) throw new Error('Confirm this exact specification in the production page first');
    await save('builder-intent.json', { specId: id, specRevision: Number(revision), specHash: spec.contentHash });
    const task = await api('/productions/agent-task', { taskId: spec.taskId, specId: id, specRevision: Number(revision),
      maxSteps: 8, goal: '建造已确认的规格，进行独立验收，固定候选。记录实际证据和未完成项目。' });
    await save('builder-task.json', { id: task.id });
    const runId = await api('/tasks/' + task.id + '/runs', {});
    await save('builder-run.json', { id: runId, taskId: task.id });
    await wait(runId, runtime);
    await report();
  }
}
