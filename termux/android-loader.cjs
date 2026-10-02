'use strict';

// DSH's profile router only needs these five modules. Android has no upstream
// native require-builtin prebuild. Use Node's explicit --expose-internals mode,
// and capture the real module objects before the profile router installs hooks.
// No private V8 pointers or guessed offsets are involved.
if (!process.execArgv.includes('--expose-internals')) {
  throw new Error('DSH Pocket Android loader requires node --expose-internals');
}
const ids = [
  'internal/modules/esm/loader',
  'internal/modules/cjs/loader',
  'internal/modules/helpers',
  'internal/modules/esm/utils',
  'internal/modules/esm/resolve',
];
const modules = new Map(ids.map(id => [id, require(id)]));
module.exports = Object.freeze({
  requireBuiltin(id) {
    if (!modules.has(id)) throw new Error(`Unsupported DSH Pocket loader module: ${id}`);
    return modules.get(id);
  },
});
