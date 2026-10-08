import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { connectionSettings, connectionSignature, listModels, modelRoots, writeProviderPatch } from '../bridge/providers.mjs';
import http from 'node:http';

test('phone tools require a bounded token while image attachments work without phone control', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-phone-'));
  try {
    const basic = connectionSettings({ protocol: 'openai-chat', model: 'test' });
    const phone = connectionSettings({ ...basic, vision: true, phone: { token: 'a'.repeat(72), port: 8766 } });
    assert.notEqual(connectionSignature(basic), connectionSignature(phone));
    assert.equal(connectionSettings({ ...basic, phone: { token: 'short', port: 80 } }).phone, undefined);
    const plain = JSON.parse(fs.readFileSync(writeProviderPatch(root, 'plain', basic)));
    assert.deepEqual(plain[1].insert[0].config.providers['pocket-openai'].models[0].input, ['text', 'image']);
    assert.ok(plain.flatMap(row => row.insert || []).some(row => row.id === 'pocket-attachments' && row.config.dshHome === path.join(root, 'dsh-home')));
    assert.equal(plain.flatMap(row => row.insert || []).some(row => row.id === 'pocket-phone'), false);
    const patch = JSON.parse(fs.readFileSync(writeProviderPatch(root, 'phone', phone)));
    assert.deepEqual(patch[1].insert[0].config.providers['pocket-openai'].models[0].input, ['text', 'image']);
    assert.ok(patch.flatMap(row => row.insert || []).some(row => row.id === 'pocket-phone'));
    assert.ok(patch.flatMap(row => row.insert || []).some(row => row.id === 'pocket-attachments'));
    assert.equal(JSON.stringify(patch).includes(phone.phone.token), false);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('model roots cover the endpoint shapes people actually enter', () => {
  assert.deepEqual(modelRoots('https://api.openai.com/v1'), ['https://api.openai.com/v1/models']);
  assert.deepEqual(modelRoots('https://api.deepseek.com'),
    ['https://api.deepseek.com/models', 'https://api.deepseek.com/v1/models']);
  assert.deepEqual(modelRoots('https://x.dev/v1/chat/completions'), ['https://x.dev/v1/models']);
  assert.deepEqual(modelRoots('   '), []);
});

test('model discovery falls through to the next root and reads the list', async () => {
  const server = http.createServer((request, response) => {
    if (request.url === '/v1/models') {
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ data: [{ id: 'm-1' }, { id: 'm-2', display_name: 'Model Two' }, { id: 'm-1' }] }));
      return;
    }
    response.statusCode = 404;
    response.end('{}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  try {
    const found = await listModels({ baseUrl: base, apiKey: 'secret-key' });
    assert.equal(found.endpoint, base + '/v1/models');
    assert.deepEqual(found.items, [{ id: 'm-1' }, { id: 'm-2', name: 'Model Two' }]);
  } finally { server.close(); }
});

test('model discovery reports an unusable endpoint instead of an empty list', async () => {
  await assert.rejects(() => listModels({ baseUrl: 'http://127.0.0.1:1', apiKey: '' }), /无法连接|模型/);
});

test('custom API roots and complete request URLs do not duplicate suffixes', () => {
  const cases = [
    ['https://gateway.example', true, 'https://gateway.example/v1'],
    ['https://gateway.example/', false, 'https://gateway.example'],
    ['https://gateway.example/v1/', true, 'https://gateway.example/v1'],
    ['https://gateway.example/v1/chat/completions/', true, 'https://gateway.example/v1'],
    ['https://gateway.example/chat/completions', true, 'https://gateway.example'],
    ['https://gateway.example/custom/path/responses', true, 'https://gateway.example/custom/path'],
    ['https://gateway.example/api', true, 'https://gateway.example/api'],
    ['http://127.0.0.1:18766/v1', true, 'http://127.0.0.1:18766/v1'],
  ];
  for (const [baseUrl, autoVersion, expected] of cases) assert.equal(connectionSettings({ protocol: 'openai-chat', baseUrl, autoVersion }).baseUrl, expected);
  assert.equal(connectionSettings({ protocol: 'deepseek-messages', baseUrl: 'https://api.deepseek.com/anthropic/v1/messages' }).baseUrl, 'https://api.deepseek.com/anthropic');
  for (const baseUrl of ['file:///etc/passwd', 'https://user:pass@example.com', 'https://example.com?key=secret', 'bad']) {
    assert.throws(() => connectionSettings({ baseUrl, protocol: 'openai-chat' }));
  }
});

test('custom routes install the real pi-ai adapter; generated patches never contain keys', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-provider-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const protocol of ['openai-chat', 'openai-responses', 'deepseek-messages']) {
    const settings = connectionSettings({ protocol, model: 'my-model', apiKey: 'SECRET-TEST-ONLY', baseUrl: 'https://gateway.example/v1' });
    const file = writeProviderPatch(root, 'pocket-test', settings);
    const raw = fs.readFileSync(file, 'utf8'), patch = JSON.parse(raw);
    assert.equal(raw.includes(settings.apiKey), false);
    assert.equal(settings.provider, protocol === 'deepseek-messages' ? 'deepseek-official' : 'pocket-openai');
    if (protocol !== 'deepseek-messages') {
      assert.equal(patch[0].disabled, true);
      assert.equal(patch[1].insert[0].name, '@deepseek-ai/dsh-llm-pi-ai');
      const route = patch[1].insert[0].config.providers[settings.provider];
      assert.equal(route.api, protocol === 'openai-chat' ? 'openai-completions' : 'openai-responses');
      assert.equal(route.apiKeyEnv, 'POCKET_API_KEY');
      assert.equal(route.models[0].id, 'my-model');
    }
  }
});

test('connection changes cannot silently reuse a process with old credentials', () => {
  const original = connectionSettings({ protocol: 'openai-chat', model: 'model', apiKey: 'key-A', baseUrl: 'https://gateway.example/v1' });
  assert.equal(connectionSignature(original), connectionSignature(connectionSettings({ ...original, baseUrl: original.baseUrl + '/chat/completions' })));
  for (const changes of [{ reasoningEffort: 'high' }, { apiKey: 'key-B' }, { model: 'other' }, { baseUrl: 'https://other.example/v1' }, { protocol: 'openai-responses' }])
    assert.notEqual(connectionSignature(original), connectionSignature(connectionSettings({ ...original, ...changes })));
});

test('reasoning defaults remain unset and explicit levels configure both adapter families', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-reasoning-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const protocol of ['openai-chat', 'openai-responses', 'deepseek-messages']) {
    for (const effort of ['', 'off', 'low', 'high', 'max']) {
      const settings = connectionSettings({ protocol, model: 'custom-model', reasoningEffort: effort });
      const patch = JSON.parse(fs.readFileSync(writeProviderPatch(root, 'test', settings), 'utf8'));
      if (protocol === 'deepseek-messages') assert.equal(patch[0].config.reasoningEffort, effort || undefined);
      else {
        const route = patch[1].insert[0].config.providers['pocket-openai'];
        assert.equal(route.reasoning, effort || undefined);
        assert.equal(route.models[0].reasoningEfforts?.high, effort ? 'high' : undefined);
      }
    }
  }
  assert.throws(() => connectionSettings({ protocol: 'deepseek-messages', reasoningEffort: 'medium' }));
  assert.throws(() => connectionSettings({ protocol: 'openai-chat', reasoningEffort: 'garbage' }));
});
