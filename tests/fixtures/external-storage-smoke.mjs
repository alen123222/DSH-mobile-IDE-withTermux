// Run inside Termux after starting the app's bridge. Creates one small demo
// project on the explicitly supplied volume; never uses a real API key/model.
import fs from 'node:fs';
import path from 'node:path';
const stateDir = process.env.POCKET_HOME;
const volume = process.env.POCKET_TEST_VOLUME;
if (!stateDir || !volume?.startsWith('/storage/')) throw new Error('Set POCKET_HOME and POCKET_TEST_VOLUME explicitly');
const config = JSON.parse(fs.readFileSync(path.join(stateDir, 'connection.json'), 'utf8'));
const base = `http://127.0.0.1:${config.port}/v1/`;
async function request(route, method = 'GET', body) {
  const r = await fetch(base + route, { method, headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000) });
  const data = await r.json();
  if (!r.ok) throw new Error(`${r.status}: ${data.error}`);
  return data;
}
const health = await request('health');
if (health.version !== '0.4.4') throw new Error('Update the local service first');
const shortcuts = await request(`shortcuts?external=${encodeURIComponent(volume)}`);
if (!shortcuts.items.some(x => x.path === volume)) throw new Error('External mount shortcut missing');
console.log(JSON.stringify({ stage: 'discovery', external: shortcuts.items.filter(x => x.path === volume || /external-\d+$/.test(x.path)) }));
let parent = volume;
try { await request('workspace-check', 'POST', { path: parent }); }
catch (e) {
  console.log(JSON.stringify({ stage: 'volume-root', writable: false, error: e.message }));
  parent = path.join(volume, 'Android/data/com.termux/files');
  await request('workspace-check', 'POST', { path: parent });
}
const existing = process.env.POCKET_TEST_PROJECT;
if (existing && (!existing.startsWith(volume + '/dsh-pocket-demo-') || existing.includes('..'))) throw new Error('Invalid demo project path');
const created = existing ? { path: existing } : await request('directories', 'POST', { parent, name: `dsh-pocket-demo-${Date.now()}` });
const workspace = await request('workspaces', 'POST', { path: created.path });
const terminal = await request('terminals', 'POST', { workspaceId: workspace.id });
try {
  await request(`terminals/${terminal.id}`, 'POST', { type: 'resize', rows: 30, cols: 100 });
  const command = `printf '%s\\n' "print('POCKET_EXTERNAL_OK')" > storage_check.py\npython -m py_compile storage_check.py && python storage_check.py\npwd\n`;
  await request(`terminals/${terminal.id}`, 'POST', { type: 'input', data: Buffer.from(command).toString('base64') });
  let output = '';
  for (let i = 0; i < 80; i++) {
    const result = await request(`terminals/${terminal.id}?after=0`);
    output = result.chunks.map(c => Buffer.from(c.data, 'base64').toString()).join('');
    if (/[\r\n]POCKET_EXTERNAL_OK\r*\n/.test(output)) break;
    await new Promise(r => setTimeout(r, 150));
  }
  if (!/[\r\n]POCKET_EXTERNAL_OK\r*\n/.test(output)) throw new Error(`Command failed: ${output}`);
  const preview = await request(`file?workspaceId=${workspace.id}&path=${encodeURIComponent(path.join(workspace.path, 'storage_check.py'))}`);
  if (!preview.content.includes('POCKET_EXTERNAL_OK')) throw new Error('Preview read did not match the file written through the terminal');
  const listing = await request(`browse?path=${encodeURIComponent(workspace.path)}`);
  if (!listing.entries.some(e => e.name === '__pycache__' && e.directory)) throw new Error('Compilation did not create bytecode');
  console.log(JSON.stringify({ passed: true, workspace: workspace.path, workspaceId: workspace.id,
    verified: ['external discovery', 'create directory', 'read/write probe', 'save workspace', 'real PTY commands', 'Python bytecode compilation', 'file preview'] }));
} finally { await request(`terminals/${terminal.id}`, 'DELETE'); }
