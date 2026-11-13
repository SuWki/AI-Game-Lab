import fs from 'node:fs';

// Run against a fresh agl-engine-baseline.ts report. Exit 1 means a game finding,
// not a harness crash. This check intentionally fails on the recorded baseline.
const [input, output] = process.argv.slice(2);
if (!input) throw new Error('Usage: node check-result-consistency.mjs <engine-summary.json> [finding.json]');
const report = JSON.parse(fs.readFileSync(input, 'utf8'));
if (!Array.isArray(report.runs) || report.runs.length !== 18 || report.completed !== 18) {
  throw new Error('Expected the complete 18-run baseline; refusing a partial report');
}
const failures = report.runs.filter(({ result }) =>
  ['committed', 'matured'].includes(result?.buildStage) && result?.buildSummary?.includes('未成型')
).map(({ seed, preferredRoute, policy, result }) => ({
  seed, preferredRoute, policy,
  outcome: result.outcome, buildStage: result.buildStage,
  summary: result.summary, buildSummary: result.buildSummary,
}));
const finding = {
  id: 'GD-F01', baseline: report.baseline, checked: report.runs.length,
  rule: 'A committed or matured build must not be described as unformed solely because the run ended in defeat.',
  status: failures.length ? 'FAIL' : 'PASS', count: failures.length, failures,
};
if (output) fs.writeFileSync(output, JSON.stringify(finding, null, 2) + '\n');
console.log(JSON.stringify({ id: finding.id, checked: finding.checked, status: finding.status, count: finding.count }));
if (failures.length) process.exitCode = 1;
