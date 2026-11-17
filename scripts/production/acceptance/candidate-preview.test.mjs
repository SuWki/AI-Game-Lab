import {test} from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';import crypto from 'node:crypto';import net from 'node:net';import {spawn} from 'node:child_process';
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
async function fixture(){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'agl-candidate-node-'));const script=await fs.readFile(new URL('../candidate/serve-candidate.mjs',import.meta.url));
 const files={'build/index.html':Buffer.from('<h1>Fixed candidate</h1>'),'build/game.mjs':Buffer.from('export const ready=true;'),'build/engine.mjs':Buffer.from('export const speed=220;'),'build/style.css':Buffer.from('body{color:green}'),'build/config.json':Buffer.from('{"seed":42}'),'serve-candidate.mjs':script};
 for(const [name,bytes]of Object.entries(files)){await fs.mkdir(path.dirname(path.join(root,name)),{recursive:true});await fs.writeFile(path.join(root,name),bytes);}
 await fs.writeFile(path.join(root,'candidate-manifest.json'),JSON.stringify({files:Object.fromEntries(Object.entries(files).map(([name,bytes])=>[name,hash(bytes)]))}));return root;
}
async function freePort(){const server=net.createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;await new Promise(r=>server.close(r));return port;}
async function cleanup(root){assert.equal(path.dirname(path.resolve(root)),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('agl-candidate-node-'));await fs.rm(root,{recursive:true,force:true});}
test('portable candidate serves fixed assets only and remains alive across requests',{timeout:20000},async()=>{
 const root=await fixture(),port=await freePort(),child=spawn(process.execPath,['serve-candidate.mjs',String(port)],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});let output='',errors='';child.stdout.on('data',d=>output+=d);child.stderr.on('data',d=>errors+=d);
 try{
  const end=Date.now()+10000;while(!output.startsWith('http')){if(child.exitCode!==null||Date.now()>end)throw Error('Preview did not start: '+errors);await new Promise(r=>setTimeout(r,30));}const url=output.trim();assert.ok(new URL(url).pathname.length>30);
  for(const name of ['index.html','game.mjs','engine.mjs','style.css','config.json']){const r=await fetch(url+name,{signal:AbortSignal.timeout(3000)});assert.equal(r.status,200);assert.equal(hash(Buffer.from(await r.arrayBuffer())),hash(await fs.readFile(path.join(root,'build',name))));}
  assert.equal((await fetch(url)).status,200);assert.equal((await fetch(new URL('/',url))).status,404);assert.equal((await fetch(url+'source/engine.mjs')).status,404);assert.equal((await fetch(url,{method:'POST'})).status,405);assert.match((await fetch(url)).headers.get('content-security-policy'),/sandbox allow-scripts/);assert.equal(child.exitCode,null);
 }finally{child.kill();if(child.exitCode===null)await new Promise(r=>child.once('exit',r));await cleanup(root);}
});
test('portable candidate refuses tampered package files before listening',{timeout:15000},async()=>{
 const root=await fixture();await fs.writeFile(path.join(root,'build/engine.mjs'),'changed');const child=spawn(process.execPath,['serve-candidate.mjs',String(await freePort())],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});let output='',errors='';child.stdout.on('data',d=>output+=d);child.stderr.on('data',d=>errors+=d);
 try{const code=await new Promise(r=>child.once('exit',r));assert.notEqual(code,0);assert.match(errors,/Package hash mismatch/);assert.equal(output,'');}finally{child.kill();await cleanup(root);}
});
