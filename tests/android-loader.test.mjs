import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const file = new URL('../termux/android-loader.cjs', import.meta.url);

test('Android profile loader requires the explicit Node internals runtime flag', () => {
  assert.throws(() => vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    process: { execArgv: [] },
    require: () => { throw Error('Must not load modules before checking the runtime flag'); },
  }), /requires node --expose-internals/);
});

test('Android profile loader returns real Node internal module identities', { skip: !process.execArgv.includes('--expose-internals') }, () => {
  const loader = require(fileURLToPath(file));
  for (const id of ['internal/modules/esm/loader', 'internal/modules/cjs/loader', 'internal/modules/helpers', 'internal/modules/esm/utils', 'internal/modules/esm/resolve']) {
    assert.equal(loader.requireBuiltin(id), require(id));
  }
  assert.throws(() => loader.requireBuiltin('internal/bootstrap/realm'), /Unsupported DSH Pocket loader module/);
});
