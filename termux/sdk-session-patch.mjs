import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

// Exact, version-pinned patch: SDK's cache survives turns, but not processes.
export function sdkSessionPatch(source) {
  const before = '\tasync createSession(sessionId) {\n\t\tconst rec = { handle: await this.ctx.agents.create({';
  const after = `\tasync pocketResumeOrCreate(options) {
\t\tif (this.ctx.get("sessionPersistence") !== undefined) {
\t\t\ttry {
\t\t\t\treturn await this.ctx.agents.resume({ resumeSessionId: options.sessionId, agentOptions: options.agentOptions });
\t\t\t} catch (error) {
\t\t\t\tif (!(error instanceof PocketSessionNotFoundError) || error.sessionId !== options.sessionId) throw error;
\t\t\t}
\t\t}
\t\treturn this.ctx.agents.create(options);
\t}
\tasync createSession(sessionId) {
\t\tconst rec = { handle: await this.pocketResumeOrCreate({`;
  const preamble = 'import { SessionPersistenceNotFoundError as PocketSessionNotFoundError } from "@deepseek-ai/dsh-session-persistence";\n';
  if (source.includes(after) && source.startsWith(preamble)) return source;
  if (source.split(before).length !== 2 || source.includes('pocketResumeOrCreate')) {
    throw new Error('SDK session patch mismatch; requires the pinned DSH 0.2.0-rc.2 runtime');
  }
  return preamble + source.replace(before, after);
}

export function sdkSessionPatchPlan(runtime) {
  const require = createRequire(path.join(path.resolve(runtime), 'package.json'));
  const file = require.resolve('@deepseek-ai/dsh-sdk-jsonrpc-server');
  const manifest = JSON.parse(fs.readFileSync(path.join(path.dirname(file), '../package.json'), 'utf8'));
  if (manifest.version !== '0.2.0-rc.2') throw new Error('SDK session patch requires DSH 0.2.0-rc.2');
  const original = fs.readFileSync(file, 'utf8');
  return { file, original, output: sdkSessionPatch(original) };
}

// Also applied before launch, so already-installed engines receive the fix
// when the app updates its bridge assets, without reinstalling native modules.
export function ensureSdkSessionPatch(runtime) {
  const plan = sdkSessionPatchPlan(runtime);
  if (plan.original === plan.output) return;
  if (!fs.existsSync(plan.file + '.pocket-original')) fs.writeFileSync(plan.file + '.pocket-original', plan.original);
  const temporary = plan.file + '.pocket-update';
  fs.writeFileSync(temporary, plan.output);
  fs.renameSync(temporary, plan.file);
}
