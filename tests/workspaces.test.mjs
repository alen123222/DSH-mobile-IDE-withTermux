import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { Workspaces } from '../bridge/workspaces.mjs';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-ws-'));

test('a large folder lists quickly because dirsOnly needs no stat per entry', async () => {
  const wide = path.join(root, 'wide');
  fs.mkdirSync(wide);
  for (let i = 0; i < 1500; i++) fs.mkdirSync(path.join(wide, `d${String(i).padStart(4, '0')}`));
  for (let i = 0; i < 300; i++) fs.writeFileSync(path.join(wide, `f${i}.txt`), 'x');

  const ws = new Workspaces(root, {});
  // Emulate FUSE: every stat costs 100 ms. A per-entry stat implementation
  // would need 1500 of them; withFileTypes needs none for a dirs-only listing.
  const realStat = fs.promises.stat.bind(fs.promises);
  fs.promises.stat = async target => { await new Promise(r => setTimeout(r, 100)); return realStat(target); };
  const started = Date.now();
  const result = await ws.browse(wide, true);
  const elapsed = Date.now() - started;
  fs.promises.stat = realStat;

  assert.equal(result.total, 1500);
  assert.equal(result.entries.length, 1500);
  assert.ok(result.entries.every(entry => entry.directory), 'files must be filtered out for a dirs-only listing');
  // 1500 stats at 100 ms each cannot be concurrent-limited below ~1.5 s; the
  // fix makes the listing independent of the entry count.
  assert.ok(elapsed < 1000, `dirs-only listing took ${elapsed} ms, expected no per-entry stat`);
});

test('a file listing still reports sizes', async () => {
  const dir = path.join(root, 'files');
  fs.mkdirSync(dir);
  fs.writeFileSync(path.join(dir, 'a.txt'), 'hello');
  const ws = new Workspaces(root, {});
  const result = await ws.browse(dir);
  const entry = result.entries.find(item => item.name === 'a.txt');
  assert.equal(entry.directory, false);
  assert.equal(entry.size, 5);
});

test('an unreadable folder explains the termux-setup-storage step', async () => {
  const ws = new Workspaces(root, {});
  await assert.rejects(() => ws.browse('/definitely/not/here'), /目录不存在|没有读取/);
});

test('a missing folder is reported as 404, not a crash', async () => {
  const ws = new Workspaces(root, {});
  await assert.rejects(() => ws.browse('/nope-' + Date.now()), error => {
    assert.equal(error.status, 404);
    return true;
  });
});

test('the picker offers home, internal storage and mounted volumes only', async () => {
  const ws = new Workspaces(root, {});
  const items = await ws.shortcuts();
  assert.ok(items.length >= 2, 'expected at least home and internal storage');
  assert.ok(items.every(item => typeof item.available === 'boolean'));
  assert.equal(new Set(items.map(item => item.path)).size, items.length, 'paths must be unique');
  // Regression: these were reported as permission-denied, which made the home
  // directory look inaccessible. The home of a running Termux is always readable.
  assert.ok(items.find(item => item.path === os.homedir()).available, 'home must be available');
  assert.ok(items.some(item => item.path === '/storage/emulated/0'), 'internal storage stays available');
  // Dead ends are removed rather than shown permanently disabled: /sdcard needs
  // termux-setup-storage, and an Android app uid cannot list "/" at all (EACCES).
  const dropped = ['/sdcard', '/', path.parse(os.homedir()).root, path.join(os.homedir(), 'storage', 'downloads')];
  assert.ok(!items.some(item => dropped.includes(item.path)), 'no dead-end shortcuts');
});

test('a shortcut is tested by reading it, not by statSync alone', async () => {
  // Android's FUSE mount can fail statSync where readdir succeeds, which would
  // have reported a readable folder as forbidden.
  const ws = new Workspaces(root, {});
  const realStatSync = fs.statSync;
  fs.statSync = () => { throw new Error('statSync refused'); };
  let items;
  try { items = await ws.shortcuts(); }
  finally { fs.statSync = realStatSync; }
  assert.ok(items.find(item => item.path === os.homedir()).available,
    'home stays available even when statSync throws');
});

test('the parent of a top-level folder is itself and never blank', async t => {
  const top = path.parse(os.homedir()).root;
  try { const dir = await fs.promises.opendir(top); await dir.close(); }
  catch { t.skip(`the filesystem root ${top} is not readable on this platform`); return; }
  const result = await new Workspaces(root, {}).browse(top);
  assert.equal(result.parent, top);
  assert.equal(result.path, top);
});

