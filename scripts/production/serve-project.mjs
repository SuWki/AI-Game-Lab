import http from 'node:http';import fs from 'node:fs/promises';import path from 'node:path';
import crypto from 'node:crypto';
const root=process.cwd(),manifest=JSON.parse(await fs.readFile(path.join(root,'latest-build.json'),'utf8'));
if(!/^build-[a-f0-9-]+$/.test(manifest.buildId))throw Error('Invalid build directory');
const directory=path.join(root,manifest.buildId),port=Number(process.argv[2]||4180);if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid port');
const types={'.html':'text/html; charset=utf-8','.mjs':'text/javascript; charset=utf-8','.json':'application/json','.css':'text/css'};
const server=http.createServer(async(req,res)=>{try{const name=new URL(req.url,'http://localhost').pathname.slice(1)||'index.html';if(!Object.hasOwn(manifest.files,name))throw Error('Unknown build file');const file=path.join(directory,name),stat=await fs.lstat(file);if(!stat.isFile()||stat.isSymbolicLink())throw Error('Invalid build file');const bytes=await fs.readFile(file);if(crypto.createHash('sha256').update(bytes).digest('hex')!==manifest.files[name])throw Error('Build changed');res.writeHead(200,{'Content-Type':types[path.extname(name)]||'application/octet-stream','Cache-Control':'no-store'});res.end(bytes);}catch{res.writeHead(404);res.end('Not found');}});
server.listen(port,'127.0.0.1',()=>console.log(`http://127.0.0.1:${port} · ${manifest.buildId}`));
process.on('SIGINT',()=>server.close());process.on('SIGTERM',()=>server.close());
