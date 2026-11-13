import fs from 'node:fs';
import assert from 'node:assert/strict';
import { createRunReview } from '../src/systems/RunReview';
const baseline=JSON.parse(fs.readFileSync('output/qa/agl-engine/summary.json','utf8'));
assert.equal(baseline.completed,18);
for(const run of baseline.runs){
 const result=run.result;
 const before=JSON.stringify(result);
 const review=createRunReview(result,'2026-11-12T00:00:00.000Z');
 assert.equal(review.facts.outcome,result.outcome);
 assert.deepEqual(review.facts.routeTrace,result.routeTrace);
 assert.deepEqual(review.facts.selectedUpgrades,result.selectedUpgrades);
 assert.deepEqual(review.facts.eventHistory,result.eventHistory);
 assert.equal(review.facts.endingReason,result.endingReason);
 assert.equal(review.interpretation.summary,result.summary);
 assert.equal('summary' in review.facts,false);
 assert.equal(review.availability.damageTimeline,false);
 assert.equal(review.availability.inputReplay,false);
 review.facts.routeTrace.length=0;
 assert.equal(JSON.stringify(result),before,'Export must be detached from live result');
}
console.log(JSON.stringify({test:'run-review-real-results',checked:18,status:'PASS'}));
