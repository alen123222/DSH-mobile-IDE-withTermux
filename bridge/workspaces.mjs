import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { ApiError, directory, readJson, writeJson, text, within } from './util.mjs';

export class Workspaces {
  constructor(stateDir) {
    this.filename = path.join(stateDir, 'workspaces.json');
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
  browse(location = os.homedir(), dirsOnly = false) {
    const canonical = directory(location);
    const entries = fs.readdirSync(canonical, { withFileTypes: true }).flatMap(entry => {
      const filename = path.join(canonical, entry.name);
      try {
        const stat = fs.statSync(filename);
        if (dirsOnly && !stat.isDirectory()) return [];
        return [{ name: entry.name, path: filename, directory: stat.isDirectory(), size: stat.size, symlink: entry.isSymbolicLink() }];
      } catch { return []; /* Broken or inaccessible entries cannot be opened. */ }
    }).sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name));
    return { path: canonical, parent: path.dirname(canonical), home: os.homedir(), entries: entries.slice(0, 2000), truncated: entries.length > 2000 };
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
