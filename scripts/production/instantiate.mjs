import fs from 'node:fs/promises';import path from 'node:path';import crypto from 'node:crypto';import {fileURLToPath} from 'node:url';
import {buildProject,digest} from './build-project.mjs';
const template=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../templates/survival-web');
export function configuration(envelope){
 if(envelope.schemaVersion!==1||envelope.kind!=='GameSpec'||!envelope.artifactId||!Number.isInteger(envelope.revision)||!envelope.contentHash)throw Error('A versioned GameSpec envelope is required');
 const c=envelope.content,p=c?.systems?.survivalWeb;
 const canonical=n=>Array.isArray(n)?n.map(canonical):n!==null&&typeof n==='object'?Object.fromEntries(Object.keys(n).sort().map(k=>[k,canonical(n[k])])):n;
 if(digest(Buffer.from(JSON.stringify(canonical(c))))!==envelope.contentHash)throw Error('GameSpec content hash mismatch; use the exact persisted content');
 if(c.targetPlatform!=='web'||!p||!['heat-burst','shield-dash'].includes(p.mechanic))throw Error('Template requires web platform and systems.survivalWeb with a supported mechanic');
 function number(name,min,max){if(!Number.isFinite(p[name])||p[name]<min||p[name]>max)throw Error('Invalid template parameter: '+name);return p[name];}
 if(typeof c.title!=='string'||!c.title.trim()||c.title.length>120)throw Error('Invalid title');
 const seed=number('seed',0,4294967295);if(!Number.isInteger(seed))throw Error('Seed must be an integer');
 return {title:c.title,mechanic:p.mechanic,duration:number('duration',1,300),enemySpeed:number('enemySpeed',20,180),spawnInterval:number('spawnInterval',.3,3),seed};
}
export async function instantiate(envelope,outputRoot){
 const config=configuration(envelope),catalog=JSON.parse(await fs.readFile(path.join(template,'template.json'),'utf8'));
 const source={};for(const name of catalog.files){if(!['index.html','game.mjs','engine.mjs','style.css'].includes(name))throw Error('Unexpected template entry');const file=path.join(template,name),stat=await fs.lstat(file);if(stat.isSymbolicLink()||!stat.isFile())throw Error('Unsafe template file');source[name]=await fs.readFile(file);}
 const hashes=Object.fromEntries(Object.entries(source).map(([name,bytes])=>[name,digest(bytes)]));
 await fs.mkdir(outputRoot,{recursive:true});const parent=await fs.realpath(outputRoot),workspace=path.join(parent,crypto.randomUUID());await fs.mkdir(workspace);
 for(const [name,bytes] of Object.entries(source))await fs.writeFile(path.join(workspace,name),bytes);
 await fs.writeFile(path.join(workspace,'config.json'),JSON.stringify(config,null,2));
 await fs.writeFile(path.join(workspace,'specification.json'),JSON.stringify(envelope,null,2));
 const provenance={schemaVersion:1,template:{id:catalog.id,version:catalog.version,manifestHash:digest(await fs.readFile(path.join(template,'template.json'))),files:hashes},spec:{id:envelope.artifactId,revision:envelope.revision,hash:envelope.contentHash},mode:'deterministic-template-instantiation',limitations:['Only supported survivalWeb parameters are implemented; other specification requirements need separate implementation and acceptance.','No AI coding or quality acceptance has occurred.']};
 await fs.writeFile(path.join(workspace,'production-manifest.json'),JSON.stringify(provenance,null,2));
 for(const file of ['build-project.mjs','serve-project.mjs'])await fs.copyFile(path.join(path.dirname(fileURLToPath(import.meta.url)),file),path.join(workspace,file));
 await fs.writeFile(path.join(workspace,'package.json'),JSON.stringify({name:'agl-survival-instance',version:'1.0.0',private:true,type:'module',scripts:{build:'node build-project.mjs',start:'node serve-project.mjs'}},null,2));
 await fs.writeFile(path.join(workspace,'package-lock.json'),JSON.stringify({name:'agl-survival-instance',version:'1.0.0',lockfileVersion:3,requires:true,packages:{'':{name:'agl-survival-instance',version:'1.0.0'}}},null,2));
 await fs.writeFile(path.join(workspace,'README.md'),'# Generated survival slice\n\nRequires Node 18+ and a modern browser. No dependency install is needed.\n\nRun `node build-project.mjs`, then `node serve-project.mjs 4180` and open http://127.0.0.1:4180. WASD/arrows move; Space uses the mechanic. Start/restart controls are above the arena.\n\nAll graphics are original geometric placeholders. Single arena, no sound/save/network. This is a template slice, not confirmation that every GameSpec requirement is implemented. See specification.json and production-manifest.json.\n');
 return {workspace,build:await buildProject(workspace),provenance};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const [specFile,outputRoot]=process.argv.slice(2);if(!specFile||!outputRoot)throw Error('Usage: node instantiate.mjs <GameSpec-envelope.json> <output-root>');
 console.log(JSON.stringify(await instantiate(JSON.parse(await fs.readFile(specFile,'utf8')),outputRoot),null,2));
}
