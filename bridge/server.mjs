import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { ApiError, safeEqual, writeJson, readJson, executable } from './util.mjs';
import { Workspaces } from './workspaces.mjs';
import { Terminals } from './terminals.mjs';
import { DshSessions } from './dsh.mjs';
import { inLanguage, languageOf, t } from './i18n.mjs';
import { connectionSettings, listModels } from './providers.mjs';

// Server-Sent Events. The DSH session events already arrive live in dsh.mjs;
// this only gives the phone a push channel instead of a 900 ms poll. The
// connection is a plain HTTP request, so it must be exempt from requestTimeout.
function stream(req, res, chats, id) {
  const item = chats.get(id);
  res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store, no-transform',
    Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
  res.write(': pocket stream open\n\n');
  let unsubscribe = () => {};
  const keepAlive = setInterval(() => { try { res.write(': ping\n\n'); } catch { done(); } }, 20000);
  keepAlive.unref?.();
  const done = () => { clearInterval(keepAlive); unsubscribe(); unsubscribe = () => {}; };
  const send = snapshot => {
    if (snapshot === null) { done(); return; } // Service shutting down.
    try {
      if (snapshot.status === 'deleted') { res.write('event: deleted\ndata: {}\n\n'); done(); res.end(); }
      else res.write(`data: ${JSON.stringify(snapshot)}\n\n`);
    } catch { done(); }
  };
  // subscribe() delivers the current snapshot synchronously, so `done` and the
  // keep-alive timer must already exist by this point.
  unsubscribe = chats.subscribe(item, send);
  req.on('close', done); req.on('error', done); res.on('error', done);
}

async function bodyOf(request) {
  if (!request.headers['content-type']?.startsWith('application/json')) throw new ApiError(415, t('请求必须为 JSON'));
  let size = 0; const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    // File saves are the reason this is not 512 KB: an edited source file has to
    // fit in the request body, and the endpoint is loopback-only and token-gated.
    if (size > 8 * 1024 * 1024) throw new ApiError(413, t('请求内容过大'));
    chunks.push(chunk);
  }
  try {
    const result = JSON.parse(Buffer.concat(chunks).toString());
    if (!result || Array.isArray(result) || typeof result !== 'object') throw new Error();
    return result;
  } catch { throw new ApiError(400, t('无效的 JSON 请求')); }
}

/**
 * Store one attachment the user picked inside the workspace it belongs to, so the
 * model can open it with the file tools it already has. Images normally travel as
 * content blocks instead; this is for everything else.
 */
function attachFile(body) {
  const root = path.resolve(String(body.workspacePath || ''));
  if (!root || root === path.parse(root).root) throw new ApiError(400, t('请先选择工作区'));
  const name = String(body.name || 'file').replace(/[^\w.-]+/g, '_').slice(-80) || 'file';
  const data = Buffer.from(String(body.data || ''), 'base64');
  if (data.length === 0) throw new ApiError(400, t('附件为空'));
  if (data.length > 6 * 1024 * 1024) throw new ApiError(400, t('附件过大，请压缩后再发送'));
  const dir = path.join(root, '.dsh-attachments');
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  let target = path.join(dir, name);
  for (let n = 1; fs.existsSync(target) && n < 100; n += 1) target = path.join(dir, n + '-' + name);
  fs.writeFileSync(target, data, { mode: 0o600 });
  return { path: target, bytes: data.length };
}

