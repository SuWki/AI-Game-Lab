import {createGame} from './engine.mjs';
const config=await fetch('./config.json').then(r=>{if(!r.ok)throw Error('Missing game configuration');return r.json();});
document.title=config.title;document.querySelector('#title').textContent=config.title;
document.querySelector('#instructions').textContent=config.mechanic==='heat-burst'?'热量爆发：自动射击积累热量，空格释放环形弹幕；过热时停止自动射击。':'护盾冲刺：能量随时间恢复，空格消耗能量沿移动方向冲刺并短暂无敌。';
const canvas=document.querySelector('canvas'),ctx=canvas.getContext('2d'),status=document.querySelector('#status'),hud=document.querySelector('#hud');
let game=null,last=0,action=false;const held=new Set();
addEventListener('keydown',e=>{if(['ArrowUp','ArrowDown','ArrowLeft','ArrowRight',' '].includes(e.key))e.preventDefault();held.add(e.key.toLowerCase());if(e.key===' '&&!e.repeat)action=true;});
addEventListener('keyup',e=>held.delete(e.key.toLowerCase()));addEventListener('blur',()=>{held.clear();action=false;});
document.addEventListener('visibilitychange',()=>{held.clear();action=false;last=0;});
function start(){game=createGame(config);held.clear();action=false;last=0;status.textContent='进行中';}
document.querySelector('#start').onclick=start;document.querySelector('#restart').onclick=start;
function frame(now){
 const dt=last?Math.min((now-last)/1000,.05):0;last=now;
 if(game&&!document.hidden){game.step(dt,{left:held.has('a')||held.has('arrowleft'),right:held.has('d')||held.has('arrowright'),up:held.has('w')||held.has('arrowup'),down:held.has('s')||held.has('arrowdown'),action});action=false;}
 ctx.clearRect(0,0,800,520);ctx.strokeStyle='#253252';for(let x=0;x<800;x+=40){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,520);ctx.stroke();}for(let y=0;y<520;y+=40){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(800,y);ctx.stroke();}
 if(game){const s=game.state;ctx.fillStyle=s.shield>0?'#fef08a':'#7dd3fc';ctx.beginPath();ctx.arc(s.x,s.y,s.shield>0?22:12,0,Math.PI*2);ctx.fill();ctx.fillStyle='#fb7185';for(const e of s.enemies)ctx.fillRect(e.x-10,e.y-10,20,20);ctx.fillStyle='#a7f3d0';for(const b of s.bullets){ctx.beginPath();ctx.arc(b.x,b.y,4,0,Math.PI*2);ctx.fill();}
 hud.textContent=`生命 ${s.hp} · ${config.mechanic==='heat-burst'?'热量':'能量'} ${Math.round(s.resource)} · 得分 ${s.score} · 时间 ${s.time.toFixed(1)} / ${config.duration} 秒`;
 if(s.phase!=='playing'){status.textContent=s.phase==='victory'?'胜利！存活目标达成':'失败，点击重新开始';ctx.fillStyle='#10172bcc';ctx.fillRect(0,200,800,100);ctx.fillStyle='#fff';ctx.font='30px system-ui';ctx.textAlign='center';ctx.fillText(s.phase==='victory'?'SURVIVED':'TRY AGAIN',400,260);}
 }else{ctx.fillStyle='#7dd3fc';ctx.font='24px system-ui';ctx.textAlign='center';ctx.fillText('点击开始游戏',400,260);}requestAnimationFrame(frame);
}requestAnimationFrame(frame);
