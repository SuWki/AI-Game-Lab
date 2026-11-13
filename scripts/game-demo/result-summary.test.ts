import assert from 'node:assert/strict';
import { RunEngine } from '../src/systems/RunEngine';

// Exercise the actual engine formatter; no rendering or constructed battle needed.
const summarize = (RunEngine.prototype as any).getBuildSummary;
let checked = 0;
for (const route of ['crit', 'pierce', 'dash']) {
  for (const stage of ['unformed', 'hinted', 'committed', 'matured']) {
    const outputs = ['victory', 'defeat'].map(outcome => {
      const text = summarize.call({}, route, stage, outcome);
      if (stage === 'unformed') assert.match(text, /尚未成型|未成型/);
      else if (stage === 'hinted') assert.match(text, /初现/);
      else { assert.match(text, /成型/); assert.doesNotMatch(text, /尚未|未成型/); }
      checked++;
      return text;
    });
    assert.equal(outputs[0], outputs[1], 'Survival must not change build progression');
  }
}
assert.match(summarize.call({}, null, 'unformed', 'victory'), /通关成功/);
assert.match(summarize.call({}, null, 'unformed', 'defeat'), /未形成完整流派/);
console.log(JSON.stringify({ test: 'result-stage-outcome', checked: checked + 2, status: 'PASS' }));
