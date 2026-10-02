import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { createBridge } from '../bridge/server.mjs';

async function setup(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-test-'));
  const projects = ['project A', '另一个项目'].map(name => path.join(root, name));
  projects.forEach(dir => fs.mkdirSync(dir));
  const token = 'test-connection-secret-'.repeat(3);
  const bridge = createBridge({ stateDir: path.join(root, 'private'), token,
    dshOptions: { dshBin: process.execPath, command: [process.execPath, fileURLToPath(new URL('./fixtures/sdk-runtime.mjs', import.meta.url))], timeoutMs: 2000 } });
  bridge.server.listen(0, '127.0.0.1');
  await once(bridge.server, 'listening');
  const base = `http://127.0.0.1:${bridge.server.address().port}/v1/`;
  async function request(route, { method = 'GET', body, headers = {} } = {}) {
    const response = await fetch(base + route, { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }
  t.after(async () => {
    const exits = [...bridge.chats.sessions.values()].flatMap(s => s.process && s.process.exitCode === null && s.process.signalCode === null ? [once(s.process, 'exit')] : []);
    bridge.chats.closeAll(); bridge.terminals.closeAll();
    await Promise.all(exits);
    await new Promise(resolve => bridge.server.close(resolve));
    fs.rmSync(root, { recursive: true, force: true });
  });
  return { root, projects, token, bridge, request };
}

test('local execution API requires pairing token and rejects browser origins', async t => {
  const { request } = await setup(t);
  assert.equal((await request('health', { headers: { Authorization: 'Bearer bad' } })).status, 401);
  assert.equal((await request('health', { headers: { Origin: 'https://evil.example' } })).status, 403);
  assert.equal((await request('health')).status, 200);
  assert.equal((await request('workspaces', { method: 'POST', body: [] })).status, 400);
});

test('workspaces use original independent folders and reject relative/missing/file paths', async t => {
  const { request, projects, root } = await setup(t);
  const first = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const second = await request('workspaces', { method: 'POST', body: { path: projects[1] } });
  assert.equal(first.body.path, fs.realpathSync(projects[0]));
  assert.notEqual(first.body.id, second.body.id);
  const duplicate = await request('workspaces', { method: 'POST', body: { path: path.join(projects[0], '.') } });
  assert.equal(duplicate.body.id, first.body.id);
  assert.equal((await request('workspaces', { method: 'POST', body: { path: 'relative' } })).status, 400);
  assert.equal((await request('workspaces', { method: 'POST', body: { path: path.join(root, 'missing') } })).status, 404);
  fs.writeFileSync(path.join(projects[0], 'original.txt'), 'still here');
  assert.equal((await request('workspaces', { method: 'POST', body: { path: path.join(projects[0], 'original.txt') } })).status, 400);
  await request(`workspaces/${first.body.id}`, { method: 'DELETE' });
  assert.equal(fs.readFileSync(path.join(projects[0], 'original.txt'), 'utf8'), 'still here');
});

test('file preview stays within selected workspace and refuses binary and oversized files', async t => {
  const { request, projects } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const inside = path.join(projects[0], 'hello.txt'), outside = path.join(projects[1], 'other.txt');
  fs.writeFileSync(inside, '你好'); fs.writeFileSync(outside, 'private');
  const preview = filename => request(`file?${new URLSearchParams({ workspaceId: workspace.id, path: filename })}`);
  assert.equal((await preview(inside)).body.content, '你好');
  assert.equal((await preview(outside)).status, 403);
  fs.writeFileSync(inside, Buffer.from([0, 1, 2])); assert.equal((await preview(inside)).status, 415);
  fs.writeFileSync(inside, Buffer.alloc(600000, 65)); assert.equal((await preview(inside)).status, 413);
  assert.equal((await request('directories', { method: 'POST', body: { parent: projects[0], name: '../escape' } })).status, 400);
});

async function waitChat(request, id, predicate) {
  for (let i = 0; i < 150; i++) {
    const { body } = await request(`chats/${id}`);
    if (predicate(body)) return body;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('Chat did not reach expected state');
}

test('DSH protocol handles receipt races, isolates cwd, and continues live conversations', async t => {
  const { request, projects } = await setup(t);
  const conversations = [];
  for (const project of projects) {
    const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: project } });
    const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
    conversations.push(chat);
  }
  const payload = { prompt: 'inspect', model: 'test-model', allowExecution: true };
  assert.equal((await request(`chats/${conversations[0].id}/prompt`, { method: 'POST', body: { ...payload, allowExecution: false } })).status, 403);
  for (const chat of conversations) {
    assert.equal((await request(`chats/${chat.id}/prompt`, { method: 'POST', body: payload })).status, 200);
  }
  for (const chat of conversations) {
    const complete = await waitChat(request, chat.id, value => value.status !== 'running');
    assert.equal(complete.status, 'ready', complete.error);
    assert.equal(complete.messages.at(-1).text, `cwd=${chat.cwd}; inspect`);
    assert.equal(complete.messages.length, 2);
    assert.equal(complete.events[0].data.name, 'bash');
  }
  const id = conversations[0].id;
  await request(`chats/${id}/prompt`, { method: 'POST', body: { ...payload, prompt: 'second turn' } });
  const complete = await waitChat(request, id, value => value.status !== 'running');
  assert.equal(complete.messages.length, 4);
  assert.match(complete.messages.at(-1).text, /second turn$/);
  const changed = await request(`chats/${id}/prompt`, { method: 'POST', body: { ...payload, prompt: 'wrong credentials', apiKey: 'other-key' } });
  assert.equal(changed.status, 409);
  assert.match(changed.body.error, /新建会话/);
});

test('stop terminates the owned runtime and preserves the transcript', async t => {
  const { request, projects, bridge } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'wait', model: 'test', apiKey: 'SECRET-NOT-ON-DISK', allowExecution: true } });
  await new Promise(resolve => setTimeout(resolve, 150));
  const child = bridge.chats.get(chat.id).process;
  const exited = once(child, 'exit');
  assert.equal((await request(`chats/${chat.id}/stop`, { method: 'POST', body: {} })).body.status, 'stopped');
  await exited;
  const transcript = (await request(`chats/${chat.id}`)).body;
  assert.equal(transcript.messages[0].text, 'wait');
  assert.ok(!JSON.stringify(transcript).includes('SECRET-NOT-ON-DISK'));
  assert.equal((await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'again', model: 'test', allowExecution: true } })).status, 409);
});

test('real PTY retains cd and shell variables across inputs', { skip: process.platform === 'win32' }, async t => {
  const { request, projects } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: terminal } = await request('terminals', { method: 'POST', body: { workspaceId: workspace.id } });
  const input = command => request(`terminals/${terminal.id}`, { method: 'POST', body: { type: 'input', data: Buffer.from(command).toString('base64') } });
  await input(`cd '${projects[1]}'\nPOCKET_TEST_VAR=persisted\n`);
  await input('printf "\\nRESULT=%s|%s\\n" "$PWD" "$POCKET_TEST_VAR"\n');
  let output = '';
  for (let i = 0; i < 50; i++) {
    const { body } = await request(`terminals/${terminal.id}?after=0`);
    output = body.chunks.map(c => Buffer.from(c.data, 'base64').toString()).join('');
    if (output.includes(`RESULT=${projects[1]}|persisted`)) break;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(output.includes(`RESULT=${projects[1]}|persisted`), output);
  await request(`terminals/${terminal.id}`, { method: 'DELETE' });
});
