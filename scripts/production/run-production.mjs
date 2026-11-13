// Trusted platform command; reuses the common local execution protocol.
import fs from 'node:fs/promises';import path from 'node:path';import {fileURLToPath} from 'node:url';
import {instantiate} from './instantiate.mjs';import {digest} from './build-project.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../templates/survival-web');
const names=['engine.mjs','game.mjs','index.html','style.css','template.json'];
const fingerprint=async()=>digest(Buffer.from((await Promise.all(names.map(async n=>n+':'+digest(await fs.readFile(path.join(root,n)))))).join('\n')));
const report={runId:process.env.AGL_TEST_RUN_ID,status:'FAIL',coverageComplete:true,checks:[]};
try{
 const input=JSON.parse(await fs.readFile('input.json','utf8'));
 if(await fingerprint()!==input.templateHash)throw Error('Registered template changed before execution');
 const result=await instantiate(input.spec,path.resolve('output'));
 const produced={...result.provenance.template.files,'template.json':result.provenance.template.manifestHash};
 if(digest(Buffer.from(names.map(n=>n+':'+produced[n]).join('\n')))!==input.templateHash)throw Error('Template changed during instantiation');
 if(await fingerprint()!==input.templateHash)throw Error('Registered template changed during execution');
 for(const [name,hash] of Object.entries(result.build.files)){if(digest(await fs.readFile(path.join(result.workspace,result.build.buildId,name)))!==hash)throw Error('Built output mismatch: '+name);}
 report.status='PASS';report.production=result;report.checks.push({id:'template-build-integrity',status:'PASS',expected:'Pinned template and generated build fingerprints match',actual:'Matched'});
}catch(e){process.exitCode=1;report.checks.push({id:'template-build-integrity',status:'FAIL',expected:'Successful pinned template build',actual:String(e)});}
if(!process.env.AGL_TEST_RESULT_PATH||!report.runId)throw Error('Use the platform managed executor');
await fs.writeFile(process.env.AGL_TEST_RESULT_PATH,JSON.stringify(report,null,2));console.log(JSON.stringify(report));
