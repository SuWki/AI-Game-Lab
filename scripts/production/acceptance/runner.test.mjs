import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';import {execFile} from 'node:child_process';import {promisify} from 'node:util';
test('managed production rejects a changed template before creating a project',async()=>{
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'agl-runner-')),result=path.join(directory,'result.json');
 await fs.writeFile(path.join(directory,'input.json'),JSON.stringify({templateHash:'old-template',spec:{}}));
 const runner=fileURLToPath(new URL('../run-production.mjs',import.meta.url));
 await assert.rejects(promisify(execFile)(process.execPath,[runner],{cwd:directory,env:{...process.env,AGL_TEST_RUN_ID:'run-test',AGL_TEST_RESULT_PATH:result}}));
 const report=JSON.parse(await fs.readFile(result,'utf8'));assert.equal(report.runId,'run-test');assert.equal(report.status,'FAIL');assert.match(report.checks[0].actual,/template changed/);await assert.rejects(fs.stat(path.join(directory,'output')));
});
