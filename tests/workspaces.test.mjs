import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { Workspaces } from '/root/workspace/DSH-mobile-IDE-withTermux/bridge/workspaces.mjs';

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

test('home and root are always available, sdcard may not be', async () => {
  const ws = new Workspaces(root, {});
  const items = await ws.shortcuts();
  assert.ok(items.length >= 3, 'expected several starting points');
  assert.ok(items.every(item => typeof item.available === 'boolean'));
  assert.equal(new Set(items.map(item => item.path)).size, items.length, 'paths must be unique');
  // Regression: these were reported as permission-denied, which made the home
  // directory look inaccessible. The home of a running Termux is always readable.
  assert.ok(items.find(item => item.path === os.homedir()).available, 'home must be available');
  assert.ok(items.find(item => item.path === '/').available, 'the root directory is always readable');
  // /sdcard exists only after termux-setup-storage, so either answer is valid,
  // but it must be a real boolean rather than a guess.
  assert.ok(items.some(item => item.path === '/sdcard'));
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

test('the parent of a top-level folder is itself and never blank', () => {
  const ws = new Workspaces(root, {});
  return ws.browse('/').then(result => {
    assert.equal(result.parent, '/');
    assert.equal(result.path, '/');
  });
});

test.after(() => fs.rmSync(root, { recursive: true, force: true }));