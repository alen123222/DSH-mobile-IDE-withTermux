// Official whole-log projections handle retries, resumed history and compaction.
export const name = 'pocket-metrics';
export const inject = ['sessionProjections', 'tokenMeter'];
export function apply(ctx) {
  const pending = new Map();
  const keys = ['sessionStats', 'tokenUsage', 'contextPressure', 'contextBreakdown'];
  ctx.on('session/event', (session, event) => {
    if (!['step/end', 'turn/end', 'assistant/message', 'assistant/attempt', 'request/context', 'request/header', 'tool/result', 'system/message'].includes(event.type)) return;
    if (pending.has(session)) return;
    const timer = setTimeout(() => {
      pending.delete(session);
      try {
        const snapshot = ctx.sessionProjections.snapshot(session, keys);
        process.stdout.write(JSON.stringify({ jsonrpc: '2.0', method: 'session.metrics',
          params: { sessionId: String(session.id), ...snapshot } }) + '\n');
      } catch (error) { process.stderr.write('Pocket metrics unavailable: ' + error.message + '\n'); }
    }, 100);
    timer.unref?.();
    pending.set(session, timer);
  });
  ctx.on('dispose', () => { for (const timer of pending.values()) clearTimeout(timer); pending.clear(); });
}
