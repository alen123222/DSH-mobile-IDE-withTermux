import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { DshSessions } from '../../bridge/dsh.mjs';

const home = process.env.POCKET_HOME;
const runtime = path.join(home, 'runtime');
const require = createRequire(path.join(runtime, 'package.json'));
const sharp = require('sharp');
const png = await sharp({ create: { width: 80, height: 60, channels: 3, background: '#0088cc' } }).png().toBuffer();
let images = 0;
const server = http.createServer(async (req, res) => {
  try {
    const chunks = []; for await (const part of req) chunks.push(part);
    const body = JSON.parse(Buffer.concat(chunks));
    const pictures = body.messages.flatMap(m => Array.isArray(m.content) ? m.content.filter(c => c.type === 'image_url') : []);
    assert.ok(pictures.length > 0, 'Provider did not receive image content'); images += pictures.length;
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    for (const [delta, finish_reason] of [[{ role: 'assistant', content: 'IMAGE_OK' }, null], [{}, 'stop']]) {
      res.write('data: ' + JSON.stringify({ id: 'image-test', object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason }] }) + '\n\n');
    }
    res.write('data: ' + JSON.stringify({ id: 'image-test', object: 'chat.completion.chunk', choices: [], usage: {
      prompt_tokens: 120, completion_tokens: 12, total_tokens: 132, prompt_tokens_details: { cached_tokens: 80 },
    } }) + '\n\n');
    res.end('data: [DONE]\n\n');
  } catch (e) { res.writeHead(400); res.end(JSON.stringify({ error: { message: e.message } })); }
});
server.listen(0, '127.0.0.1'); await once(server, 'listening');
const root = fs.mkdtempSync(path.join(home, 'image-smoke-'));
fs.symlinkSync(runtime, path.join(root, 'runtime'));
const sessions = new DshSessions(root);
const chat = sessions.create({ id: 'image-test', path: root });
try {
  for (const [index, attachments] of [[0, [{ mimeType: 'image/png', data: png.toString('base64') }]], [1, [{ mimeType: 'image/png', data: png.toString('base64') }]], [2, []]]) {
    await sessions.prompt(chat.id, { prompt: 'Describe test picture', model: 'image-test', protocol: 'openai-chat', apiKey: 'fake-image-test',
      baseUrl: `http://127.0.0.1:${server.address().port}/v1`, allowExecution: true, reasoningEffort: index === 2 ? 'low' : '', attachments });
    const until = Date.now() + 45000;
    while (sessions.get(chat.id).status === 'running' && Date.now() < until) await new Promise(r => setTimeout(r, 150));
    assert.equal(sessions.get(chat.id).status, 'ready', sessions.get(chat.id).error);
    assert.equal(sessions.get(chat.id).messages.at(-1).text, 'IMAGE_OK');
  }
  for (let i = 0; i < 30 && sessions.get(chat.id).metrics?.sessionStats?.steps !== 3; i++) await new Promise(r => setTimeout(r, 100));
  const metrics = sessions.get(chat.id).metrics;
  assert.equal(metrics.sessionStats.steps, 3);
  assert.equal(metrics.sessionStats.turns, 3);
  assert.equal(metrics.tokenUsage.outputTokens, 36);
  assert.equal(metrics.tokenUsage.cacheReadTokens, 240);
  assert.equal(metrics.tokenUsage.uncachedInputTokens, 120);
  assert.equal(metrics.contextPressure.contextWindow, 131072);
  assert.ok(metrics.contextBreakdown.toolsTokens > 0);
  sessions.flush(sessions.get(chat.id));
  assert.deepEqual(new DshSessions(root).get(chat.id).metrics, metrics);
  console.log(JSON.stringify({ passed: true, images, duplicateUpload: true, resumedWithImageHistory: true, metrics }));
} finally {
  sessions.closeAll(); server.close();
}