export function createBridge({ stateDir, token, dshOptions = {} }) {
  if (typeof token !== 'string' || token.length < 32) throw new Error('Bridge token must contain at least 32 characters');
  const workspaces = new Workspaces(stateDir, dshOptions), terminals = new Terminals(), chats = new DshSessions(stateDir, dshOptions);
  const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    const send = (status, data) => { res.writeHead(status); res.end(JSON.stringify(data)); };
    // Resolve every message in the caller's language for this request only.
    await inLanguage(languageOf(req), async () => {
    try {
      if (req.headers.origin) throw new ApiError(403, t('浏览器来源不可调用本地执行服务'));
      if (!safeEqual(req.headers.authorization || '', `Bearer ${token}`)) throw new ApiError(401, t('连接密钥不匹配，请重新连接 Termux'));
      const url = new URL(req.url, 'http://127.0.0.1');
      const parts = url.pathname.split('/').filter(Boolean);
      const method = req.method;
      if (parts[0] !== 'v1') throw new ApiError(404, t('接口不存在'));
      const body = method === 'POST' ? await bodyOf(req) : {};
      let result;
      if (method === 'POST' && parts[1] === 'providers' && parts[2] === 'models') result = await listModels(connectionSettings(body));
      else if (method === 'GET' && parts[1] === 'health') result = { version: '0.6.2', platform: process.platform, arch: process.arch,
        home: os.homedir(), prefix: process.env.PREFIX || '', node: process.version, python: !!(executable('python3') || executable('python')),
        dsh: chats.dshBin(), dshProfile: 'sdk-minimal', pid: process.pid };
      else if (method === 'POST' && parts[1] === 'attach') result = attachFile(body);
      else if (method === 'GET' && parts[1] === 'shortcuts') result = { items: await workspaces.shortcuts((url.searchParams.get('external') || '').split('\n')) };
      else if (method === 'POST' && parts[1] === 'workspace-check') result = await workspaces.validate(body.path);
      else if (method === 'GET' && parts[1] === 'browse') result = await workspaces.browse(url.searchParams.get('path') || undefined, url.searchParams.get('dirs') === 'true');
      else if (method === 'POST' && parts[1] === 'directories') result = workspaces.create(body.parent, body.name);
      else if (method === 'GET' && parts[1] === 'workspaces') result = { items: workspaces.all() };
      else if (method === 'POST' && parts[1] === 'workspaces' && !parts[2]) result = await workspaces.add(body.path);
      else if (method === 'DELETE' && parts[1] === 'workspaces' && parts[2]) { workspaces.remove(parts[2]); result = { ok: true }; }
      else if (method === 'POST' && parts[1] === 'workspaces' && parts[3] === 'star') result = workspaces.star(parts[2], body.starred);
      else if (method === 'GET' && parts[1] === 'file') result = workspaces.file(url.searchParams.get('workspaceId'), url.searchParams.get('path'));
      else if (method === 'POST' && parts[1] === 'file') result = workspaces.save(
        body.workspaceId || url.searchParams.get('workspaceId'), body.path, body);
      else if (method === 'GET' && parts[1] === 'raw') {
        // Stream the bytes. Base64 inside JSON would inflate a photo by a third
        // and hold it in memory twice over.
        const info = workspaces.raw(url.searchParams.get('workspaceId'), url.searchParams.get('path'));
        res.writeHead(200, { 'Content-Type': info.mime, 'Content-Length': String(info.size), 'Cache-Control': 'no-store' });
        const source = fs.createReadStream(info.path);
        source.on('error', () => res.destroy());
        res.on('close', () => source.destroy());
        source.pipe(res);
        return;
      }
      else if (method === 'POST' && parts[1] === 'extract') result = workspaces.extract(
        body.workspaceId || url.searchParams.get('workspaceId'), body.path);
      else if (method === 'POST' && parts[1] === 'terminals' && !parts[2]) result = terminals.open(workspaces.get(body.workspaceId), body.cwd);
      else if (method === 'GET' && parts[1] === 'terminals') {
        const after = Number(url.searchParams.get('after') || 0);
        if (!Number.isSafeInteger(after) || after < 0) throw new ApiError(400, t('无效的终端游标'));
        result = terminals.poll(parts[2], after);
      } else if (method === 'POST' && parts[1] === 'terminals' && parts[2]) { terminals.write(parts[2], body); result = { ok: true }; }
      else if (method === 'DELETE' && parts[1] === 'terminals') { terminals.close(parts[2]); result = { ok: true }; }
      else if (method === 'GET' && parts[1] === 'chats' && !parts[2]) result = { items: chats.list(url.searchParams.get('workspaceId')) };
      else if (method === 'POST' && parts[1] === 'chats' && !parts[2]) result = chats.create(workspaces.get(body.workspaceId));
      else if (method === 'GET' && parts[1] === 'chats' && parts[3] === 'stream') return stream(req, res, chats, parts[2]);
      else if (method === 'GET' && parts[1] === 'chats' && parts[2]) {
        const raw = url.searchParams.get('since');
        result = chats.delta(parts[2], raw === null ? undefined : Number(raw));
      }
      else if (method === 'POST' && parts[1] === 'chats' && parts[3] === 'prompt') result = await chats.prompt(parts[2], body);
      else if (method === 'POST' && parts[1] === 'chats' && parts[3] === 'stop') result = chats.stop(parts[2]);
      else if (method === 'POST' && parts[1] === 'chats' && parts[3] === 'resume') result = chats.resume(parts[2]);
      else if (method === 'POST' && parts[1] === 'chats' && parts[3] === 'star') result = chats.star(parts[2], body.starred);
      else if (method === 'DELETE' && parts[1] === 'chats' && parts[2]) result = chats.remove(parts[2]);
      else throw new ApiError(404, t('接口不存在'));
      send(200, result);
    } catch (error) {
      const status = error.status || ({ ENOENT: 404, EACCES: 403, EPERM: 403, EEXIST: 409 }[error.code]) || 500;
      send(status, { error: error.message || t('操作失败') });
    }
    });
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.setTimeout(0); // Long-lived SSE connections must not be reaped.
  server.on('close', () => { terminals.closeAll(); chats.closeAll(); });
  return { server, workspaces, terminals, chats };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const stateDir = process.env.POCKET_HOME || path.join(os.homedir(), '.local', 'share', 'dsh-pocket');
  fs.mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const configPath = path.join(stateDir, 'connection.json');
  let config = readJson(configPath, null);
  if (!config) { config = { token: crypto.randomBytes(32).toString('hex'), port: 8765 }; writeJson(configPath, config); }

  // Starting a second copy used to die with a bare EADDRINUSE stack trace in
  // Termux whenever the app was relaunched while the old service was still up.
  // If a healthy service already owns the port and accepts our token, adopt it
  // and exit 0; only a genuinely foreign listener is an error.
  const probe = async () => {
    const response = await fetch(`http://127.0.0.1:${config.port}/v1/health`,
      { headers: { Authorization: `Bearer ${config.token}` }, signal: AbortSignal.timeout(1500) });
    return response.ok ? await response.json() : null;
  };
  let existing = null;
  try { existing = await probe(); } catch { /* Nothing listening yet. */ }
  if (existing) {
    console.log(`DSH Pocket bridge already running on 127.0.0.1:${config.port} (pid ${existing.pid}, version ${existing.version}).`);
    process.exit(0);
  }
  let portHeld = false;
  try { await fetch(`http://127.0.0.1:${config.port}/v1/health`, { signal: AbortSignal.timeout(1200) }); portHeld = true; }
  catch { /* Free or refused. */ }

  const bridge = createBridge({ stateDir, token: config.token });
  bridge.server.on('error', error => {
    if (error.code === 'EADDRINUSE') {
      console.error(`DSH Pocket: 127.0.0.1:${config.port} 已被其他进程占用。请在 Termux 执行 pkill -f dsh-pocket，或重启 Termux 后再试。`);
      process.exitCode = 1;
      return;
    }
    console.error(`DSH Pocket: ${error.message}`);
    process.exitCode = 1;
  });
  bridge.server.listen(config.port, '127.0.0.1', () => {
    if (portHeld) console.log('DSH Pocket bridge replaced an unhealthy listener.');
    console.log(`DSH Pocket bridge listening on 127.0.0.1:${config.port}`);
  });
  let stopping = false;
  const stop = () => {
    if (stopping) return; stopping = true;
    bridge.terminals.closeAll(); bridge.chats.closeAll();
    bridge.server.close(); bridge.server.closeAllConnections();
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGTERM', stop); process.on('SIGINT', stop);
}
