import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

export function attachmentPatch(source) {
  const marker = '// Pocket Android attachment durability boundary v1';
  if (source.startsWith(marker)) return source;
  const changes = [
    ['await ensureDurableDirectory(home, parse(home).root);', 'await ensureDurableDirectory(home, process.platform === "android" ? pocketAttachmentBoundary(home) : parse(home).root);'],
    ['await link(staged.path, target);', 'await (process.platform === "android" ? pocketPublishAttachment(staged.path, target) : link(staged.path, target));'],
    ['await unlink(staged.path);', 'if (process.platform === "android") await removeTemporary(staged.path);\n\t\telse await unlink(staged.path);'],
    ['await link(source, target);', 'if (process.platform === "android") await publishImmutableObject(root, target, await readFile(source), sha256);\n\t\t\telse await link(source, target);'],
  ];
  for (const [before, after] of changes) {
    if (source.split(before).length !== 2) throw new Error(`Attachment patch mismatch: ${before}`);
    source = source.replace(before, after);
  }
  return `${marker}
import { homedir as pocketHome } from 'node:os';
import { resolve as pocketResolve, sep as pocketSep } from 'node:path';
import { publishExclusiveAndroid as pocketPublishAttachment } from '@pocket/android-system';
function pocketAttachmentBoundary(home) {
  const boundary = pocketResolve(pocketHome());
  if (home !== boundary && !home.startsWith(boundary + pocketSep)) throw new Error('Attachment home must stay inside the Termux home');
  return boundary;
}
` + source;
}

export function ensureAttachmentPatch(runtime) {
  const require = createRequire(path.join(path.resolve(runtime), 'package.json'));
  const file = require.resolve('@deepseek-ai/dsh-attachment-local');
  const manifest = JSON.parse(fs.readFileSync(path.join(path.dirname(file), '../package.json'), 'utf8'));
  if (manifest.version !== '0.2.0-rc.2') throw new Error('Attachment patch requires DSH 0.2.0-rc.2');
  const original = fs.readFileSync(file, 'utf8');
  const output = attachmentPatch(original);
  if (original === output) return;
  if (!fs.existsSync(file + '.pocket-original')) fs.writeFileSync(file + '.pocket-original', original);
  fs.writeFileSync(file + '.pocket-update', output);
  fs.renameSync(file + '.pocket-update', file);
}
