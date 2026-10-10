import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { ApiError, directory, directoryAsync, readJson, writeJson, text, within } from './util.mjs';
import { t } from './i18n.mjs';

async function mapLimited(items, transform, concurrency = 8) {
  const result = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (next < items.length) { const index = next++; result[index] = await transform(items[index]); }
  }));
  return result;
}

async function storageDeadline(operation, milliseconds) {
  let timer;
  try {
    return await Promise.race([operation, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new ApiError(504,
        t('存储响应超时，请检查 Termux 存储权限或重新连接外接盘后刷新。'))), milliseconds);
    })]);
  } finally { clearTimeout(timer); }
}

function storageError(error) {
  if (['EACCES', 'EPERM', 'EROFS'].includes(error.code)) return new ApiError(403,
    t('Termux 无法读写此目录，或存储为只读。请先授权共享存储；外接盘可尝试 ~/storage/external-1。Android 的文件选择器授权不会自动授予 Termux 路径权限。'));
  if (['ENOENT', 'ENODEV'].includes(error.code)) return new ApiError(404, t('目录不存在或外接存储已拔出，请连接后刷新'));
  return error;
}

// --- File types -------------------------------------------------------------
// The viewer decides what it can render from 'kind'; the app never guesses from
// the name alone, because an extension can lie and a file may have none at all.
const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'heic', 'heif', 'avif', 'ico']);
const PDF_EXT = new Set(['pdf']);
const ARCHIVE_EXT = new Set(['zip', 'jar', 'apk', 'aar', 'whl', 'epub', 'docx', 'xlsx', 'pptx', 'odt', 'ods', 'odp']);
const MIME = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp',
  heic: 'image/heic', heif: 'image/heif', avif: 'image/avif', ico: 'image/x-icon', pdf: 'application/pdf',
  zip: 'application/zip', jar: 'application/java-archive', aar: 'application/zip', whl: 'application/zip',
  epub: 'application/epub+zip', apk: 'application/vnd.android.package-archive',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  md: 'text/markdown; charset=utf-8', json: 'application/json; charset=utf-8', xml: 'text/xml; charset=utf-8',
  html: 'text/html; charset=utf-8', htm: 'text/html; charset=utf-8', css: 'text/css; charset=utf-8',
  txt: 'text/plain; charset=utf-8', log: 'text/plain; charset=utf-8',
};
const LANGUAGES = {
  c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp', py: 'python', pyw: 'python',
  java: 'java', kt: 'kotlin', kts: 'kotlin', js: 'javascript', mjs: 'javascript', cjs: 'javascript',
  ts: 'typescript', tsx: 'typescript', jsx: 'javascript', json: 'json', md: 'markdown', markdown: 'markdown',
  sh: 'shell', bash: 'shell', zsh: 'shell', fish: 'shell', yml: 'yaml', yaml: 'yaml', toml: 'toml',
  xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml', css: 'css', scss: 'css', gradle: 'kotlin',
  sql: 'sql', rs: 'rust', go: 'go', rb: 'ruby', php: 'php', swift: 'swift', cs: 'csharp', lua: 'lua',
  dart: 'dart', vue: 'xml', properties: 'plain', ini: 'plain', conf: 'plain', env: 'shell', csv: 'plain',
};
function mimeOf(ext) { return MIME[ext] || 'application/octet-stream'; }
function extensionOf(target) {
  const name = path.basename(target).toLowerCase();
  if (name.startsWith('.') && !name.includes('.', 1)) return name.slice(1); // .gitignore, .env
  return path.extname(name).slice(1);
}
function languageOf(target, ext) {
  if (LANGUAGES[ext]) return LANGUAGES[ext];
  const name = path.basename(target).toLowerCase();
  if (name === 'makefile' || name === 'dockerfile' || name.startsWith('dockerfile.')) return 'shell';
  return 'plain';
}
function sniff(head) {
  if (head.length >= 4 && head[0] === 0x89 && head.subarray(1, 4).toString('latin1') === 'PNG') return 'image';
  if (head.length >= 3 && head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff) return 'image';
  if (head.length >= 4 && head.subarray(0, 4).toString('latin1') === 'GIF8') return 'image';
  if (head.length >= 12 && head.subarray(0, 4).toString('latin1') === 'RIFF' && head.subarray(8, 12).toString('latin1') === 'WEBP') return 'image';
  if (head.length >= 2 && head[0] === 0x42 && head[1] === 0x4d) return 'image';
  if (head.length >= 4 && head.subarray(0, 4).toString('latin1') === '%PDF') return 'pdf';
  if (head.length >= 3 && head[0] === 0x50 && head[1] === 0x4b && [0x03, 0x05, 0x07].includes(head[2])) return 'archive';
  return null;
}
function kindOf(target, head) {
  const ext = extensionOf(target);
  if (IMAGE_EXT.has(ext)) return 'image';
  if (PDF_EXT.has(ext)) return 'pdf';
  if (ARCHIVE_EXT.has(ext)) return 'archive';
  return sniff(head); // null means "decide from the bytes".
}
function readHead(target, length) {
  const size = fs.statSync(target).size;
  const buffer = Buffer.alloc(Math.min(length, size));
  if (!buffer.length) return buffer;
  const handle = fs.openSync(target, 'r');
  try { fs.readSync(handle, buffer, 0, buffer.length, 0); } finally { fs.closeSync(handle); }
  return buffer;
}
// Terminal users keep GBK sources around. Decoding those as UTF-8 turns every
// Chinese comment into replacement characters, so try UTF-8 strictly first and
// fall back to GBK only when the result is actually printable.
function decodeText(bytes) {
  if (bytes.includes(0)) return null;
  try { return { text: new TextDecoder('utf-8', { fatal: true }).decode(bytes), encoding: 'utf8' }; }
  catch { /* Not UTF-8; try the fallback below. */ }
  let text;
  try { text = new TextDecoder('gbk').decode(bytes); }
  catch { return null; } // This Node has no GBK table; treat it as binary.
  let control = 0;
  for (const char of text) {
    const code = char.codePointAt(0);
    if (code === 0xfffd || code < 9 || (code > 13 && code < 32)) control++;
  }
  if (text.length && control / text.length > 0.02) return null;
  return { text, encoding: 'gbk' };
}

