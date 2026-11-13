import fs from 'node:fs';
import {chromium} from 'playwright';
const out=process.env.AGL_BROWSER_OUT||'output/qa/agl-browser';fs.mkdirSync(out,{recursive:true});
const report={mode:'DOM click handlers and QA fixtures; not physical pointer or natural full run',url:process.env.AGL_GAME_URL||'http://127.0.0.1:4173/game-demo/',errors:[],checks:[],snapshots:[]};
const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,timeout:20000,args:['--disable-gpu']});
try{
 const p=await browser.newPage({viewport:{width:1440,height:960}});p.setDefaultTimeout(15000);
 p.on('pageerror',e=>report.errors.push(String(e)));p.on('console',m=>{if(m.type()==='error')report.errors.push(m.text())});
 await p.goto(report.url,{waitUntil:'networkidle'});await p.locator('[data-action="start"]').waitFor();report.checks.push({name:'home',pass:true});
 await p.locator('[data-action="start"]').dispatchEvent('click');await p.waitForTimeout(2500);
 report.snapshots.push(await p.evaluate(()=>({text:document.body.innerText.slice(0,900),state:window.__pilotBattleDebug?.(),actions:[...document.querySelectorAll('[data-action]')].map(e=>e.getAttribute('data-action'))})));
 await p.locator('[data-action="pause"]').dispatchEvent('click');await p.locator('.commercial-pause-panel').waitFor();report.checks.push({name:'pause',pass:true});await p.locator('.commercial-pause-panel [data-action="resume"]').dispatchEvent('click');await p.locator('.commercial-pause-panel').waitFor({state:'hidden'});report.checks.push({name:'resume',pass:true});
 for(const route of ['crit','pierce','dash']){
  const triggered=await p.evaluate(routeId=>window.__pilotQaSmoke?.({routeId,stage:'upgrade'}),route);
  try{if(!triggered)throw new Error('Fixture rejected');await p.locator('.choice-strip-upgrade').first().waitFor({timeout:15000});const expected={crit:'热枪首发',pierce:'穿透弹头',dash:'擦身初现'}[route];await p.getByText(expected,{exact:true}).first().waitFor();report.checks.push({name:route+'-upgrade-fixture',pass:true,triggered,expected});}catch(e){report.checks.push({name:route+'-upgrade-fixture',pass:false,triggered,error:e.message});break;}
 }
 await p.screenshot({path:out+'/final.png',timeout:15000});
 report.finalText=await p.locator('body').innerText();
}catch(e){report.failure=String(e);}finally{fs.writeFileSync(out+'/summary.json',JSON.stringify(report,null,2));console.log(JSON.stringify({checks:report.checks,errors:report.errors,failure:report.failure}));await browser.close();if(report.failure||report.errors.length||report.checks.length!==6||report.checks.some(c=>!c.pass))process.exitCode=1;}
