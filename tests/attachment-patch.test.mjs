import test from 'node:test';
import assert from 'node:assert/strict';
import { attachmentPatch } from '../termux/attachment-patch.mjs';

test('attachment patch bounds Android fsync and preserves immutable aliases', () => {
  const source = ['await ensureDurableDirectory(home, parse(home).root);', 'await link(staged.path, target);', 'await unlink(staged.path);', 'await link(source, target);'].join('\n');
  const patched = attachmentPatch(source);
  assert.match(patched, /home\.startsWith\(boundary \+ pocketSep\)/);
  assert.match(patched, /pocketPublishAttachment\(staged.path, target\)/);
  assert.match(patched, /await removeTemporary\(staged.path\)/);
  assert.match(patched, /publishImmutableObject\(root, target, await readFile\(source\), sha256\)/);
  assert.equal(attachmentPatch(patched), patched);
  assert.throws(() => attachmentPatch(source.replace('await link(source, target);', '')), /mismatch/);
});
