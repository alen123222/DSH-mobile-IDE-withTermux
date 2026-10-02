import { createInterface } from 'node:readline';
const send = value => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...value }) + '\n');
let cwd;
for await (const line of createInterface({ input: process.stdin })) {
  const request = JSON.parse(line);
  if (request.method === 'initialize') {
    cwd = request.params.cwd;
    send({ id: request.id, result: { serverInfo: { name: 'deepseek-harness-sdk-runtime', version: '0.0.1' } } });
  } else if (request.method === 'session/prompt') {
    const { sessionId, contentBlocks } = request.params;
    const messageId = 'message-' + request.id;
    const notify = (method, params) => send({ method, params: { sessionId, ...params } });
    // Exercise both races: initial idle before receipt, and events before RPC reply.
    notify('session.status', { status: 'idle' });
    notify('session.event', { event: { type: 'agent/inbox/spliced', data: { inserted: [{ id: messageId }] } } });
    notify('session.status', { status: 'running' });
    send({ id: request.id, result: { messageId } });
    if (contentBlocks[0].text === 'wait') continue;
    setTimeout(() => {
      send({ method: 'session.event', params: { sessionId: 'child', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: 'child must not replace root' }] } } } } });
      notify('session.event', { event: { type: 'tool/call', data: { name: 'bash', arguments: { command: 'pwd' } } } });
      notify('session.event', { event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: `cwd=${cwd}; ${contentBlocks[0].text}` }] } } } });
      notify('session.event', { event: { type: 'turn/end', data: { reason: { kind: 'completed' } } } });
      notify('session.status', { status: 'idle' });
    }, 60);
  } else if (request.method === 'shutdown') {
    send({ id: request.id, result: {} }); process.exit(0);
  }
}
