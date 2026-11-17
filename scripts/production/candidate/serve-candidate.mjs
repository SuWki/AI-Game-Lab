// Standalone, dependency-free preview of the frozen build. No source execution or rebuild.
import fs from 'node:fs/promises';import path from 'node:path';import http from 'node:http';import crypto from 'node:crypto';import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url)),hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const manifest=JSON.parse(await fs.readFile(path.join(root,'candidate-manifest.json'),'utf8')),files=new Map();
for(const [name,expected] of Object.entries(manifest.files)){
 const target=path.resolve(root,name);if(!target.startsWith(root+path.sep)||path.isAbsolute(name))throw Error('Invalid package path');
 let cursor=root;for(const part of name.split('/')){cursor=path.join(cursor,part);if((await fs.lstat(cursor)).isSymbolicLink())throw Error('Symlink in package');}
 const bytes=await fs.readFile(target);if(hash(bytes)!==expected)throw Error('Package hash mismatch: '+name);if(name.startsWith('build/'))files.set('/'+name.slice(6),bytes);
}
const port=Number(process.argv[2]||4180);if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid loopback port');const origin=`http://127.0.0.1:${port}`,prefix='/'+crypto.randomUUID()+'/';
http.createServer((req,res)=>{
 const name=(req.url||'/').split('?')[0],key=name===prefix?'/index.html':name.startsWith(prefix)?'/'+name.slice(prefix.length):'',bytes=files.get(key);
 res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Access-Control-Allow-Origin','null');res.setHeader('Cache-Control','no-store');res.setHeader('Cross-Origin-Opener-Policy','same-origin');res.setHeader('Referrer-Policy','no-referrer');
 res.setHeader('Content-Security-Policy',`sandbox allow-scripts; default-src 'none'; script-src ${origin}; style-src ${origin}; connect-src ${origin}; img-src ${origin} data:; frame-ancestors 'none'; form-action 'none'; base-uri 'none'`);
 if(!['GET','HEAD'].includes(req.method)){res.writeHead(405).end();return;}if(!bytes){res.writeHead(404).end();return;}
 res.setHeader('Content-Type',key.endsWith('.html')?'text/html; charset=utf-8':key.endsWith('.css')?'text/css; charset=utf-8':key.endsWith('.json')?'application/json':'text/javascript; charset=utf-8');res.writeHead(200);res.end(req.method==='HEAD'?undefined:bytes);
}).listen(port,'127.0.0.1',()=>console.log(origin+prefix));
