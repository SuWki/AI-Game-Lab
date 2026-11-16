// Platform-owned verification. This program and its contract are outside editable game workspaces.
import fs from 'node:fs/promises';import path from 'node:path';import {pathToFileURL,fileURLToPath} from 'node:url';import assert from 'node:assert/strict';
import {buildProject,digest} from '../build-project.mjs';
const [contractPath,contractHash,programHash,builderHash]=process.argv.slice(2),root=process.cwd();
const report={runId:process.env.AGL_TEST_RUN_ID,status:'FAIL',coverageComplete:false,scope:'survival-web-engine-v1',contractHash,checks:[]};
async function checkIntegrity(){
 if(digest(await fs.readFile(contractPath))!==contractHash)throw Error('Frozen acceptance contract changed');
 if(digest(await fs.readFile(fileURLToPath(import.meta.url)))!==programHash)throw Error('Acceptance program changed');
 if(digest(await fs.readFile(new URL('../build-project.mjs',import.meta.url)))!==builderHash)throw Error('Trusted build program changed');
}
function check(id,expected,fn){try{fn();report.checks.push({id,status:'PASS',expected,actual:'Assertions passed'});}catch(e){report.checks.push({id,status:'FAIL',expected,actual:String(e).slice(0,2000)});}}
try{
 await checkIntegrity();const contract=JSON.parse(await fs.readFile(contractPath,'utf8'));
 for(const [name,hash] of Object.entries(contract.protectedFiles)){const file=path.join(root,name),stat=await fs.lstat(file);if(!stat.isFile()||stat.isSymbolicLink()||digest(await fs.readFile(file))!==hash)throw Error('Protected input changed: '+name);}
 assert.deepEqual(JSON.parse(await fs.readFile('config.json','utf8')),contract.config);
 const build=await buildProject(root);report.validationBuild=build;
 for(const [name,hash] of Object.entries(build.files))assert.equal(digest(await fs.readFile(path.join(root,build.buildId,name))),hash);
 const {createGame}=await import(pathToFileURL(path.join(root,build.buildId,'engine.mjs')));assert.equal(typeof createGame,'function');
 const config=contract.config;const fresh=()=>createGame(structuredClone(config));
 check('initial-and-restart','Fresh rounds reset health, score, clock and resources',()=>{const a=fresh();a.step(.05,{right:true,action:true});const b=fresh();for(const [key,value] of Object.entries({phase:'playing',hp:100,score:0,time:0,resource:0,shield:0,x:400,y:260}))assert.equal(b.state[key],value);});
 check('movement','Directional movement stays in the arena',()=>{const g=fresh();g.step(.05,{right:true});assert.ok(g.state.x>400&&g.state.x<=420);const d=fresh();d.step(.05,{left:true,up:true});assert.ok(d.state.x<400&&d.state.y<260);for(let n=0;n<400;n++)d.step(.05,{left:true,up:true});assert.ok(d.state.x>=14&&d.state.y>=14);});
 check('deterministic-replay','Identical seeds and actions reproduce state',()=>{const a=fresh(),b=fresh();for(let i=0;i<80;i++){const input={right:i<10,action:i===15};a.step(.05,input);b.step(.05,input);}assert.deepEqual(a.state,b.state);});
 check('settlement','Round terminates consistently and terminal state stops advancing',()=>{const g=fresh();for(let i=0;i<Math.ceil(config.duration/.05)+2;i++)g.step(.05);assert.ok(['victory','defeat'].includes(g.state.phase));if(g.state.phase==='victory'){assert.ok(g.state.time>=config.duration);assert.ok(g.state.hp>0);}else assert.equal(g.state.hp,0);const before=structuredClone(g.state);g.step(.05,{action:true});assert.deepEqual(g.state,before);});
 check('damage-and-protection','Contact damage can defeat player; shield blocks it',()=>{const g=fresh();g.state.hp=20;g.state.shot=1;g.state.enemies.push({x:400,y:260});g.step(.01);assert.equal(g.state.phase,'defeat');assert.equal(g.state.hp,0);const shield=fresh();shield.state.shield=1;shield.state.shot=1;shield.state.enemies.push({x:400,y:260});shield.step(.01);assert.equal(shield.state.hp,100);});
 check('resource-mechanic','Configured resource mechanic has its declared effect',()=>{const g=fresh();g.state.shot=1;if(config.mechanic==='heat-burst'){g.step(.01,{action:true});assert.equal(g.state.resource,45);assert.equal(g.state.bullets.length,12);}else{g.step(.05);assert.ok(g.state.resource>0);g.state.resource=60;const x=g.state.x;g.step(.01,{right:true,action:true});assert.ok(g.state.shield>0);assert.ok(g.state.x>x+90);assert.ok(g.state.resource<1);}});
 await checkIntegrity();for(const [name,hash] of Object.entries(contract.protectedFiles))assert.equal(digest(await fs.readFile(path.join(root,name))),hash,'Protected input changed during verification');
 report.coverageComplete=true;report.status=report.checks.every(c=>c.status==='PASS')?'PASS':'FAIL';
}catch(e){report.checks.push({id:'acceptance-integrity',status:'FAIL',expected:'Frozen inputs, trusted build and runnable engine',actual:String(e).slice(0,2000)});}
if(!report.runId||!process.env.AGL_TEST_RESULT_PATH)throw Error('Use the managed platform executor');
await fs.writeFile(process.env.AGL_TEST_RESULT_PATH,JSON.stringify(report,null,2));console.log(JSON.stringify(report));if(report.status!=='PASS')process.exitCode=1;
