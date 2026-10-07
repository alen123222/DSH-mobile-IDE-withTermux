import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { DshSessions } from '../bridge/dsh.mjs';
import { ensureSdkSessionPatch, sdkSessionPatch } from '../termux/sdk-session-patch.mjs';

const runtime = path.resolve(process.env.POCKET_TEST_RUNTIME || '.cache/dsh-runtime');
const installed = fs.existsSync(path.join(runtime, 'node_modules/@deepseek-ai/dsh/package.json'));
const runtimeTest = { skip: installed ? false : 'Install pinned DSH or set POCKET_TEST_RUNTIME' };

test('SDK patch refuses unknown source', () => {
  assert.throws(() => sdkSessionPatch('different SDK'), /patch mismatch/);
});

test('real SDK resumes only missing sessions via create and preserves other errors', runtimeTest, async () => {
  ensureSdkSessionPatch(runtime);
  const require = createRequire(path.join(runtime, 'package.json'));
  const sdkFile = require.resolve('@deepseek-ai/dsh-sdk-jsonrpc-server');
  const source = fs.readFileSync(sdkFile, 'utf8');
  assert.equal(sdkSessionPatch(source), source, 'patch is idempotent');
  const { HarnessSdkJsonRpcServer } = await import(pathToFileURL(sdkFile));
  const { SessionPersistenceNotFoundError, SessionAlreadyOwnedError } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-persistence')));
  let creates = 0, resumes = 0, failure;
  const handle = { agent: {}, dispose() {} };
  const server = new HarnessSdkJsonRpcServer({
    on: () => () => {}, get: () => ({}),
    agents: {
      async resume(options) { resumes++; assert.equal(options.agentOptions.model, 'new-model'); if (failure) throw failure; return handle; },
      async create() { creates++; return handle; },
    },
  }, { notify() {} });
  server.model = 'new-model';
  assert.equal((await server.getOrCreateSession('existing')).handle, handle);
  assert.equal((await server.getOrCreateSession('existing')).handle, handle);
  assert.equal(resumes, 1, 'live session is reused');
  failure = new SessionPersistenceNotFoundError('fresh');
  await server.getOrCreateSession('fresh');
  assert.equal(creates, 1);
  for (const error of [new SessionAlreadyOwnedError('locked'), new Error('corrupt log'), new SessionPersistenceNotFoundError('different-id')]) {
    failure = error;
    await assert.rejects(server.getOrCreateSession('locked'), thrown => thrown === error);
  }
  assert.equal(creates, 1, 'lock and corruption errors must never create a replacement');
  await server.shutdown();
});

test('real SDK preserves model-visible history across model switch, stop and bridge reload', { ...runtimeTest, timeout: 90000 }, async t => {
  ensureSdkSessionPatch(runtime);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-real-resume-'));
  const observed = [];
  const api = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    observed.push(body);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const emit = (delta, finish_reason = null) => res.write('data: ' + JSON.stringify({ id: 'resume-test', object: 'chat.completion.chunk',
      created: 1, model: body.model, choices: [{ index: 0, delta, finish_reason }] }) + '\n\n');
    emit({ role: 'assistant', content: 'reply-' + observed.length });
    emit({}, 'stop'); res.end('data: [DONE]\n\n');
  });
  api.listen(0, '127.0.0.1'); await once(api, 'listening');
  const options = { dshBin: process.execPath, timeoutMs: 20000,
    command: [process.execPath, '--expose-internals', path.join(runtime, 'node_modules/@deepseek-ai/dsh/lib/bin.js'), '--profile', 'sdk-minimal'] };
  const state = path.join(root, 'state');
  let sessions = new DshSessions(state, options);
  const close = async () => {
    const items = [...sessions.sessions.values()];
    sessions.closeAll();
    await Promise.all(items.map(item => item.retiring));
  };
  t.after(async () => { await close(); await new Promise(resolve => api.close(resolve)); fs.rmSync(root, { recursive: true, force: true }); });
  const chat = sessions.create({ id: 'workspace', path: root });
  const ask = async (prompt, model) => {
    await sessions.prompt(chat.id, { prompt, model, protocol: 'openai-chat', apiKey: 'fake-test-key',
      baseUrl: `http://127.0.0.1:${api.address().port}/v1`, allowExecution: true });
    const deadline = Date.now() + 20000;
    while (sessions.get(chat.id).status === 'running' && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 30));
    const item = sessions.get(chat.id);
    assert.equal(item.status, 'ready', item.error || item.stderr || 'turn did not finish');
    return item.process.pid;
  };
  const firstPid = await ask('remember-marker-1', 'model-a');
  const secondPid = await ask('remember-marker-2', 'model-b');
  assert.notEqual(firstPid, secondPid);
  sessions.stop(chat.id);
  await ask('remember-marker-3', 'model-b');
  await close();
  sessions = new DshSessions(state, options);
  await ask('remember-marker-4', 'model-a');
  assert.deepEqual(observed.map(body => body.model), ['model-a', 'model-b', 'model-b', 'model-a']);
  for (let i = 0; i < observed.length; i++) {
    const history = JSON.stringify(observed[i].messages);
    for (let j = 1; j <= i + 1; j++) assert.ok(history.includes('remember-marker-' + j), history);
    for (let j = 1; j <= i; j++) assert.ok(history.includes('reply-' + j), history);
  }
});
