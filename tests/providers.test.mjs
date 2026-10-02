import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { connectionSettings, connectionSignature, writeProviderPatch } from '../bridge/providers.mjs';

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
  for (const changes of [{ apiKey: 'key-B' }, { model: 'other' }, { baseUrl: 'https://other.example/v1' }, { protocol: 'openai-responses' }])
    assert.notEqual(connectionSignature(original), connectionSignature(connectionSettings({ ...original, ...changes })));
});
