export function createGame(config){
 const state={phase:'playing',time:0,hp:100,resource:0,score:0,x:400,y:260,enemies:[],bullets:[],spawn:0,shot:0,shield:0};
 let seed=config.seed>>>0;const random=()=>{seed=(1664525*seed+1013904223)>>>0;return seed/4294967296;};
 function step(dt,input={}){
  if(state.phase!=='playing')return;dt=Math.max(0,Math.min(dt,.05));state.time+=dt;
  let dx=(input.right?1:0)-(input.left?1:0),dy=(input.down?1:0)-(input.up?1:0),len=Math.hypot(dx,dy)||1;
  state.x=Math.max(14,Math.min(786,state.x+dx/len*210*dt));state.y=Math.max(14,Math.min(506,state.y+dy/len*210*dt));
  state.resource=Math.max(0,state.resource-dt*(config.mechanic==='heat-burst'?14:0));
  state.shield=Math.max(0,state.shield-dt);
  if(config.mechanic==='shield-dash')state.resource=Math.min(100,state.resource+dt*20);
  if(input.action){
   if(config.mechanic==='heat-burst'&&state.resource<=55){state.resource+=45;for(let a=0;a<12;a++)state.bullets.push({x:state.x,y:state.y,vx:Math.cos(a*Math.PI/6)*400,vy:Math.sin(a*Math.PI/6)*400});}
   if(config.mechanic==='shield-dash'&&state.resource>=60){state.resource-=60;state.shield=1;state.x=Math.max(14,Math.min(786,state.x+dx/len*90));state.y=Math.max(14,Math.min(506,state.y+dy/len*90));}
  }
  state.spawn-=dt;if(state.spawn<=0){state.spawn=config.spawnInterval;let a=random()*Math.PI*2;state.enemies.push({x:400+Math.cos(a)*480,y:260+Math.sin(a)*360});}
  state.shot-=dt;
  if(state.shot<=0&&state.enemies.length&&(config.mechanic!=='heat-burst'||state.resource<90)){
   const enemy=state.enemies.reduce((a,b)=>Math.hypot(a.x-state.x,a.y-state.y)<Math.hypot(b.x-state.x,b.y-state.y)?a:b);
   const d=Math.hypot(enemy.x-state.x,enemy.y-state.y)||1;state.bullets.push({x:state.x,y:state.y,vx:(enemy.x-state.x)/d*420,vy:(enemy.y-state.y)/d*420});state.shot=.3;
   if(config.mechanic==='heat-burst')state.resource=Math.min(100,state.resource+6);
  }
  for(const b of state.bullets){b.x+=b.vx*dt;b.y+=b.vy*dt;}
  state.bullets=state.bullets.filter(b=>b.x>-40&&b.x<840&&b.y>-40&&b.y<560);
  state.enemies=state.enemies.filter(e=>{
   const d=Math.hypot(state.x-e.x,state.y-e.y)||1;e.x+=(state.x-e.x)/d*config.enemySpeed*dt;e.y+=(state.y-e.y)/d*config.enemySpeed*dt;
   const bullet=state.bullets.find(b=>Math.hypot(e.x-b.x,e.y-b.y)<18);
   if(bullet){state.bullets.splice(state.bullets.indexOf(bullet),1);state.score+=10;return false;}
   if(d<24){if(state.shield<=0)state.hp=Math.max(0,state.hp-22);return false;}return true;
  });
  if(state.hp<=0)state.phase='defeat';else if(state.time>=config.duration)state.phase='victory';
 }
 return {state,step};
}
