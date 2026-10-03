import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { ApiError, directory, readJson, writeJson, text, within } from './util.mjs';

export class Workspaces {
  constructor(stateDir, options = {}) {
    this.filename = path.join(stateDir, 'workspaces.json');
    this.options = options;
    this.items = readJson(this.filename, []);
  }
  all() {
    return this.items.map(item => ({ ...item, available: fs.existsSync(item.path) }))
      .sort((a, b) => Number(Boolean(b.starred)) - Number(Boolean(a.starred)));
  }
  get(id) {
    const item = this.items.find(item => item.id === id);
    if (!item) throw new ApiError(404, '工作区不存在');
    directory(item.path);
    return item;
  }
  add(location) {
    const canonical = directory(location);
    const existing = this.items.find(item => item.path === canonical);
    if (existing) return existing;
    const item = { id: crypto.randomUUID(), path: canonical, name: path.basename(canonical) || canonical, starred: false };
    this.items.push(item);
    writeJson(this.filename, this.items);
    return item;
  }
  remove(id) {
    const before = this.items.length;
    this.items = this.items.filter(item => item.id !== id);
    if (this.items.length === before) throw new ApiError(404, '工作区不存在');
    writeJson(this.filename, this.items);
  }
  star(id, value) {
    const item = this.items.find(item => item.id === id);
    if (!item) throw new ApiError(404, '工作区不存在');
    item.starred = value === true;
    writeJson(this.filename, this.items);
    return { ...item, available: fs.existsSync(item.path) };
  }
  async browse(location = os.homedir(), dirsOnly = false) {
    const canonical = directory(location);
    let dirents;
    try { dirents = await fs.promises.readdir(canonical, { withFileTypes: true }); }
    catch (error) {
      if (error.code === 'EACCES' || error.code === 'EPERM') throw new ApiError(403,
        '没有读取该目录的权限。共享存储需要先在 Termux 执行一次 termux-setup-storage。');
      if (error.code === 'ELOOP') throw new ApiError(400, '目录链接形成循环');
      if (error.code === 'ENOENT') throw new ApiError(404, '目录不存在');
      throw error;
    }
    // Shared storage over Android's FUSE mount costs tens of milliseconds per
    // entry, and the old code statted every entry. A readdir with withFileTypes
    // already carries the file type, so dirsOnly is answered without touching
    // the filesystem at all, and sizes cost one stat only for real files.
    // Do not resolve symlinks: on FUSE that costs a syscall per entry, and a
    // link does not need following to appear in a listing.
    let entries = dirents.map(entry => ({
      name: entry.name,
      path: path.join(canonical, entry.name),
      directory: entry.isDirectory(),
      size: -1,
      symlink: entry.isSymbolicLink(),
    }));
    if (!dirsOnly) {
      // File sizes need a stat each; keep that off the directory listing path.
      const settled = await Promise.all(entries.map(entry =>
        entry.symlink || entry.directory ? Promise.resolve(entry)
          : fs.promises.stat(entry.path).then(stat => ({ ...entry, size: stat.size })).catch(() => entry)));
      entries = settled;
    }
    entries = entries
      .filter(entry => !(dirsOnly && !entry.directory))
      .sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name));
    const limit = this.options.browseLimit ?? 2000;
    return { path: canonical, parent: path.dirname(canonical), home: os.homedir(),
      entries: entries.slice(0, limit), truncated: entries.length > limit, total: entries.length };
  }
  /** Well-known starting points for the picker, with availability resolved server-side. */
  shortcuts() {
    const home = os.homedir();
    const candidates = [
      { label: '主目录', path: home },
      { label: '内部存储', path: '/storage/emulated/0' },
      { label: '共享存储', path: '/sdcard' },
      { label: 'Download', path: path.join(home, 'storage', 'downloads') },
      { label: '根目录', path: '/' },
    ];
    const seen = new Set();
    return candidates
      .filter(item => !seen.has(item.path) && seen.add(item.path))
      .map(item => {
        let available = false;
        try { available = fs.statSync(item.path).isDirectory(); }
        catch { available = false; }
        return { ...item, available };
      });
  }

  create(parent, name) {
    text(name, '目录名', 255);
    if (name === '.' || name === '..' || /[\\/]/.test(name)) throw new ApiError(400, '目录名不能包含路径分隔符');
    const target = path.join(directory(parent), name);
    fs.mkdirSync(target);
    return { path: target };
  }
  read(id, location) {
    const workspace = this.get(id);
    const target = fs.realpathSync(text(location, '文件路径'));
    if (!within(workspace.path, target)) throw new ApiError(403, '文件位于当前工作区之外，请添加对应工作区');
    const stat = fs.statSync(target);
    if (!stat.isFile()) throw new ApiError(400, '请选择普通文件');
    if (stat.size > 512 * 1024) throw new ApiError(413, '预览支持 512 KB 以内的文本文件，可在终端打开更大的文件');
    const bytes = fs.readFileSync(target);
    if (bytes.includes(0)) throw new ApiError(415, '此文件不是可预览的文本文件');
    return { path: target, content: bytes.toString('utf8') };
  }
}
