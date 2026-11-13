// Minimal independent test producer for the Lab protocol; not a gameplay benchmark.
import fs from 'node:fs';
import path from 'node:path';
const resultPath=process.env.AGL_TEST_RESULT_PATH;
const runId=process.env.AGL_TEST_RUN_ID;
if(!resultPath||!runId)throw new Error('Run this registered target through AI Game Lab');
const config=JSON.parse(fs.readFileSync('config.json','utf8'));
const checks=[
 {id:'schema-version',title:'配置格式版本',status:config.schemaVersion===1?'PASS':'FAIL',expected:'schemaVersion = 1',actual:String(config.schemaVersion),severity:'MEDIUM',category:'UNEXPECTED_STATE'},
 {id:'player-positive-health',title:'玩家初始生命值必须为正',status:Number.isFinite(config.player?.maxHp)&&config.player.maxHp>0?'PASS':'FAIL',expected:'player.maxHp > 0',actual:String(config.player?.maxHp),severity:'HIGH',category:'INVARIANT_VIOLATION'},
];
fs.mkdirSync('output',{recursive:true});
fs.writeFileSync('output/normalized-config.json',JSON.stringify(config,null,2));
const passed=checks.every(check=>check.status==='PASS');
fs.mkdirSync(path.dirname(resultPath),{recursive:true});
fs.writeFileSync(resultPath,JSON.stringify({runId,status:passed?'PASS':'FAIL',coverageComplete:true,checks},null,2));
console.log(JSON.stringify({runId,status:passed?'PASS':'FAIL',checks:checks.length}));
if(!passed)process.exitCode=1;
