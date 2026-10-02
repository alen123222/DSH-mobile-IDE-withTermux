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
    let names;
    try { names = await fs.promises.readdir(canonical); }
    catch (error) {
      if (error.code === 'EACCES' || error.code === 'EPERM') throw new ApiError(403, '没有读取该目录的权限');
      if (error.code === 'ELOOP') throw new ApiError(400, '目录链接形成循环');
      throw error;
    }
    // Shared storage over Android's FUSE mount costs tens of milliseconds per
    // entry. The old synchronous statSync loop blocked the whole event loop for
    // seconds on a large folder, which starved /health and timed out the UI.
    const settled = await Promise.all(names.map(name => fs.promises
      .stat(path.join(canonical, name))
      .then(stat => ({ name, stat }), () => null)));
    const entries = settled.filter(Boolean)
      .filter(({ stat }) => !(dirsOnly && !stat.isDirectory()))
      .map(({ name, stat }) => ({ name, path: path.join(canonical, name),
        directory: stat.isDirectory(), size: stat.size, symlink: false }))
      .sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name));
    const limit = this.options.browseLimit ?? 2000;
    return { path: canonical, parent: path.dirname(canonical), home: os.homedir(),
      entries: entries.slice(0, limit), truncated: entries.length > limit, total: entries.length };
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
