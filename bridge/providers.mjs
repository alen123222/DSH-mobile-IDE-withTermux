import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { ApiError } from './util.mjs';
import { t } from './i18n.mjs';

export function connectionSettings(input) {
  const protocol = input.protocol || (input.provider && input.provider !== 'deepseek-official' ? 'openai-chat' : 'deepseek-messages');
  if (!['openai-chat', 'openai-responses', 'deepseek-messages'].includes(protocol)) throw new ApiError(400, t('不支持的 API 协议'));
  const fallback = protocol === 'deepseek-messages' ? 'https://api.deepseek.com/anthropic' : 'https://api.openai.com/v1';
  let url;
  try { url = new URL(input.baseUrl?.trim() || fallback); } catch { throw new ApiError(400, t('API 地址无效')); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new ApiError(400, t('API 地址须为 HTTP(S)，且不包含账号、查询参数或片段'));
  let pathname = url.pathname.replace(/\/+$/, '');
  const suffixes = protocol === 'deepseek-messages' ? ['/v1/messages', '/messages'] : ['/chat/completions', '/responses'];
  const suffix = suffixes.find(s => pathname.endsWith(s));
  if (suffix) pathname = pathname.slice(0, -suffix.length);
  if (!suffix && !pathname && protocol !== 'deepseek-messages' && input.autoVersion !== false) pathname = '/v1';
  url.pathname = pathname || '/';
  return { protocol, provider: protocol === 'deepseek-messages' ? 'deepseek-official' : 'pocket-openai',
    baseUrl: url.href.replace(/\/+$/, ''), model: input.model?.trim() || '', apiKey: input.apiKey || '',
    autoVersion: input.autoVersion !== false, reasoningEffort: reasoningEffort(input.reasoningEffort, protocol), vision: input.vision === true,
    ...(input.phone && typeof input.phone.token === 'string' && /^[a-zA-Z0-9-]{64,100}$/.test(input.phone.token) && Number.isInteger(input.phone.port) && input.phone.port > 1024 && input.phone.port < 65536
      ? { phone: { token: input.phone.token, port: input.phone.port } } : {}), ...modelLimits(input) };
}

function reasoningEffort(value, protocol) {
  if (value === undefined || value === null || value === '') return '';
  const levels = protocol === 'deepseek-messages' ? ['off', 'low', 'high', 'max']
    : ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
  if (!levels.includes(value)) throw new ApiError(400, 'Unsupported reasoning effort for this protocol');
  return value;
}

// The window and output ceiling used to be hardcoded to 131072/8192, which is
// wrong for most non-DeepSeek models. Take them from the preset, clamped to
// values the engine can actually use.
export function modelLimits(input) {
  const clamp = (value, fallback, min, max) => {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed >= min ? Math.min(Math.round(parsed), max) : fallback;
  };
  return { contextWindow: clamp(input.contextWindow, 131072, 4096, 4194304),
    maxTokens: clamp(input.maxTokens, 8192, 256, 131072) };
}

// Process fingerprint. Bumped whenever anything baked into a running engine changes:
// the generated patch, or the environment it is spawned with. A live engine is reused
// across turns, so without a bump a shipped fix keeps failing until the app restarts.
const PATCH_REVISION = 3;
export const connectionSignature = settings => crypto.createHash('sha256')
  .update(JSON.stringify({ patch: PATCH_REVISION, settings })).digest('hex');

export function writeProviderPatch(stateDir, id, settings) {
  const dir = path.join(stateDir, 'provider-patches');
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const model = { id: settings.model, name: settings.model,
    contextWindow: settings.contextWindow ?? 131072, maxTokens: settings.maxTokens ?? 8192 };
  // Custom model IDs have no catalog capabilities. An explicit selection opts
  // into the standard effort vocabulary; the default leaves provider defaults intact.
  const effort = settings.reasoningEffort;
  if (effort && settings.protocol !== 'deepseek-messages') model.reasoningEfforts = {
    off: 'none', minimal: 'minimal', low: 'low', medium: 'medium', high: 'high', xhigh: 'xhigh', max: 'max',
  };
  const patch = settings.protocol === 'deepseek-messages'
    ? [{ id: 'llm-deepseek', config: { baseURL: settings.baseUrl, apiKeyEnv: 'POCKET_API_KEY', models: [model], maxTokens: settings.maxTokens ?? 8192,
      ...(effort ? { reasoningEffort: effort } : {}) } }]
    : [{ id: 'llm-deepseek', disabled: true }, { insert: [{ id: 'pocket-llm', name: '@deepseek-ai/dsh-llm-pi-ai', config: {
      providers: { 'pocket-openai': { displayName: t('Pocket 自定义 API'), apiKeyEnv: 'POCKET_API_KEY',
        api: settings.protocol === 'openai-responses' ? 'openai-responses' : 'openai-completions', baseURL: settings.baseUrl,
        models: [{ ...model, input: ['text', 'image'] }], ...(effort ? { reasoning: effort } : {}), retryPolicy: { mode: 'normal', maxRetries: 1 } } }
    } }] }];
  // The attachment store is needed whenever a prompt can carry a picture, which is
  // independent of phone control; without it the engine refuses an image block with
  // "SDK image prompt requires an attachment store".
  // Keep attachment storage beside the engine's sessions. Android ancestor fsync
  // and immutable publication are handled by the checked runtime attachment patch.
  patch.push({ insert: [{
    id: 'pocket-attachments', name: '@deepseek-ai/dsh-attachment-local',
    config: { dshHome: path.join(stateDir, 'dsh-home') },
  }] });
  if (settings.phone) patch.push({ insert: [
    { id: 'pocket-phone', name: fileURLToPath(new URL('./phone-plugin.mjs', import.meta.url)) },
  ] });
  patch.push({ insert: [
    { id: 'pocket-token-meter', name: '@deepseek-ai/dsh-token-meter' },
    { id: 'pocket-session-stats', name: '@deepseek-ai/dsh-session-stats' },
    { id: 'pocket-metrics', name: fileURLToPath(new URL('./metrics-plugin.mjs', import.meta.url)) },
  ] });
  const file = path.join(dir, `${id}.json`);
  fs.writeFileSync(file, JSON.stringify(patch, null, 2), { mode: 0o600 });
  return file;
}

/**
 * Model discovery. Endpoints are rooted differently: some at the host, some at
 * the API version, and some are pointed straight at the chat path, so try the
 * plausible roots in order and take the first that answers with a list. The key
 * is used for this request only; it is never stored and never echoed back.
 */
export function modelRoots(baseUrl) {
  const raw = String(baseUrl || '').trim().replace(/\/+$/, '');
  if (!raw) return [];
  let url;
  try { url = new URL(raw); } catch { return []; }
  const path = url.pathname.replace(/\/(chat\/completions|completions|responses|messages)$/, '');
  const roots = new Set([path]);
  if (!/\/v\d+$/.test(path)) roots.add(path.replace(/\/$/, '') + '/v1');
  return [...roots].map(prefix => url.origin + (prefix.endsWith('/') ? prefix.slice(0, -1) : prefix) + '/models');
}

function readModelList(payload) {
  const raw = Array.isArray(payload) ? payload : (payload?.data ?? payload?.models ?? payload?.items);
  if (!Array.isArray(raw)) return [];
  const seen = new Set();
  const items = [];
  for (const entry of raw) {
    const object = entry && typeof entry === 'object' ? entry : null;
    const id = String(object ? object.id ?? object.name ?? '' : entry ?? '').trim();
    if (!id || id.length > 200 || seen.has(id)) continue;
    seen.add(id);
    const label = String(object ? object.display_name ?? object.displayName ?? '' : '').trim();
    items.push(label && label !== id ? { id, name: label } : { id });
  }
  return items.slice(0, 500);
}

export async function listModels(settings) {
  const roots = modelRoots(settings.baseUrl);
  if (roots.length === 0) throw new ApiError(400, t('请先填写 API 地址'));
  let last = '';
  for (const url of roots) {
    let response;
    try {
      response = await fetch(url, { headers: { Accept: 'application/json',
        ...(settings.apiKey ? { Authorization: 'Bearer ' + settings.apiKey } : {}) },
        signal: AbortSignal.timeout(20000) });
    } catch (error) {
      last = t('无法连接') + ' ' + url + '：' + (error?.message || error);
      continue;
    }
    if (!response.ok) {
      last = url + ' → HTTP ' + response.status +
        (response.status === 401 || response.status === 403 ? '（' + t('API Key 无效或无权限') + '）' : '');
      continue;
    }
    let payload;
    try { payload = await response.json(); } catch { last = url + ' ' + t('返回的不是 JSON'); continue; }
    const items = readModelList(payload);
    if (items.length > 0) return { items, endpoint: url };
    last = url + ' ' + t('没有返回模型列表');
  }
  throw new ApiError(502, last || t('无法获取模型列表'));
}
