import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { createBridge } from '../bridge/server.mjs';
import { DshSessions } from '../bridge/dsh.mjs';
import { connectionSettings, writeProviderPatch } from '../bridge/providers.mjs';

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
  const stateDir = path.join(root, 'private');
  async function request(route, { method = 'GET', body, headers = {}, query } = {}) {
    const url = query ? `${base}${route}?${new URLSearchParams(query)}` : base + route;
    const response = await fetch(url, { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }
  t.after(async () => {
    const exits = [...bridge.chats.sessions.values()].flatMap(s => s.process && s.process.exitCode === null && s.process.signalCode === null ? [once(s.process, 'exit')] : []);
    bridge.chats.closeAll(); bridge.terminals.closeAll();
    await Promise.all(exits);
    await new Promise(resolve => bridge.server.close(resolve));
    fs.rmSync(root, { recursive: true, force: true });
  });
  return { root, projects, token, bridge, request, stateDir, base };
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

test('stop terminates the owned runtime, preserves the transcript and allows resuming', async t => {
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
  // A stopped session used to be permanently dead. It must now resume.
  assert.equal((await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'again', model: 'test', apiKey: 'SECRET-NOT-ON-DISK', allowExecution: true } })).status, 200);
  await new Promise(resolve => setTimeout(resolve, 150));
  await request(`chats/${chat.id}/stop`, { method: 'POST', body: {} });
});

test('workspaces and chats can be starred and deleted', async t => {
  const { request, projects } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  assert.equal(workspace.starred, false);
  const starred = (await request(`workspaces/${workspace.id}/star`, { method: 'POST', body: { starred: true } })).body;
  assert.equal(starred.starred, true);
  assert.equal((await request('workspaces')).body.items[0].starred, true, 'starred workspaces sort first');
  assert.equal((await request(`workspaces/${workspace.id}/star`, { method: 'POST', body: { starred: false } })).body.starred, false);

  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  const starredChat = (await request(`chats/${chat.id}/star`, { method: 'POST', body: { starred: true } })).body;
  assert.equal(starredChat.starred, true);
  assert.equal((await request('chats', { query: { workspaceId: workspace.id } })).body.items[0].id, chat.id);

  // Unchanged sessions answer with a tiny delta object instead of the full history.
  const revision = (await request(`chats/${chat.id}`)).body.revision;
  assert.equal((await request(`chats/${chat.id}`, { query: { since: String(revision) } })).body.unchanged, true);

  assert.equal((await request(`chats/${chat.id}`, { method: 'DELETE' })).status, 200);
  assert.equal((await request(`chats/${chat.id}`)).status, 404);
  // Deleting a workspace only removes the record; the directory stays on disk.
  assert.equal((await request(`workspaces/${workspace.id}`, { method: 'DELETE' })).status, 200);
  assert.equal((await request(`workspaces/${workspace.id}`, { method: 'DELETE' })).status, 404);
  const { statSync } = await import('node:fs');
  assert.ok(statSync(projects[0]).isDirectory());
});

test('a running session cannot be deleted out from under the engine', async t => {
  const { request, projects, bridge } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'wait', model: 'test', apiKey: 'SECRET-NOT-ON-DISK', allowExecution: true } });
  await new Promise(resolve => setTimeout(resolve, 150));
  assert.equal(bridge.chats.get(chat.id).status, 'running');
  assert.equal((await request(`chats/${chat.id}`, { method: 'DELETE' })).status, 409);
  await request(`chats/${chat.id}/stop`, { method: 'POST', body: {} });
});

test('a corrupt state file is quarantined instead of blocking startup', async t => {
  const { request, projects, stateDir } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  fs.mkdirSync(path.join(stateDir, 'chats'), { recursive: true });
  fs.writeFileSync(path.join(stateDir, 'chats', 'pocket-broken.json'), '{"id":"pocket-broken","title":"截断');
  const sessions = new DshSessions(stateDir, {});
  assert.equal(sessions.sessions.has('pocket-broken'), false, 'unreadable records are dropped');
  assert.equal(fs.readdirSync(path.join(stateDir, 'chats')).filter(name => name.includes('corrupt')).length, 1,
    'damaged bytes are kept for inspection');
  assert.equal((await request('health')).status, 200);
  assert.equal(workspace.path, projects[0]);
});

