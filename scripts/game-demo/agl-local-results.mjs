import fs from 'node:fs';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
const out=process.env.AGL_RESULTS_OUT||'output/qa/agl-local-results';fs.mkdirSync(out,{recursive:true});
const report={url:process.env.AGL_GAME_URL||'http://127.0.0.1:4173/game-demo/',mode:'local built game / QA result fixtures / DOM handlers',checks:[],errors:[]};
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--disable-gpu'],timeout:20000});
try{
 const page=await browser.newPage({viewport:{width:1440,height:960}});page.setDefaultTimeout(15000);
 page.on('pageerror',e=>report.errors.push(String(e)));
 await page.goto(report.url,{waitUntil:'networkidle'});
 await page.locator('[data-action="start"]').dispatchEvent('click');
 await page.locator('[data-action="pause"]').waitFor();
 for(const routeId of ['crit','pierce','dash'])for(const resultMode of ['victory','defeat']){
  assert.equal(await page.evaluate(config=>window.__pilotQaSmoke(config),{routeId,stage:'result',resultMode}),true);
  await page.locator(`.result-screen.is-${resultMode}`).waitFor();
  await page.locator('[data-action="restart"]').waitFor();
  const text=await page.locator('.result-screen').innerText();
  assert.ok(text.includes(resultMode==='victory'?'通关成功':'通关失败'));
  let exportVerified=false,downloadName=null;
  if(process.env.AGL_EXPECT_REVIEW==='1'||await page.locator('[data-action="export-review"]').count()){
  const [download]=await Promise.all([
   page.waitForEvent('download'),
   page.locator('[data-action="export-review"]').dispatchEvent('click'),
  ]);
  const reviewPath=`${out}/${routeId}-${resultMode}-review.json`;
  await download.saveAs(reviewPath);
  const review=JSON.parse(fs.readFileSync(reviewPath,'utf8'));
  assert.equal(review.schemaVersion,1);
  assert.equal(review.facts.routeId,routeId);
  assert.equal(review.facts.outcome,resultMode);
  assert.ok(Array.isArray(review.facts.routeTrace));
  assert.ok(Array.isArray(review.facts.selectedUpgrades));
  assert.equal(review.interpretation.source,'game-generated');
  assert.equal(review.availability.inputReplay,false);
  assert.equal(review.game.sourceRevision,null);
  if(['committed','matured'].includes(review.facts.buildStage))assert.doesNotMatch(review.interpretation.buildSummary,/未成型/);
  exportVerified=true;downloadName=download.suggestedFilename();
  }
  report.checks.push({routeId,resultMode,pass:true,exportVerified,downloadName,text});
 }
 report.metrics=await page.evaluate(()=>JSON.parse(localStorage.getItem('commercial_pilot_metrics_v1')||'null'));
 await page.screenshot({path:out+'/result.png',timeout:15000});
 assert.equal(report.errors.length,0);
}catch(e){report.failure=String(e);process.exitCode=1;}finally{
 fs.writeFileSync(out+'/summary.json',JSON.stringify(report,null,2));
 console.log(JSON.stringify({checks:report.checks.map(({text,...c})=>c),errors:report.errors,failure:report.failure}));
 await browser.close();
}
