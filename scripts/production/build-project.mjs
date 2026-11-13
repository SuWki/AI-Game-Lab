import fs from 'node:fs/promises';import path from 'node:path';import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
export const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
export async function buildProject(root){
 const names=['index.html','game.mjs','engine.mjs','style.css','config.json'];
 const inputs={};for(const name of names){const stat=await fs.lstat(path.join(root,name));if(!stat.isFile()||stat.isSymbolicLink())throw Error('Expected regular source file: '+name);inputs[name]=await fs.readFile(path.join(root,name));}
 const directory='build-'+crypto.randomUUID();await fs.mkdir(path.join(root,directory));const hashes={};
 for(const name of names){await fs.writeFile(path.join(root,directory,name),inputs[name]);hashes[name]=digest(inputs[name]);}
 const production=await fs.readFile(path.join(root,'production-manifest.json'));
 const manifest={schemaVersion:1,buildId:directory,files:hashes,productionManifestHash:digest(production),spec:JSON.parse(production).spec};
 await fs.writeFile(path.join(root,directory,'build-manifest.json'),JSON.stringify(manifest,null,2));
 await fs.writeFile(path.join(root,'latest-build.json'),JSON.stringify(manifest,null,2));return manifest;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){console.log(JSON.stringify(await buildProject(process.cwd()),null,2));}
