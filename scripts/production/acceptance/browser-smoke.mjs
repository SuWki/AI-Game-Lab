// Independent runner: kept outside editable generated projects and template runtime.
import fs from 'node:fs/promises';import path from 'node:path';import {pathToFileURL} from 'node:url';import {spawn} from 'node:child_process';
const [workspace,playwrightModule,browserExecutable,portText='4186',evidenceDirectory]=process.argv.slice(2);
if(!workspace||!playwrightModule||!browserExecutable||!evidenceDirectory)throw Error('Usage: browser-smoke.mjs <workspace> <playwright-index.mjs> <browser.exe> <port> <evidence-dir>');
const port=Number(portText),url=`http://127.0.0.1:${port}`;await fs.mkdir(evidenceDirectory,{recursive:true});
const config=JSON.parse(await fs.readFile(path.join(workspace,'config.json'),'utf8'));if(config.duration>30)throw Error('Smoke profile must declare a round of at most 30 seconds');
const child=spawn(process.execPath,['serve-project.mjs',String(port)],{cwd:workspace,windowsHide:true,stdio:['ignore','pipe','pipe']});let log='',spawnError;child.stdout.on('data',d=>log+=d);child.stderr.on('data',d=>log+=d);child.on('error',e=>spawnError=e);
let browser;const checks=[],errors=[];
try{
 const deadline=Date.now()+15000;while(!log.includes(url)){if(spawnError)throw spawnError;if(child.exitCode!==null||Date.now()>deadline)throw Error('Preview not ready: '+log);await new Promise(r=>setTimeout(r,100));}
 const {chromium}=await import(pathToFileURL(path.resolve(playwrightModule)));browser=await chromium.launch({executablePath:browserExecutable,headless:true,args:['--disable-gpu']});const page=await browser.newPage({viewport:{width:1100,height:920}});page.on('pageerror',e=>errors.push(String(e)));
 await page.goto(url);await page.getByRole('heading',{name:config.title,exact:true}).waitFor();checks.push('build-load');
 await page.getByRole('button',{name:'开始游戏',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#hud').textContent.includes('时间 0.'));checks.push('start');
 await page.keyboard.down('d');await page.waitForTimeout(300);await page.keyboard.up('d');await page.keyboard.press('Space');
 await page.waitForFunction(()=>/胜利|失败/.test(document.querySelector('#status').textContent),{timeout:(config.duration+8)*1000});checks.push('natural-settlement');
 await page.screenshot({path:path.join(evidenceDirectory,'settlement.png'),fullPage:true});
 await page.getByRole('button',{name:'重新开始',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#status').textContent==='进行中'&&/生命 100/.test(document.querySelector('#hud').textContent)&&/得分 0/.test(document.querySelector('#hud').textContent));checks.push('restart');
 if(errors.length)throw Error(errors.join('\n'));
 await fs.writeFile(path.join(evidenceDirectory,'browser.json'),JSON.stringify({status:'PASS',method:'Real browser clicks, key input and elapsed game time; no forced state hooks',config,checks,errors,build:JSON.parse(await fs.readFile(path.join(workspace,'latest-build.json'),'utf8'))},null,2));console.log(JSON.stringify({status:'PASS',workspace,checks}));
}finally{await browser?.close();child.kill();if(child.exitCode===null)await Promise.race([new Promise(r=>child.once('exit',r)),new Promise(r=>setTimeout(r,3000))]);}