test('model limits reach the provider patch and reject junk', async t => {
  const { projects } = await setup(t);
  const { modelLimits } = await import('../bridge/providers.mjs');
  assert.deepEqual(modelLimits({}), { contextWindow: 131072, maxTokens: 8192 });
  assert.deepEqual(modelLimits({ contextWindow: '1048576', maxTokens: '64000' }), { contextWindow: 1048576, maxTokens: 64000 });
  // Out-of-range and junk input falls back rather than reaching the engine.
  assert.deepEqual(modelLimits({ contextWindow: 10, maxTokens: 'abc' }), { contextWindow: 131072, maxTokens: 8192 });
  assert.deepEqual(modelLimits({ contextWindow: 99_999_999, maxTokens: 999_999 }), { contextWindow: 4194304, maxTokens: 131072 });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-patch-'));
  const settings = { ...connectionSettings({ model: 'test', protocol: 'openai-chat', baseUrl: 'https://api.example.com/v1', apiKey: 'k', contextWindow: 1000000, maxTokens: 32000 }) };
  const file = writeProviderPatch(dir, 'probe', settings);
  const patch = JSON.parse(fs.readFileSync(file, 'utf8'));
  const models = patch[1].insert[0].config.providers['pocket-openai'].models;
  assert.equal(models[0].contextWindow, 1000000);
  assert.equal(models[0].maxTokens, 32000);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('the SSE channel pushes snapshots and reports deletion', async t => {
  const { request, projects, base, token } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  const controller = new AbortController();
  t.after(() => controller.abort());
  const response = await fetch(`${base}chats/${chat.id}/stream`,
    { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal });
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /text\/event-stream/);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const queue = [];
  let notify = () => {};
  (async () => {
    let buffer = '';
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let index;
        while ((index = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, index);
          buffer = buffer.slice(index + 2);
          const data = frame.split('\n').find(line => line.startsWith('data: '));
          if (!data) continue; // Comment line, e.g. the opening handshake or a keep-alive.
          const event = frame.split('\n').find(line => line.startsWith('event: '));
          queue.push({ event: event ? event.substring(7).trim() : 'message',
            data: JSON.parse(data.substring(6)) });
          notify();
        }
      }
    } catch { /* Aborted at teardown. */ }
  })();
  const nextEvent = async (timeoutMs = 4000) => {
    const deadline = Date.now() + timeoutMs;
    while (queue.length === 0) {
      if (Date.now() > deadline) return null;
      await new Promise(resolve => { notify = () => { notify = () => {}; resolve(); }; setTimeout(resolve, 100); });
    }
    return queue.shift();
  };
  // The bridge sends the current snapshot immediately on subscribe.
  const first = await nextEvent();
  assert.equal(first.event, 'message');
  assert.equal(first.data.id, chat.id);
  await request(`chats/${chat.id}/star`, { method: 'POST', body: { starred: true } });
  const starred = await nextEvent();
  assert.equal(starred.data.starred, true, 'a state change pushes without any polling');
  await request(`chats/${chat.id}`, { method: 'DELETE' });
  assert.equal((await nextEvent()).event, 'deleted', 'deleting a session tells its open stream');
  controller.abort();
});

