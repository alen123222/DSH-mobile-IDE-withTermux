import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';
const runtime = path.resolve(process.argv[2]);
const paths = JSON.parse(fs.readFileSync(path.join(runtime, 'native-paths.json')));
const require = createRequire(path.join(runtime, 'package.json'));
const testDir = fs.mkdtempSync(path.join(runtime, '.smoke-'));
try {
  require(paths.koffi);
  const pty = require(paths.pty);
  const { tryLockExclusive } = await import(pathToFileURL(path.join(paths.system, 'lib/flock.js')));
  const { publishExclusiveAndroid } = await import(pathToFileURL(path.join(paths.androidSystem, 'index.mjs')));
  const lockFile = path.join(testDir, 'lock');
  const a = fs.openSync(lockFile, 'w'), b = fs.openSync(lockFile, 'r+');
  try {
    await tryLockExclusive(a);
    await assert.rejects(tryLockExclusive(b), error => ['EAGAIN', 'EWOULDBLOCK'].includes(error.code));
  } finally { fs.closeSync(a); fs.closeSync(b); }
  const source = path.join(testDir, 'staged'), target = path.join(testDir, 'current');
  fs.writeFileSync(source, 'first'); await publishExclusiveAndroid(source, target);
  fs.writeFileSync(source, 'second');
  await assert.rejects(publishExclusiveAndroid(source, target), error => error.code === 'EEXIST');
  assert.equal(fs.readFileSync(target, 'utf8'), 'first');
  assert.equal(fs.readFileSync(source, 'utf8'), 'second');
  console.log('PASS: native flock and atomic no-overwrite publication');
  await new Promise((resolve, reject) => {
    let output = '';
    const child = pty.spawn(`${process.env.PREFIX}/bin/bash`, ['-c', 'printf "pocket-pty-ok"'], { cwd: testDir, env: process.env });
    const timer = setTimeout(() => { child.kill(); reject(new Error('PTY timeout')); }, 10000);
    child.onData(data => { output += data; });
    child.onExit(() => { clearTimeout(timer); output.includes('pocket-pty-ok') ? resolve() : reject(new Error(`PTY failed: ${output}`)); });
  });
  console.log('PASS: real node-pty output');
  await new Promise((resolve, reject) => {
    const child = spawn(path.join(runtime, 'bin/dsh-pocket'), ['--profile', 'sdk-minimal'], {
      cwd: testDir, env: { ...process.env, DSH_HOME: path.join(testDir, 'dsh-home') }, stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stderr = '', passed = false;
    const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error(`SDK initialization timed out: ${stderr}`)); }, 60000);
    child.stderr.on('data', data => { stderr = (stderr + data.toString()).slice(-8000); });
    child.on('error', error => { clearTimeout(timer); reject(error); });
    child.on('exit', code => { clearTimeout(timer); passed ? resolve() : reject(new Error(`SDK exited ${code}: ${stderr}`)); });
    createInterface({ input: child.stdout }).on('line', line => {
      try {
        const frame = JSON.parse(line);
        if (frame.id === 1) {
          if (frame.error || frame.result?.serverInfo?.name !== 'deepseek-harness-sdk-runtime') throw new Error(JSON.stringify(frame));
          passed = true;
          child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'shutdown' }) + '\n');
        }
      } catch (error) { child.kill(); reject(error); }
    });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { cwd: testDir, provider: 'deepseek-official', model: 'deepseek-v4-flash' } }) + '\n');
  });
  console.log('PASS: official DSH SDK handshake (no model API request)');
} finally { fs.rmSync(testDir, { recursive: true, force: true }); }
