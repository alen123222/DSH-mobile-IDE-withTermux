// Android smoke test: no external API or real credential is used.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';

const home = process.env.POCKET_HOME || path.join(os.homedir(), '.local/share/dsh-pocket');
let requests = 0, toolRequests = 0;
const marker = 'POCKET_CUSTOM_ADAPTER_OK';
const server = http.createServer(async (req, res) => {
  try {
    if (req.headers.authorization !== 'Bearer pocket-smoke-fake-key' && req.headers['x-api-key'] !== 'pocket-smoke-fake-key') {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: { message: 'Invalid key: ' + (req.headers.authorization || req.headers['x-api-key'] || '') } }));
    }
    if (process.argv.includes('--serve') && req.url === '/v1/messages') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ content: [{ type: 'text', text: 'OK' }], model: body.model, role: 'assistant', type: 'message' }));
    }
    if (process.argv.includes('--serve') && req.url === '/v1/responses') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ status: 'completed', output: [{ type: 'message', content: [{ type: 'output_text', text: 'OK' }] }] }));
    }
    assert.equal(req.headers.authorization, 'Bearer pocket-smoke-fake-key');
    if (req.url === '/v1/models' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ data: [{ id: 'pocket-test-model' }, { id: 'pocket-other-model' }] }));
    }
    assert.equal(req.url, '/v1/chat/completions');
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    assert.equal(body.model, 'pocket-test-model');
    requests++;
    if (!body.stream) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ id: 'mock-test', object: 'chat.completion', model: body.model,
        choices: [{ index: 0, message: { role: 'assistant', content: 'OK' }, finish_reason: 'stop' }] }));
    }
    const hasResult = body.messages.some(m => m.role === 'tool');
    const tool = body.tools?.find(t => t.function.parameters?.properties?.command);
    assert.ok(tool, 'Real DSH must expose its persistent Bash tool');
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
    const emit = (delta, finish_reason = null) => res.write('data: ' + JSON.stringify({ id: 'mock-stream', object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000), model: body.model, choices: [{ index: 0, delta, finish_reason }] }) + '\n\n');
    emit({ role: 'assistant' });
    if (!hasResult) {
      toolRequests++;
      emit({ tool_calls: [{ index: 0, id: 'call_pocket_smoke', type: 'function', function: { name: tool.function.name,
        arguments: JSON.stringify({ command: `printf '%s' '${marker}' > pocket-smoke-result.txt; cat pocket-smoke-result.txt` }) } }] });
      emit({}, 'tool_calls');
    } else { emit({ content: marker }); emit({}, 'stop'); }
    res.end('data: [DONE]\n\n');
  } catch (error) {
    res.writeHead(400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: error.message } }));
  }
});
server.listen(process.argv.includes('--serve') ? 18766 : 0, '127.0.0.1');
await once(server, 'listening');
if (process.argv.includes('--serve')) {
  console.log('POCKET_MOCK_API_READY');
} else {
  const { DshSessions } = await import(pathToFileURL(path.join(home, 'bridge/dsh.mjs')));
  const root = fs.mkdtempSync(path.join(home, 'provider-smoke-'));
  const cwd = path.join(root, 'project'); fs.mkdirSync(cwd);
  const sessions = new DshSessions(path.join(root, 'state'), { dshBin: path.join(home, 'runtime/bin/dsh-pocket'), timeoutMs: 30000 });
  const chat = sessions.create({ id: 'mock-workspace', path: cwd });
  let passed = false;
  try {
    await sessions.prompt(chat.id, { prompt: 'Use Bash to write and read the test marker.', model: 'pocket-test-model', protocol: 'openai-chat',
      apiKey: 'pocket-smoke-fake-key', baseUrl: `http://127.0.0.1:${server.address().port}/v1/chat/completions`, allowExecution: true });
    const deadline = Date.now() + 60000;
    while (sessions.get(chat.id).status === 'running' && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 200));
    const result = sessions.snapshot(sessions.get(chat.id));
    assert.equal(result.status, 'ready', result.error || 'Real DSH turn did not finish');
    assert.equal(result.messages.at(-1)?.text, marker);
    assert.equal(fs.readFileSync(path.join(cwd, 'pocket-smoke-result.txt'), 'utf8'), marker);
    assert.ok(requests >= 2 && toolRequests === 1);
    assert.ok(result.events.some(e => e.type === 'tool/result'));
    assert.equal(JSON.stringify(result).includes('pocket-smoke-fake-key'), false);
    console.log(JSON.stringify({ passed: true, protocol: 'openai-chat', requests, toolRequests, assistant: marker, realFileWrite: true }));
    passed = true;
  } finally {
    const runtime = sessions.get(chat.id).process;
    const exit = runtime && runtime.exitCode === null ? once(runtime, 'exit') : Promise.resolve();
    sessions.closeAll();
    await exit;
    await new Promise(resolve => server.close(resolve));
    if (passed) fs.rmSync(root, { recursive: true, force: true });
  }
}
