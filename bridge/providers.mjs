import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { ApiError } from './util.mjs';

export function connectionSettings(input) {
  const protocol = input.protocol || (input.provider && input.provider !== 'deepseek-official' ? 'openai-chat' : 'deepseek-messages');
  if (!['openai-chat', 'openai-responses', 'deepseek-messages'].includes(protocol)) throw new ApiError(400, '不支持的 API 协议');
  const fallback = protocol === 'deepseek-messages' ? 'https://api.deepseek.com/anthropic' : 'https://api.openai.com/v1';
  let url;
  try { url = new URL(input.baseUrl?.trim() || fallback); } catch { throw new ApiError(400, 'API 地址无效'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new ApiError(400, 'API 地址须为 HTTP(S)，且不包含账号、查询参数或片段');
  let pathname = url.pathname.replace(/\/+$/, '');
  const suffixes = protocol === 'deepseek-messages' ? ['/v1/messages', '/messages'] : ['/chat/completions', '/responses'];
  const suffix = suffixes.find(s => pathname.endsWith(s));
  if (suffix) pathname = pathname.slice(0, -suffix.length);
  if (!suffix && !pathname && protocol !== 'deepseek-messages' && input.autoVersion !== false) pathname = '/v1';
  url.pathname = pathname || '/';
  return { protocol, provider: protocol === 'deepseek-messages' ? 'deepseek-official' : 'pocket-openai',
    baseUrl: url.href.replace(/\/+$/, ''), model: input.model?.trim() || '', apiKey: input.apiKey || '',
    autoVersion: input.autoVersion !== false, ...modelLimits(input) };
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

export const connectionSignature = settings => crypto.createHash('sha256').update(JSON.stringify(settings)).digest('hex');

export function writeProviderPatch(stateDir, id, settings) {
  const dir = path.join(stateDir, 'provider-patches');
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const model = { id: settings.model, name: settings.model,
    contextWindow: settings.contextWindow ?? 131072, maxTokens: settings.maxTokens ?? 8192 };
  const patch = settings.protocol === 'deepseek-messages'
    ? [{ id: 'llm-deepseek', config: { baseURL: settings.baseUrl, apiKeyEnv: 'POCKET_API_KEY', models: [model], maxTokens: settings.maxTokens ?? 8192 } }]
    : [{ id: 'llm-deepseek', disabled: true }, { insert: [{ id: 'pocket-llm', name: '@deepseek-ai/dsh-llm-pi-ai', config: {
      providers: { 'pocket-openai': { displayName: 'Pocket 自定义 API', apiKeyEnv: 'POCKET_API_KEY',
        api: settings.protocol === 'openai-responses' ? 'openai-responses' : 'openai-completions', baseURL: settings.baseUrl,
        models: [{ ...model, input: ['text'] }], retryPolicy: { mode: 'normal', maxRetries: 1 } } }
    } }] }];
  const file = path.join(dir, `${id}.json`);
  fs.writeFileSync(file, JSON.stringify(patch, null, 2), { mode: 0o600 });
  return file;
}
