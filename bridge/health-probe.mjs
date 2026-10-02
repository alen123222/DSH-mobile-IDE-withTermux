// DSH Pocket: diagnose why the Android app cannot reach the local service.
// Run from Termux via RUN_COMMAND; prints what Termux itself observes on the
// same loopback port the app uses, so a mismatch can be identified instead of
// guessed at.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const stateDir = process.env.POCKET_HOME || path.join(os.homedir(), '.local', 'share', 'dsh-pocket');
const configPath = path.join(stateDir, 'connection.json');

function line(label, value) { console.log(`${label}: ${value}`); }

line('node', process.version);
line('state dir', stateDir);
line('bridge version expected', process.env.POCKET_EXPECT_VERSION || '(any)');

let config = null;
try { config = JSON.parse(fs.readFileSync(configPath, 'utf8')); }
catch (error) { line('connection.json', `UNREADABLE (${error.code || error.message})`); }
if (!config) {
  line('result', 'cannot probe: no usable connection.json');
  process.exit(1);
}
line('port', config.port ?? 8764);
line('token length', String(config.token || '').length);

const port = config.port || 8765;
// 1. Is anything listening at all?
const net = await import('node:net');
await new Promise(resolve => {
  const socket = net.connect({ host: '127.0.0.1', port }, () => {
    line('tcp connect', `OK (pid ${socket.remotePort} -> ${socket.localPort})`);
    socket.destroy();
    resolve();
  });
  socket.setTimeout(2000, () => { line('tcp connect', 'TIMEOUT'); socket.destroy(); resolve(); });
  socket.on('error', error => { line('tcp connect', `REFUSED/ERROR (${error.code})`); resolve(); });
});

// 2. Does it answer /v1/health with this token?
try {
  const response = await fetch(`http://127.0.0.1:${port}/v1/health`,
    { headers: { Authorization: `Bearer ${config.token}` }, signal: AbortSignal.timeout(3000) });
  line('http status', response.status);
  const text = await response.text();
  line('http body', text.slice(0, 400));
  const data = JSON.parse(text);
  line('service version', data.version);
  line('service pid', data.pid);
  if (data.pid && Number.isSafeInteger(data.pid)) {
    line('service alive', (() => { try { process.kill(data.pid, 0); return 'yes'; } catch { return 'no'; } })());
  }
  line('result', response.ok
    ? 'the service is healthy and reachable from Termux. If the app still reports not connected, the block is between the app and loopback.'
    : 'the service rejected this token; delete connection.json and reconnect from the app.');
} catch (error) {
  line('http error', error.message);
  line('result', 'no HTTP answer from the loopback service.');
  process.exit(1);
}