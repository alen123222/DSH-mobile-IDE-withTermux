import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { ApiError, executable, killProcessTree, within } from './util.mjs';
import { t } from './i18n.mjs';

export class Terminals {
  constructor() { this.items = new Map(); }
  open(workspace, cwd) {
    const running = [...this.items.values()].find(t => t.workspaceId === workspace.id && t.running);
    if (running) return this.info(running);
    if (process.platform === 'win32') throw new ApiError(501, t('真实终端需要 Termux 或 POSIX 环境'));
    // "Open in terminal" from the file viewer starts the shell in the file's own
    // folder; the path still has to be inside the workspace.
    let directory = workspace.path;
    if (cwd) {
      try { directory = fs.realpathSync(String(cwd)); } catch { throw new ApiError(404, t('目录不存在')); }
      if (!within(workspace.path, directory)) throw new ApiError(403, t('目录位于当前工作区之外'));
      if (!fs.statSync(directory).isDirectory()) throw new ApiError(400, t('请选择文件夹作为终端目录'));
    }
    const python = executable('python3') || executable('python');
    const shell = executable('bash');
    if (!python || !shell) throw new ApiError(409, t('请先在 Termux 安装 python 和 bash'));
    if ([...this.items.values()].filter(t => t.running).length >= 8) throw new ApiError(409, t('最多同时打开 8 个终端'));
    const terminal = { id: crypto.randomUUID(), workspaceId: workspace.id, cwd: directory, running: true, chunks: [], bytes: 0, cursor: 0, exitCode: null };
    terminal.child = spawn(python, ['-u', fileURLToPath(new URL('./pty_host.py', import.meta.url)), directory, shell], {
      cwd: directory, stdio: ['pipe', 'pipe', 'pipe'], detached: true,
    });
    const append = data => {
      const chunk = { cursor: ++terminal.cursor, data };
      terminal.chunks.push(chunk);
      terminal.bytes += data.length;
      while (terminal.bytes > 2 * 1024 * 1024 && terminal.chunks.length > 1) terminal.bytes -= terminal.chunks.shift().data.length;
    };
    terminal.child.stdin.on('error', () => { /* Exit handler owns the terminal state. */ });
    createInterface({ input: terminal.child.stdout }).on('line', line => {
      try {
        const item = JSON.parse(line);
        if (item.type === 'data') append(item.data);
        if (item.type === 'exit') { terminal.running = false; terminal.exitCode = item.code; }
      } catch { append(Buffer.from(t('\r\n终端协议错误\r\n')).toString('base64')); }
    });
    terminal.child.stderr.on('data', data => append(Buffer.from(data).toString('base64')));
    terminal.child.on('error', error => { append(Buffer.from(error.message).toString('base64')); terminal.running = false; });
    terminal.child.on('exit', code => { terminal.running = false; terminal.exitCode ??= code; });
    this.items.set(terminal.id, terminal);
    return this.info(terminal);
  }
  get(id) {
    const terminal = this.items.get(id);
    if (!terminal) throw new ApiError(404, t('终端已失效，请重新打开'));
    return terminal;
  }
  info(t) { return { id: t.id, workspaceId: t.workspaceId, cwd: t.cwd, running: t.running, exitCode: t.exitCode }; }
  poll(id, after) {
    const terminal = this.get(id);
    return { ...this.info(terminal), chunks: terminal.chunks.filter(c => c.cursor > after), cursor: terminal.cursor,
      gap: terminal.chunks.length > 0 && after < terminal.chunks[0].cursor - 1 };
  }
  write(id, input) {
    const terminal = this.get(id);
    if (!terminal.running) throw new ApiError(409, t('终端已退出'));
    if (input.type === 'input') {
      if (typeof input.data !== 'string' || input.data.length > 32768 || !/^[A-Za-z0-9+/]*={0,2}$/.test(input.data)) throw new ApiError(400, t('无效的终端输入'));
    } else if (input.type === 'resize') {
      if (![input.rows, input.cols].every(v => Number.isInteger(v) && v >= 2 && v <= 500)) throw new ApiError(400, t('无效的终端尺寸'));
    } else throw new ApiError(400, t('未知的终端操作'));
    terminal.child.stdin.write(JSON.stringify(input) + '\n');
  }
  close(id) {
    const terminal = this.get(id);
    if (terminal.running) killProcessTree(terminal.child);
    terminal.running = false;
  }
  closeAll() { for (const id of this.items.keys()) this.close(id); }
}