test('a stale stop timer cannot kill the process from a later turn', async t => {
  const { request, projects, bridge } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'first', model: 'test', apiKey: 'k', allowExecution: true } });
  await new Promise(resolve => setTimeout(resolve, 200));
  const first = bridge.chats.get(chat.id).process.pid;
  await request(`chats/${chat.id}/stop`, { method: 'POST', body: {} });
  await new Promise(resolve => setTimeout(resolve, 250));
  await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'second', model: 'test', apiKey: 'k', allowExecution: true } });
  await new Promise(resolve => setTimeout(resolve, 250));
  const second = bridge.chats.get(chat.id).process;
  assert.notEqual(first, second.pid, 'a fresh engine is running');
  // Outlive the 2.5 s escalation window the previous stop armed.
  await new Promise(resolve => setTimeout(resolve, 2800));
  assert.equal(second.signalCode, null, 'the old escalation timer must not touch the new process');
  assert.notEqual(bridge.chats.get(chat.id).status, 'error');
  await request(`chats/${chat.id}/stop`, { method: 'POST', body: {} });
});

test('resume does not crash the service when it drops the old process', async t => {
  const { request, projects, bridge } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'x', model: 'test', apiKey: 'k', allowExecution: true } });
  await new Promise(resolve => setTimeout(resolve, 200));
  await request(`chats/${chat.id}/stop`, { method: 'POST', body: {} });
  // Resume with no follow-up prompt: the old timer used to dereference a null
  // item.process and take the whole service down.
  await request(`chats/${chat.id}/resume`, { method: 'POST', body: {} });
  assert.equal(bridge.chats.get(chat.id).status, 'ready');
  await new Promise(resolve => setTimeout(resolve, 2800));
  assert.equal((await request('health')).status, 200, 'the service survived the stale timer');
});

test('deleting a finished session keeps it deleted', async t => {
  const { request, projects, bridge, stateDir } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  await request(`chats/${chat.id}/prompt`, { method: 'POST', body: { prompt: 'x', model: 'test', apiKey: 'k', allowExecution: true } });
  for (let i = 0; i < 100 && bridge.chats.get(chat.id).status !== 'ready'; i++) await new Promise(resolve => setTimeout(resolve, 50));
  const child = bridge.chats.get(chat.id).process;
  assert.ok(child, 'the finished session still owns its engine process');
  await request(`chats/${chat.id}`, { method: 'DELETE' });
  const file = path.join(stateDir, 'chats', `${chat.id}.json`);
  assert.equal(fs.existsSync(file), false);
  if (child && child.exitCode === null) await new Promise(resolve => child.on('exit', resolve));
  await new Promise(resolve => setTimeout(resolve, 700));
  assert.equal(fs.existsSync(file), false, 'a dead engine must not rewrite the transcript');
  assert.equal(new DshSessions(stateDir, {}).sessions.has(chat.id), false, 'the record stays gone across a restart');
});

test('a plain GET returns a full snapshot even at revision zero', async t => {
  const { request, projects } = await setup(t);
  const { body: workspace } = await request('workspaces', { method: 'POST', body: { path: projects[0] } });
  const { body: chat } = await request('chats', { method: 'POST', body: { workspaceId: workspace.id } });
  assert.equal(chat.revision, 0);
  // Number(null) is 0, so an unguarded delta made this answer with a stub that
  // had no workspaceId — the sidebar could not open a brand new session.
  const plain = (await request(`chats/${chat.id}`)).body;
  assert.equal(plain.unchanged, undefined);
  assert.equal(plain.workspaceId, workspace.id);
  assert.ok(Array.isArray(plain.messages));
  assert.equal(plain.status, 'ready');
  assert.equal((await request(`chats/${chat.id}?since=0`)).body.unchanged, true, 'an explicit since still works');
});

test('browsing a large directory does not stall the event loop', async t => {
  const { request, projects } = await setup(t);
  const wide = path.join(projects[0], 'wide');
  fs.mkdirSync(wide);
  for (let i = 0; i < 400; i++) fs.mkdirSync(path.join(wide, `d${i}`));
  const started = Date.now();
  const { body: listing } = await request('browse', { query: { path: wide, dirs: 'true' } });
  assert.ok(Date.now() - started < 10000, 'a big folder must answer promptly');
  assert.equal(listing.entries.length, 400);
  assert.equal(listing.total, 400);
  // Health has to stay answerable while a listing is in flight.
  assert.equal((await request('health')).status, 200);
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