test.after(() => fs.rmSync(root, { recursive: true, force: true }));

test('an external volume is offered and a Termux link still browses and resolves', async () => {
  const home = path.join(root, 'home'), storageRoot = path.join(root, 'storage');
  const volume = path.join(storageRoot, '1234-ABCD');
  fs.mkdirSync(path.join(home, 'storage'), { recursive: true });
  fs.mkdirSync(volume, { recursive: true });
  const link = path.join(home, 'storage', 'external-1');
  fs.symlinkSync(volume, link, process.platform === 'win32' ? 'junction' : 'dir');
  const ws = new Workspaces(path.join(root, 'state'), { home, storageRoot });
  const items = await ws.shortcuts();
  assert.ok(items.find(i => i.path === volume)?.available);
  // The Termux link is no longer its own chip, but it must still browse and
  // resolve, which is what the directory-symlink fix guarantees.
  assert.ok(!items.some(i => i.path === link), 'the storage link is not a separate shortcut');
  assert.ok((await ws.browse(path.dirname(link), true)).entries.find(e => e.name === 'external-1')?.directory);
  const created = ws.create('~/storage/external-1', '工程 with spaces');
  const workspace = await ws.add(created.path);
  assert.equal(workspace.path, fs.realpathSync(created.path));
  fs.writeFileSync(path.join(workspace.path, 'main.txt'), 'external project');
  assert.equal(ws.file(workspace.id, path.join(workspace.path, 'main.txt')).text, 'external project');
  assert.equal(new Workspaces(path.join(root, 'state')).all()[0].path, workspace.path);
  fs.renameSync(volume, volume + '-unplugged');
  assert.equal(ws.all()[0].available, false);
  await assert.rejects(ws.validate(workspace.path), /不存在|拔出/);
});

test('the viewer classifies code, GBK text, images, PDFs, archives and binaries', async () => {
  const ws = new Workspaces(path.join(root, 'kind-state'));
  const project = path.join(root, 'kind-project');
  fs.mkdirSync(project, { recursive: true });
  const write = (name, value) => fs.writeFileSync(path.join(project, name), value);
  write('main.py', 'def main():\n    print("hi")\n');
  write('notes.txt', Buffer.from([0xc4, 0xe3, 0xba, 0xc3])); // 你好, GBK
  write('blob.bin', Buffer.from([1, 2, 0, 4]));
  write('shot.png', Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1]));
  write('doc.pdf', Buffer.from('%PDF-1.4\n'));
  write('pack.zip', Buffer.from([0x50, 0x4b, 0x03, 0x04, 1]));
  write('mystery', Buffer.from('%PDF-1.7\n')); // no extension: the magic number decides
  const workspace = await ws.add(project);
  const info = name => ws.file(workspace.id, path.join(project, name));
  assert.equal(info('main.py').kind, 'text');
  assert.equal(info('main.py').language, 'python');
  assert.equal(info('main.py').lines, 3);
  assert.match(info('main.py').mime, /text\/plain/);
  // A GBK source file must not come back as replacement characters.
  assert.equal(info('notes.txt').encoding, 'gbk');
  assert.equal(info('notes.txt').text, '你好');
  assert.equal(info('blob.bin').kind, 'binary');
  assert.equal(info('shot.png').kind, 'image');
  assert.equal(info('doc.pdf').kind, 'pdf');
  assert.equal(info('pack.zip').kind, 'archive');
  assert.equal(info('mystery').kind, 'pdf', 'a magic number beats a missing extension');
});

test('saving is atomic, keeps the mode and refuses a stale mtime', async () => {
  const ws = new Workspaces(path.join(root, 'save-state'));
  const project = path.join(root, 'save-project');
  fs.mkdirSync(project, { recursive: true });
  const file = path.join(project, 'app.kt');
  fs.writeFileSync(file, 'val x = 1\n');
  const workspace = await ws.add(project);
  const before = ws.file(workspace.id, file);
  const saved = ws.save(workspace.id, file, { content: 'val x = 2\n', ifMtime: before.mtime });
  assert.equal(fs.readFileSync(file, 'utf8'), 'val x = 2\n');
  assert.equal(saved.size, 10);
  assert.deepEqual(fs.readdirSync(project), ['app.kt'], 'no temporary file is left behind');
  // The terminal rewrote the file while it sat open in the editor. Move the
  // mtime explicitly: two writes inside one millisecond would not prove the fence.
  const rewound = before.mtime - 5000;
  fs.utimesSync(file, new Date(rewound), new Date(rewound));
  assert.throws(() => ws.save(workspace.id, file, { content: 'stale', ifMtime: before.mtime }),
    error => error.status === 409 && /外部修改/.test(error.message));
  // Without ifMtime the editor can still force the write it already confirmed.
  assert.equal(ws.save(workspace.id, file, { content: 'forced\n' }).size, 7);
});

