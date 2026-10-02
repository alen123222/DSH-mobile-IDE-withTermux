import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { ApiError, text, writeJson, readJson, executable, killProcessTree } from './util.mjs';
import { connectionSettings, connectionSignature, writeProviderPatch } from './providers.mjs';

// Matches DSH 0.2.0-rc.2's SDK protocol. Every process has one immutable cwd.
// The SDK has no approval-response or cancel method; execution consent is
// explicit before launch, and Stop terminates this session's owned process.
export class DshSessions {
  constructor(stateDir, options = {}) {
    this.stateDir = stateDir;
    this.options = options;
    this.sessions = new Map();
    this.saves = new Map();
    const dir = path.join(stateDir, 'chats');
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    for (const name of fs.readdirSync(dir).filter(n => n.endsWith('.json'))) {
      const data = readJson(path.join(dir, name));
      if (!data?.id) continue;
      this.sessions.set(data.id, { ...data, status: 'archived', starred: data.starred === true,
        process: null, pending: new Map(), listeners: new Set() });
    }
  }
  dshBin() {
    return this.options.dshBin || (fs.existsSync(path.join(this.stateDir, 'runtime', '.pocket-ready'))
      ? executable(path.join(this.stateDir, 'runtime', 'bin', 'dsh-pocket')) : null) || executable('dsh');
  }
  create(workspace) {
    const item = { id: `pocket-${crypto.randomUUID()}`, workspaceId: workspace.id, cwd: workspace.path,
      title: '新会话', messages: [], events: [], status: 'ready', revision: 0, createdAt: Date.now(), starred: false,
      pending: new Map(), listeners: new Set(), process: null };
    this.sessions.set(item.id, item);
    this.save(item);
    return this.snapshot(item);
  }
  remove(id) {
    const item = this.sessions.get(id);
    if (!item) throw new ApiError(404, '会话不存在');
    if (item.status === 'running') throw new ApiError(409, '请先停止当前任务，再删除此对话');
    if (item.process) killProcessTree(item.process);
    this.flush(item);
    for (const listener of item.listeners) { try { listener(this.snapshot({ ...item, status: 'deleted' })); } catch { /* Dropped client. */ } }
    item.listeners.clear();
    this.sessions.delete(id);
    try { fs.rmSync(path.join(this.stateDir, 'chats', `${id}.json`), { force: true }); }
    catch { /* Private data may already be gone. */ }
    try { fs.rmSync(path.join(this.stateDir, 'provider-patches', `${id}.json`), { force: true }); }
    catch { /* No provider patch was written. */ }
    return { ok: true, id };
  }
  star(id, value) {
    const item = this.get(id);
    item.starred = value === true;
    this.touch(item);
    this.flush(item);
    return this.snapshot(item);
  }
  get(id) {
    const item = this.sessions.get(id);
    if (!item) throw new ApiError(404, '会话不存在');
    return item;
  }
  // Incremental read: an unchanged session costs one small JSON object instead of
  // the full message and event history on every 900 ms poll.
  delta(id, since) {
    const item = this.get(id);
    const value = this.snapshot(item);
    if (Number.isSafeInteger(since) && since === item.revision) return { id, revision: item.revision, unchanged: true };
    return value;
  }
  snapshot(item) {
    const { id, workspaceId, cwd, title, messages, events, status, revision, createdAt, error, starred } = item;
    return { id, workspaceId, cwd, title, messages, events, status, revision, createdAt, error, starred: starred === true };
  }
  list(workspaceId) {
    return [...this.sessions.values()].filter(s => s.workspaceId === workspaceId)
      .sort((a, b) => Number(Boolean(b.starred)) - Number(Boolean(a.starred)) || b.createdAt - a.createdAt)
      .map(s => ({ ...this.snapshot(s), messages: undefined, events: undefined }));
  }
  save(item) { writeJson(path.join(this.stateDir, 'chats', `${item.id}.json`), this.snapshot(item)); }
  // Every assistant message and tool event used to rewrite the whole chat file.
  // Coalesce bursts into one trailing write so a long run stays O(1) per flush.
  touch(item) {
    item.revision++;
    const pending = this.saves.get(item.id);
    if (pending) { pending.dirty = true; return; }
    const entry = { dirty: false, timer: null };
    entry.timer = setTimeout(() => {
      entry.timer = null;
      this.saves.delete(item.id);
      try { this.save(item); } catch { /* A failed flush must not kill the session. */ }
      if (entry.dirty) this.touch(item);
    }, this.options.saveDebounceMs ?? 400);
    entry.timer.unref?.();
    this.saves.set(item.id, entry);
    for (const send of item.listeners) { try { send(this.snapshot(item)); } catch { /* Dropped client. */ } }
  }
  flush(item) {
    const pending = this.saves.get(item.id);
    if (!pending) return;
    if (pending.timer) clearTimeout(pending.timer);
    this.saves.delete(item.id);
    try { this.save(item); } catch { /* Ignore: the next touch retries. */ }
  }
  subscribe(item, send) {
    item.listeners.add(send);
    send(this.snapshot(item));
    return () => item.listeners.delete(send);
  }
  fail(item, error) {
    if (item.status === 'stopped' || item.status === 'archived') return;
    item.status = 'error';
    item.error = this.redact(item, error.message || String(error));
    this.touch(item);
  }
  redact(item, value) {
    let result = String(value);
    if (item.apiKey) result = result.replaceAll(item.apiKey, '[redacted]');
    return result.slice(-8192);
  }
  request(item, method, params) {
    const id = ++item.requestId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        item.pending.delete(id);
        reject(new Error(`DSH ${method} 超时。${item.stderr || ''}`));
      }, this.options.timeoutMs ?? 60000);
      item.pending.set(id, { resolve, reject, timer });
      item.process.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }
  async start(item, settings) {
    settings = connectionSettings(settings);
    const binary = this.dshBin();
    if (!binary) throw new ApiError(409, '尚未安装 DSH，请先在环境页安装引擎');
    if (process.platform === 'win32' && !this.options.command) throw new ApiError(501, '此启动器面向 Termux；电脑端仅运行协议测试');
    item.apiKey = settings.apiKey || '';
    item.stderr = '';
    item.requestId = 0;
    item.notificationBuffer = [];
    item.receipt = null;
    item.consumed = false;
    const home = path.join(this.stateDir, 'dsh-home');
    fs.mkdirSync(home, { recursive: true, mode: 0o700 });
    const env = { ...process.env, DSH_HOME: home };
    env.POCKET_API_KEY = item.apiKey;
    const [command, ...args] = this.options.command || [binary, '--profile', 'sdk-minimal'];
    args.push('--patch', writeProviderPatch(this.stateDir, item.id, settings));
    item.connectionSignature = connectionSignature(settings);
    item.process = spawn(command, args, { cwd: item.cwd, env, detached: process.platform !== 'win32', stdio: ['pipe', 'pipe', 'pipe'] });
    item.process.stdin.on('error', error => this.fail(item, error));
    item.process.stderr.on('data', chunk => { item.stderr = this.redact(item, item.stderr + chunk.toString()); });
    item.process.on('error', error => this.fail(item, error));
    item.process.on('exit', (code, signal) => {
      for (const request of item.pending.values()) {
        clearTimeout(request.timer);
        request.reject(new Error(`DSH 已退出 (${signal || code})。${item.stderr}`));
      }
      item.pending.clear();
      if (!['stopped', 'archived', 'error'].includes(item.status)) this.fail(item, new Error(`DSH 已退出 (${signal || code})。${item.stderr}`));
      item.apiKey = '';
    });
    let bytes = 0;
    const lines = createInterface({ input: item.process.stdout, crlfDelay: Infinity });
    item.process.stdout.on('data', chunk => {
      // readline otherwise retains unbounded input if a broken runtime omits newlines.
      bytes += chunk.length;
      if (bytes > 8 * 1024 * 1024) { this.fail(item, new Error('DSH 协议帧超出限制')); killProcessTree(item.process); }
      if (chunk.includes(10)) bytes = 0;
    });
    lines.on('line', line => {
      try {
        const frame = JSON.parse(line);
        if (frame.jsonrpc !== '2.0') throw new Error('DSH 返回了未知协议');
        if (frame.id !== undefined) {
          const pending = item.pending.get(frame.id);
          if (!pending) return;
          clearTimeout(pending.timer); item.pending.delete(frame.id);
          if (frame.error) pending.reject(new Error(frame.error.message || JSON.stringify(frame.error)));
          else pending.resolve(frame.result);
        } else this.notification(item, frame);
      } catch (error) { this.fail(item, new Error(`DSH 协议解析失败：${error.message}`)); killProcessTree(item.process); }
    });
    const response = await this.request(item, 'initialize', { cwd: item.cwd,
      provider: settings.provider, model: text(settings.model, '模型名称', 200), maxTokens: settings.maxTokens ?? 8192 });
    if (response?.serverInfo?.name !== 'deepseek-harness-sdk-runtime') throw new Error('DSH SDK 版本不兼容');
  }
  notification(item, frame) {
    const params = frame.params;
    if (!params || params.sessionId !== item.id) return; // Child output must not replace the root answer.
    if (item.status !== 'running') return;
    if (!item.receipt) {
      if (item.notificationBuffer.length >= 10000) throw new Error('DSH 回执前事件过多');
      item.notificationBuffer.push(frame);
      return;
    }
    if (frame.method === 'session.event') {
      const event = params.event;
      if (!event || typeof event.type !== 'string') throw new Error('无效的 DSH 事件');
      if (event.type === 'agent/inbox/spliced' && event.data?.inserted?.some(m => m.id === item.receipt)) item.consumed = true;
      if (!item.consumed) return;
      if (event.type === 'assistant/message') {
        const content = event.data?.message?.content;
        if (!Array.isArray(content)) throw new Error('无效的助手消息');
        const value = content.filter(c => c.type === 'text').map(c => c.text).join('');
        if (value) {
          item.messages.push({ id: crypto.randomUUID(), role: 'assistant', text: value, time: Date.now() });
          // messages had no ceiling at all; a long run grew the snapshot (and every
          // flush) without bound. Keep the tail, which is what the user reads.
          const limit = this.options.maxMessages ?? 500;
          if (item.messages.length > limit) item.messages.splice(0, item.messages.length - limit);
        }
      }
      if (['tool/call', 'tool/result', 'turn/end', 'assistant/attempt'].includes(event.type)) {
        item.events.push({ type: event.type, data: event.data, time: Date.now() });
        if (item.events.length > (this.options.maxEvents ?? 300)) item.events.shift();
        if (event.type === 'turn/end' && event.data?.reason?.kind === 'error') item.error = this.redact(item, JSON.stringify(event.data.reason));
      }
      if (event.type === 'assistant/message' || event.type.startsWith('tool/') || event.type === 'turn/end') this.touch(item);
    }
    if (frame.method === 'session.status' && params.status === 'idle' && item.consumed) {
      item.status = item.error ? 'error' : 'ready';
      this.touch(item);
    }
  }
  async prompt(id, body) {
    const item = this.get(id);
    if (body.allowExecution !== true) throw new ApiError(403, '请先允许此会话执行命令和修改文件');
    if (item.status === 'stopped') this.resume(item.id);
    if (item.status === 'archived') throw new ApiError(409, '此会话来自上次运行，请在侧栏新建对话继续');
    if (item.status !== 'ready') throw new ApiError(409, item.status === 'running' ? '任务仍在运行' : '此会话当前不可用');
    text(body.prompt, '消息', 100000);
    text(body.model, '模型名称', 200);
    const settings = connectionSettings(body);
    if (!settings.apiKey && !this.options.command) throw new ApiError(400, '请在环境页保存 API Key');
    if (item.process && item.connectionSignature !== connectionSignature(settings)) throw new ApiError(409, 'API 连接或模型已更改，请新建会话使设置生效');
    if (!this.dshBin()) throw new ApiError(409, '尚未安装 DSH，请先在环境页安装引擎');
    item.status = 'running';
    item.error = undefined;
    item.receipt = null;
    item.consumed = false;
    item.notificationBuffer = [];
    item.messages.push({ id: crypto.randomUUID(), role: 'user', text: body.prompt, time: Date.now() });
    if (item.title === '新会话') item.title = body.prompt.slice(0, 40);
    this.touch(item);
    this.flush(item);
    // Return a durable enqueue acknowledgement immediately; UI observes the snapshot revision.
    void (async () => {
      try {
        if (!item.process) await this.start(item, body);
        if (item.status !== 'running') return;
        const response = await this.request(item, 'session/prompt', { sessionId: item.id, contentBlocks: [{ type: 'text', text: body.prompt }] });
        item.receipt = text(response?.messageId, 'DSH 消息回执');
        const buffered = item.notificationBuffer.splice(0);
        for (const frame of buffered) this.notification(item, frame);
      } catch (error) {
        this.fail(item, error);
        if (item.process) killProcessTree(item.process);
      }
    })();
    return this.snapshot(item);
  }
  stop(id) {
    const item = this.get(id);
    item.status = 'stopped'; this.touch(item);
    if (item.process && item.process.exitCode === null) {
      killProcessTree(item.process);
      const timer = setTimeout(() => { if (item.process.exitCode === null) killProcessTree(item.process, 'SIGKILL'); }, 2500);
      timer.unref();
    }
    return this.snapshot(item);
  }
  // A stopped session used to be permanently unusable: prompt() demanded 'ready'
  // and start() refused an existing process. Drop the dead process so the next
  // prompt transparently spawns a fresh engine with the same history and cwd.
  resume(id) {
    const item = this.get(id);
    if (item.status === 'running') throw new ApiError(409, '任务仍在运行');
    if (item.process) {
      if (item.process.exitCode === null) killProcessTree(item.process);
      item.process = null;
    }
    item.status = 'ready';
    item.error = undefined;
    item.receipt = null;
    item.consumed = false;
    item.notificationBuffer = [];
    this.touch(item);
    this.flush(item);
    return this.snapshot(item);
  }
  closeAll() {
    for (const item of this.sessions.values()) { if (item.process) this.stop(item.id); this.flush(item); item.listeners.clear(); }
    for (const entry of this.saves.values()) if (entry.timer) clearTimeout(entry.timer);
    this.saves.clear();
  }
}
