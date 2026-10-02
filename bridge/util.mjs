import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export function text(value, name, max = 8192) {
  if (typeof value !== 'string' || !value.trim() || value.length > max || value.includes('\0')) {
    throw new ApiError(400, `${name} 必须是有效的非空字符串`);
  }
  return value;
}
export function directory(value) {
  text(value, '目录');
  if (!path.isAbsolute(value)) throw new ApiError(400, '请选择绝对路径');
  const result = fs.realpathSync(value);
  if (!fs.statSync(result).isDirectory()) throw new ApiError(400, '该路径不是文件夹');
  fs.accessSync(result, fs.constants.R_OK | fs.constants.X_OK);
  return result;
}
export function writeJson(filename, value) {
  fs.mkdirSync(path.dirname(filename), { recursive: true, mode: 0o700 });
  const temporary = `${filename}.${crypto.randomUUID()}.tmp`;
  try {
    fs.writeFileSync(temporary, JSON.stringify(value), { mode: 0o600 });
    fs.renameSync(temporary, filename);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}
export function readJson(filename, fallback) {
  let raw;
  try { raw = fs.readFileSync(filename, 'utf8'); }
  catch (error) { if (error.code === 'ENOENT') return fallback; throw error; }
  try { return JSON.parse(raw); }
  catch {
    // A truncated write (battery pull, OOM kill) must never make the phone
    // unrecoverable. Keep the damaged bytes next to the file, then continue.
    try { fs.renameSync(filename, `${filename}.corrupt-${Date.now()}`); } catch { /* Already gone or read-only. */ }
    return fallback;
  }
}
export function executable(name) {
  if (path.isAbsolute(name)) return fs.existsSync(name) ? name : null;
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    for (const suffix of process.platform === 'win32' ? ['', '.cmd', '.exe'] : ['']) {
      const candidate = path.join(dir, name + suffix);
      try { fs.accessSync(candidate, fs.constants.X_OK); return candidate; }
      catch { /* Search the next PATH entry. */ }
    }
  }
  return null;
}
export function within(root, target) {
  const relative = path.relative(root, target);
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}
export function safeEqual(a, b) {
  const left = Buffer.from(a), right = Buffer.from(b);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}
export function killProcessTree(child, signal = 'SIGTERM') {
  if (!child?.pid) return;
  try {
    if (process.platform !== 'win32') process.kill(-child.pid, signal);
    else child.kill(signal);
  } catch (error) { if (error.code !== 'ESRCH') throw error; }
}