// --- Zip reading ------------------------------------------------------------
// The bridge ships as assets and Termux has no unzip by default, so the central
// directory is parsed here. Stored and deflated entries cover essentially every
// archive a phone produces; anything else is refused with a clear message.
function zipDirectory(archive) {
  let eocd = -1;
  const floor = Math.max(0, archive.length - 22 - 0xffff);
  for (let i = archive.length - 22; i >= floor; i--) {
    if (archive.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new ApiError(415, t('这不是有效的 zip 压缩包'));
  const count = archive.readUInt16LE(eocd + 10);
  if (count === 0xffff) throw new ApiError(415, t('暂不支持 ZIP64 压缩包'));
  let offset = archive.readUInt32LE(eocd + 16);
  const entries = [];
  for (let index = 0; index < count; index++) {
    if (offset + 46 > archive.length || archive.readUInt32LE(offset) !== 0x02014b50) break;
    const nameLength = archive.readUInt16LE(offset + 28);
    const extraLength = archive.readUInt16LE(offset + 30);
    const commentLength = archive.readUInt16LE(offset + 32);
    entries.push({
      name: archive.subarray(offset + 46, offset + 46 + nameLength).toString('utf8'),
      method: archive.readUInt16LE(offset + 10),
      compressed: archive.readUInt32LE(offset + 20),
      local: archive.readUInt32LE(offset + 42),
    });
    offset += 46 + nameLength + extraLength + commentLength;
    if (entries.length > 20000) break;
  }
  return entries;
}
function zipRead(archive, entry) {
  const local = entry.local;
  if (local + 30 > archive.length || archive.readUInt32LE(local) !== 0x04034b50) throw new ApiError(415, t('压缩包已损坏'));
  const start = local + 30 + archive.readUInt16LE(local + 26) + archive.readUInt16LE(local + 28);
  const data = archive.subarray(start, start + entry.compressed);
  if (entry.method === 0) return data;
  if (entry.method === 8) return zlib.inflateRawSync(data);
  throw new ApiError(415, t('压缩包使用了不支持的压缩方式'));
}

export class Workspaces {
  constructor(stateDir, options = {}) {
    this.filename = path.join(stateDir, 'workspaces.json');
    this.options = options;
    this.items = readJson(this.filename, []);
  }
  location(value) {
    if (value === '~' || value?.startsWith('~/')) return path.join(this.options.home || os.homedir(), value.slice(2));
    if (value?.startsWith('content:')) throw new ApiError(400, t('请选择 Termux 可访问的文件路径；Android 文档 URI 不能作为命令工作目录'));
    return value;
  }
  all() {
    return this.items.map(item => ({ ...item, available: fs.existsSync(item.path) }))
      .sort((a, b) => Number(Boolean(b.starred)) - Number(Boolean(a.starred)));
  }
  get(id) {
    const item = this.items.find(item => item.id === id);
    if (!item) throw new ApiError(404, t('工作区不存在'));
    directory(item.path);
    return item;
  }
  async validate(location) {
    let probe, owned = false;
    try {
      const canonical = await directoryAsync(this.location(location));
      const dir = await fs.promises.opendir(canonical);
      await dir.close();
      probe = path.join(canonical, `.dsh-pocket-check-${crypto.randomUUID()}`);
      const handle = await fs.promises.open(probe, 'wx', 0o600);
      owned = true;
      try { await handle.writeFile('DSH Pocket storage check\n'); }
      finally { await handle.close(); }
      await fs.promises.readFile(probe);
      await fs.promises.unlink(probe);
      owned = false;
      return { path: canonical, writable: true };
    } catch (error) { throw storageError(error); }
    finally { if (owned) await fs.promises.unlink(probe).catch(() => {}); }
  }
  async add(location) {
    const { path: canonical } = await this.validate(location);
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
    if (this.items.length === before) throw new ApiError(404, t('工作区不存在'));
    writeJson(this.filename, this.items);
  }
  star(id, value) {
    const item = this.items.find(item => item.id === id);
    if (!item) throw new ApiError(404, t('工作区不存在'));
    item.starred = value === true;
    writeJson(this.filename, this.items);
    return { ...item, available: fs.existsSync(item.path) };
  }
  async browse(location = os.homedir(), dirsOnly = false) {
    const canonical = await directoryAsync(this.location(location));
    let dirents;
    try { dirents = await fs.promises.readdir(canonical, { withFileTypes: true }); }
    catch (error) {
      if (error.code === 'EACCES' || error.code === 'EPERM') throw new ApiError(403,
        t('没有读取该目录的权限。共享存储需要先在 Termux 执行一次 termux-setup-storage。'));
      if (error.code === 'ELOOP') throw new ApiError(400, t('目录链接形成循环'));
      if (error.code === 'ENOENT') throw new ApiError(404, t('目录不存在'));
      throw error;
    }
    // Shared storage over Android's FUSE mount costs tens of milliseconds per
    // entry, and the old code statted every entry. A readdir with withFileTypes
    // already carries the file type, so dirsOnly is answered without touching
    // the filesystem at all, and sizes cost one stat only for real files.
    // Only links need a stat to discover whether they lead to a directory.
    let entries = dirents.map(entry => ({
      name: entry.name,
      path: path.join(canonical, entry.name),
      directory: entry.isDirectory(),
      size: -1,
      symlink: entry.isSymbolicLink(),
    }));
    entries = await mapLimited(entries, async entry => {
      if (!entry.symlink && (entry.directory || dirsOnly)) return entry;
      try {
        const stat = await fs.promises.stat(entry.path);
        return { ...entry, directory: stat.isDirectory(), size: stat.isFile() ? stat.size : -1 };
      } catch { return entry; }
    });
    entries = entries
      .filter(entry => !(dirsOnly && !entry.directory))
      .sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name));
    const limit = this.options.browseLimit ?? 2000;
    return { path: canonical, parent: path.dirname(canonical), home: os.homedir(),
      entries: entries.slice(0, limit), truncated: entries.length > limit, total: entries.length };
  }
  /** Well-known starting points for the picker, with availability resolved server-side. */
  async shortcuts(extraPaths = []) {
    const home = this.options.home || os.homedir();
    const storageRoot = this.options.storageRoot || '/storage';
    // Three starting points are enough: home, internal storage, external volumes.
    // /sdcard, ~/storage/downloads and the filesystem root are either unreachable
    // for a Termux uid or one tap away from internal storage, and each extra chip
    // only made the picker noisier.
    const candidates = [
      { label: t('主目录'), path: home },
      { label: t('内部存储'), path: '/storage/emulated/0' },
    ];
    const budget = this.options.storageProbeTimeoutMs ?? 1500;
    const volumes = await storageDeadline(fs.promises.readdir(storageRoot, { withFileTypes: true }), budget).catch(() => []);
    for (const volume of volumes) {
      if (['emulated', 'self'].includes(volume.name) || (!volume.isDirectory() && !volume.isSymbolicLink())) continue;
      candidates.push({ label: `外接存储 · ${volume.name}`, path: path.join(storageRoot, volume.name) });
    }
    // Android's StorageManager can supply mount paths even when Termux cannot list /storage.
    for (const location of extraPaths.slice(0, 16)) {
      if (typeof location === 'string' && path.isAbsolute(location) && location.startsWith('/storage/')) {
        candidates.push({ label: `外接存储 · ${path.basename(location)}`, path: location });
      }
    }
    const seen = new Set();
    const unique = candidates.filter(item => !seen.has(item.path) && seen.add(item.path));
    // Reading a directory is the real test. A bare statSync on shared storage can
    // fail where the directory itself opens fine, and reporting that as "no
    // permission" would be wrong, so try to list it instead.
    return await Promise.all(unique.map(async item => {
      let available = false;
      let reason = '';
      try {
        // Close even when a delayed open finishes after the deadline.
        await storageDeadline((async () => {
          const dir = await fs.promises.opendir(item.path);
          await dir.close();
        })(), budget);
        available = true;
      }
      catch (error) { reason = storageError(error).message; }
      return { ...item, available, reason };
    }));
  }

  create(parent, name) {
    text(name, t('目录名'), 255);
    if (name === '.' || name === '..' || /[\\/]/.test(name)) throw new ApiError(400, t('目录名不能包含路径分隔符'));
    const target = path.join(directory(this.location(parent)), name);
    try { fs.mkdirSync(target); } catch (error) { throw storageError(error); }
    return { path: target };
  }
  /** Resolve one file inside a workspace, following symlinks and refusing escapes. */
  resolve(id, location) {
    const workspace = this.get(id);
    let target;
    try { target = fs.realpathSync(this.location(text(location, t('文件路径')))); }
    catch (error) {
      if (error.code === 'ENOENT') throw new ApiError(404, t('文件不存在或已被移动'));
      throw storageError(error);
    }
    if (!within(workspace.path, target)) throw new ApiError(403, t('文件位于当前工作区之外，请添加对应工作区'));
    return target;
  }
  /** Metadata for the viewer. Text content rides along; binary kinds use /v1/raw. */
  file(id, location) {
    const target = this.resolve(id, location);
    const stat = fs.statSync(target);
    if (!stat.isFile()) throw new ApiError(400, t('请选择普通文件'));
    let writable = true;
    try { fs.accessSync(target, fs.constants.W_OK); } catch { writable = false; }
    const ext = extensionOf(target);
    const base = { path: target, name: path.basename(target), size: stat.size,
      mtime: Math.floor(stat.mtimeMs), ext, mime: mimeOf(ext), writable };
    const kind = kindOf(target, readHead(target, 4096));
    if (kind) return { ...base, kind, language: '', encoding: '', lines: 0, text: '' };
    const limit = this.options.maxTextBytes ?? 2 * 1024 * 1024;
    if (stat.size > limit) return { ...base, kind: 'binary', language: '', text: '',
      note: t('文件超过 ') + Math.round(limit / 1024 / 102.4) / 10 + t(' MB，未在应用内加载') };
    const decoded = decodeText(fs.readFileSync(target));
    if (!decoded) return { ...base, kind: 'binary', language: '', text: '' };
    return { ...base, kind: 'text', encoding: decoded.encoding, language: languageOf(target, ext),
      mime: base.mime === 'application/octet-stream' ? 'text/plain; charset=utf-8' : base.mime,
      lines: decoded.text ? decoded.text.split('\n').length : 0, text: decoded.text };
  }
  /** One file's bytes, streamed by the server. Refuses anything oversized. */
  raw(id, location) {
    const target = this.resolve(id, location);
    const stat = fs.statSync(target);
    if (!stat.isFile()) throw new ApiError(400, t('请选择普通文件'));
    const limit = this.options.maxRawBytes ?? 64 * 1024 * 1024;
    if (stat.size > limit) throw new ApiError(413, t('文件超过 ') + Math.round(limit / 1024 / 1024) + t(' MB，暂不支持在应用内打开'));
    return { path: target, size: stat.size, mime: mimeOf(extensionOf(target)) };
  }
  /**
   * Extract a zip into a sibling folder next to the archive. Every entry name is
   * confined to the destination (zip-slip) and the total written size is capped.
   */
  extract(id, location) {
    const source = this.resolve(id, location);
    const stat = fs.statSync(source);
    if (!stat.isFile()) throw new ApiError(400, t('请选择普通文件'));
    const limit = this.options.maxArchiveBytes ?? 64 * 1024 * 1024;
    if (stat.size > limit) throw new ApiError(413, t('压缩包超过 ') + Math.round(limit / 1024 / 1024) + t(' MB，暂不支持解压'));
    const archive = fs.readFileSync(source);
    const entries = zipDirectory(archive);
    const base = path.basename(source).replace(/\.[^.]+$/, '') || 'archive';
    const parent = path.dirname(source);
    let destination = path.join(parent, base);
    for (let suffix = 2; fs.existsSync(destination); suffix++) destination = path.join(parent, base + '-' + suffix);
    const ceiling = this.options.maxExtractBytes ?? 256 * 1024 * 1024;
    fs.mkdirSync(destination, { recursive: true, mode: 0o700 });
    let written = 0, files = 0;
    try {
      for (const entry of entries) {
        const clean = entry.name.replace(/\\/g, '/');
        if (!clean || clean.endsWith('/')) continue;
        if (clean.startsWith('/') || clean.split('/').includes('..')) throw new ApiError(400, t('压缩包包含不安全的路径'));
        const target = path.join(destination, clean);
        if (!within(destination, target)) throw new ApiError(400, t('压缩包包含不安全的路径'));
        const data = zipRead(archive, entry);
        written += data.length;
        if (written > ceiling) throw new ApiError(413, t('解压后内容过大'));
        fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
        fs.writeFileSync(target, data, { mode: 0o600 });
        files++;
      }
    } catch (error) {
      // A half-extracted folder is worse than none: clean up what we just made.
      try { fs.rmSync(destination, { recursive: true, force: true }); } catch { /* Already gone. */ }
      throw error;
    }
    return { path: destination, entries: files, bytes: written };
  }
  /**
   * Save an edited text file. Writes a sibling temporary file and renames it, so
   * a killed process can never leave a half-written source file behind, and the
   * original mode is preserved because a rename would otherwise reset it to 600.
   * ifMtime fences the classic clobber: the agent may have rewritten the file in
   * the terminal while it sat open in the editor.
   */
  save(id, location, body = {}) {
    const target = this.resolve(id, location);
    const stat = fs.statSync(target);
    if (!stat.isFile()) throw new ApiError(400, t('请选择普通文件'));
    if (typeof body.content !== 'string') throw new ApiError(400, t('缺少文件内容'));
    const limit = this.options.maxTextBytes ?? 2 * 1024 * 1024;
    if (Buffer.byteLength(body.content) > limit) throw new ApiError(413, t('内容过大，未保存'));
    if (Number.isFinite(body.ifMtime) && body.ifMtime > 0 && Math.floor(stat.mtimeMs) !== body.ifMtime) {
      throw new ApiError(409, t('文件已被外部修改，请重新打开后再保存'));
    }
    const mode = stat.mode & 0o777;
    const temporary = path.join(path.dirname(target), '.' + path.basename(target) + '.' + crypto.randomUUID() + '.tmp');
    try {
      fs.writeFileSync(temporary, body.content, { mode });
      fs.chmodSync(temporary, mode);
      fs.renameSync(temporary, target);
    } finally { fs.rmSync(temporary, { force: true }); }
    const after = fs.statSync(target);
    return { path: target, size: after.size, mtime: Math.floor(after.mtimeMs) };
  }
}
