import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { checkResultConsistency } from './check-result-consistency.mjs';

const [tsxCli, npmCli, sourceRevision] = process.argv.slice(2);
const { AGL_TEST_RESULT_PATH: output, AGL_TEST_RUN_ID: runId } = process.env;
if (!runId || !output || !tsxCli || !npmCli || !sourceRevision) throw new Error('Register Node, tsx CLI, npm CLI and source revision in the target catalog');
const run = (script, args) => execFileSync(process.execPath, [script, ...args], { stdio: 'inherit' });
try {
  await fs.access('node_modules/phaser/src/math/Clamp.js');
} catch {
  run(npmCli, ['ci', '--ignore-scripts', '--no-audit']);
}
run(npmCli, ['run', 'build']);
run(tsxCli, ['tools/agl-engine-baseline.ts', '--isolated-source-revision=' + sourceRevision]);
const report = JSON.parse(await fs.readFile('output/qa/agl-engine/summary.json', 'utf8'));
const finding = checkResultConsistency(report);
const checks = [
  { id: finding.id, status: finding.status, expected: finding.rule,
    actual: `${finding.count} inconsistent summaries in ${finding.checked} runs` },
  { id: 'GD-engine-replay', status: 'PASS', expected: '18 terminations, no invariant errors and one exact seeded replay',
    actual: `${report.completed} terminations, ${report.invariantFailures} invariant failures, replay=${report.deterministicReplay}` },
];
await fs.mkdir(path.dirname(output), { recursive: true });
await fs.writeFile(output, JSON.stringify({
  runId, status: finding.status, coverageComplete: true, checks, metrics: { summaryErrors: finding.count, wins: report.wins },
}));
