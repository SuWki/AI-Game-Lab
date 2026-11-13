import path from 'node:path';import {instantiate} from './instantiate.mjs';
const [base,id,revisionText,output]=process.argv.slice(2),revision=Number(revisionText);
if(!base||!id||!output||!Number.isInteger(revision)||revision<1)throw Error('Usage: node from-artifact.mjs <local-api-origin> <artifact-id> <revision> <output-root>');
const origin=new URL(base);if(!['http:','https:'].includes(origin.protocol)||!['127.0.0.1','localhost','[::1]'].includes(origin.hostname)||origin.username||origin.password)throw Error('A local API origin is required');
const response=await fetch(new URL(`/api/production-artifacts/${encodeURIComponent(id)}/revisions/${revision}`,origin),{signal:AbortSignal.timeout(10000)});
const body=await response.json();if(!response.ok||body.code!==0)throw Error(JSON.stringify(body));
console.log(JSON.stringify(await instantiate(body.data,path.resolve(output)),null,2));
