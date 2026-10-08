import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DshSessions } from '../bridge/dsh.mjs';
import { readMetrics } from '../bridge/metrics.mjs';

test('statistics accept only known nonnegative numbers', () => {
  assert.equal(readMetrics({ asOfSeq: -1 }), null);
  assert.deepEqual(readMetrics({ asOfSeq: 2, values: { tokenUsage: { outputTokens: 12, cacheReadTokens: NaN, uncachedInputTokens: -1, secret: 'private' } } }),
    { asOfSeq: 2, tokenUsage: { outputTokens: 12 } });
});
test('late idle statistics persist, ignore stale and child snapshots, and survive restart', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-metrics-'));
  const sessions = new DshSessions(root);
  try {
    const chat = sessions.create({ id: 'test', path: root });
    const item = sessions.get(chat.id);
    const frame = (id, seq, steps) => ({ method: 'session.metrics', params: { sessionId: id, asOfSeq: seq, values: { sessionStats: { steps } } } });
    sessions.notification(item, frame(chat.id, 10, 3));
    sessions.notification(item, frame(chat.id, 9, 2));
    sessions.notification(item, frame('child', 11, 100));
    assert.equal(item.metrics.sessionStats.steps, 3);
    sessions.flush(item);
    assert.equal(new DshSessions(root).get(chat.id).metrics.sessionStats.steps, 3);
  } finally { sessions.closeAll(); fs.rmSync(root, { recursive: true, force: true }); }
});