test('raw streams the bytes and refuses anything outside the workspace', async () => {
  const ws = new Workspaces(path.join(root, 'raw-state'));
  const project = path.join(root, 'raw-project');
  fs.mkdirSync(project, { recursive: true });
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 9, 9]);
  fs.writeFileSync(path.join(project, 'a.png'), png);
  const workspace = await ws.add(project);
  const info = ws.raw(workspace.id, path.join(project, 'a.png'));
  assert.equal(info.mime, 'image/png');
  assert.deepEqual(fs.readFileSync(info.path), png);
  const outside = path.join(root, 'outside.txt');
  fs.writeFileSync(outside, 'secret');
  assert.throws(() => ws.file(workspace.id, outside), error => error.status === 403);
  assert.throws(() => ws.raw(workspace.id, outside), error => error.status === 403);
  assert.throws(() => ws.save(workspace.id, outside, { content: 'x' }), error => error.status === 403);
});

// Minimal stored-entry zip writer: enough to prove the reader, with no dependency.
const CRC_TABLE = [...Array(256)].map((_, n) => {
  let value = n;
  for (let k = 0; k < 8; k++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});
function crc32(buffer) {
  let value = 0xffffffff;
  for (const byte of buffer) value = CRC_TABLE[(value ^ byte) & 0xff] ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
}
function buildZip(entries) {
  const locals = [], centrals = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const data = entry.data;
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, data);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28); central.writeUInt32LE(offset, 42);
    centrals.push(central, name);
    offset += local.length + name.length + data.length;
  }
  const centralBuffer = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBuffer.length, 12); eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuffer, eocd]);
}

test('extracting a zip stays inside the destination and refuses zip-slip', async () => {
  const ws = new Workspaces(path.join(root, 'zip-state'));
  const project = path.join(root, 'zip-project');
  fs.mkdirSync(project, { recursive: true });
  const archive = path.join(project, 'bundle.zip');
  fs.writeFileSync(archive, buildZip([
    { name: 'docs/readme.txt', data: Buffer.from('hello zip') },
    { name: 'nested/app.py', data: Buffer.from('print(1)\n') },
  ]));
  const workspace = await ws.add(project);
  const result = ws.extract(workspace.id, archive);
  assert.equal(result.entries, 2);
  assert.equal(fs.readFileSync(path.join(result.path, 'docs/readme.txt'), 'utf8'), 'hello zip');
  assert.equal(fs.readFileSync(path.join(result.path, 'nested/app.py'), 'utf8'), 'print(1)\n');
  assert.equal(path.dirname(result.path), project, 'extracted next to the archive');
  // Extracting twice must not overwrite the first result.
  assert.notEqual(ws.extract(workspace.id, archive).path, result.path);
  const evil = path.join(project, 'evil.zip');
  fs.writeFileSync(evil, buildZip([{ name: '../escape.txt', data: Buffer.from('x') }]));
  assert.throws(() => ws.extract(workspace.id, evil), error => error.status === 400 && /不安全/.test(error.message));
  assert.equal(fs.existsSync(path.join(root, 'escape.txt')), false);
  assert.equal(fs.existsSync(path.join(project, 'evil')), false, 'a refused extraction leaves nothing behind');
});

test('read-only storage cannot be saved as a writable workspace', async () => {
  const ws = new Workspaces(path.join(root, 'readonly-state'));
  const original = fs.promises.open;
  fs.promises.open = async () => { throw Object.assign(new Error('read only'), { code: 'EROFS' }); };
  try { await assert.rejects(ws.add(root), error => error.status === 403 && /只读/.test(error.message)); }
  finally { fs.promises.open = original; }
  assert.equal(ws.all().length, 0);
});
