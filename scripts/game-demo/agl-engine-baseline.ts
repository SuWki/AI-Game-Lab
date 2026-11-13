import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import Clamp from '../node_modules/phaser/src/math/Clamp.js';
(globalThis as any).Phaser = { Math: { Clamp } };
import { RunEngine } from '../src/systems/RunEngine';
import { ConfigLoader } from '../src/systems/ConfigLoader';
import { MetricsTracker } from '../src/systems/MetricsTracker';
import { MetaProgression } from '../src/systems/MetaProgression';
import type { Services } from '../src/game/types';

// Test-only clock/RNG injection; production code, stats, health and outcomes are unchanged.
let virtualMs=0;
const realRandom=Math.random, realNow=Date.now, realPerf=globalThis.performance;
Date.now=()=>1700000000000+virtualMs;
Object.defineProperty(globalThis,'performance',{configurable:true,value:{now:()=>virtualMs}});
function rng(seed:number){let a=seed|0;return()=>{a|=0;a=a+0x6D2B79F5|0;let t=Math.imul(a^a>>>15,1|a);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296;};}
function simulate(seed:number, route:string, policy:string){
 virtualMs=0;Math.random=rng(seed);
 const map=new Map<string,string>();const storage={getItem:(k:string)=>map.get(k)??null,setItem:(k:string,v:string)=>map.set(k,v),removeItem:(k:string)=>map.delete(k),clear:()=>map.clear(),key:(i:number)=>[...map.keys()][i]??null,get length(){return map.size()}};
 const metrics=new MetricsTracker(storage);metrics.beginRunFromMenu();
 const engine=new RunEngine({metrics,configLoader:new ConfigLoader(),meta:new MetaProgression(storage)} as Services);
 const picks:string[]=[];const violations:string[]=[];let ticks=0;let bossSeen=false;let maxEnemies=0;
 for(;ticks<54000;ticks++){
  const s=engine.getState();if(s.status==='result')break;
  const options=s.status==='nodeChoice'?s.nodeOptions:s.status==='upgradeChoice'?s.upgradeChoices:s.status==='eventChoice'?s.currentEvent?.options:[];
  if(options?.length){
   const ranked=[...options].sort((a:any,b:any)=>{const score=(x:any)=>(x.id.includes(route)?10:0)+(x.type==='upgrade'?4:0)+(x.type==='event'?2:0);return score(b)-score(a)});
   const chosen=ranked[0];picks.push(chosen.id);
   if(s.status==='nodeChoice')engine.chooseNode(chosen.id);else if(s.status==='upgradeChoice')engine.chooseUpgrade(chosen.id);else engine.chooseEventOption(chosen.id);
  }
  const b=s.battle;
  if(b){
   bossSeen ||= s.currentNode?.type==='boss';maxEnemies=Math.max(maxEnemies,b.enemies.length);
   const angle=ticks/180;let tx=1200+Math.cos(angle)*450,ty=780+Math.sin(angle)*300;
   if(policy==='seek-xp' && b.experienceOrbs.length){const orb=[...b.experienceOrbs].sort((a,c)=>Math.hypot(a.x-b.playerX,a.y-b.playerY)-Math.hypot(c.x-b.playerX,c.y-b.playerY))[0];tx=orb.x;ty=orb.y;}
   engine.setInputState({left:b.playerX>tx+12,right:b.playerX<tx-12,up:b.playerY>ty+12,down:b.playerY<ty-12});
   if(![b.playerX,b.playerY,s.stats.hp,s.stats.maxHp,b.remainingSec].every(Number.isFinite)){violations.push('non-finite state');break;}
   if(s.stats.hp>s.stats.maxHp+0.01){violations.push('hp exceeds max');break;}
   if(b.playerX<0||b.playerX>2400||b.playerY<0||b.playerY>1560){violations.push('player out of arena');break;}
  }
  virtualMs+=1000/60;engine.tick(1000/60);engine.drainAnnouncements();
 }
 const s=engine.getState();
 return {seed,preferredRoute:route,policy,ticks,simulatedSec:Number((virtualMs/1000).toFixed(2)),status:s.status,bossSeen,maxEnemies,picks,violations,result:s.result};
}
const out='output/qa/agl-engine';fs.mkdirSync(out,{recursive:true});const runs=[];
const revision=execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim();
if(revision!=='bf3e44bf2c32fd3a585cc6b982531f16ef807d13')throw new Error('Unsupported demo revision: '+revision);
const sha=(path:string)=>createHash('sha256').update(fs.readFileSync(path)).digest('hex');
try{
 for(const policy of ['orbit','seek-xp'])for(const route of ['crit','pierce','dash'])for(const seed of [11,29,47]){const r=simulate(seed,route,policy);runs.push(r);console.log(JSON.stringify({seed,route,policy,status:r.status,outcome:r.result?.outcome,boss:r.bossSeen,seconds:r.simulatedSec,violations:r.violations}));}
 const replay=simulate(11,'crit','orbit');const deterministic=JSON.stringify(replay)===JSON.stringify(runs[0]);
 const report={baseline:'bf3e44bf2c32fd3a585cc6b982531f16ef807d13',mode:'real RunEngine, fixed 60Hz, test-only seeded RNG/clock, no renderer, no invulnerability',deterministicReplay:deterministic,total:runs.length,completed:runs.filter(r=>r.status==='result').length,wins:runs.filter(r=>r.result?.outcome==='victory').length,bossReached:runs.filter(r=>r.bossSeen).length,invariantFailures:runs.filter(r=>r.violations.length).length,runs};
 const evidence={...report,source:{revision,engineSha256:sha('src/systems/RunEngine.ts'),lockSha256:sha('package-lock.json'),harnessSha256:sha('tools/agl-engine-baseline.ts'),policyVersion:1,clockHz:60,seeds:[11,29,47],replayScope:'seed 11 / crit / orbit'}};
 fs.writeFileSync(out+'/summary.json',JSON.stringify(evidence,null,2));console.log(JSON.stringify({...evidence,runs:undefined}));if(!deterministic||report.invariantFailures||report.completed!==report.total)process.exitCode=1;
}finally{Math.random=realRandom;Date.now=realNow;Object.defineProperty(globalThis,'performance',{configurable:true,value:realPerf});}
