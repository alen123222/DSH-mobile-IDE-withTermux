const fields = {
  sessionStats: ['turns', 'steps', 'llmMs', 'toolMs', 'ttftMs', 'ttftSteps', 'decodeMs', 'decodeTokens'],
  tokenUsage: ['uncachedInputTokens', 'outputTokens', 'cacheReadTokens', 'cacheWriteTokens'],
  contextPressure: ['pressureTokens', 'projectedTokens', 'contextWindow'],
  contextBreakdown: ['systemTokens', 'toolsTokens', 'messageTokens'],
};
export function readMetrics(params) {
  if (!Number.isSafeInteger(params?.asOfSeq) || params.asOfSeq < 0) return null;
  const result = { asOfSeq: params.asOfSeq };
  for (const [key, names] of Object.entries(fields)) {
    const source = params.values?.[key];
    if (!source || typeof source !== 'object') continue;
    result[key] = Object.fromEntries(names.filter(name => Number.isFinite(source[name]) && source[name] >= 0).map(name => [name, source[name]]));
  }
  return result;
}
