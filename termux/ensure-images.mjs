import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';

// Image support on Android: sharp ships no prebuilt binary for android-arm64, and the
// native step later in this installer does not build one either. This step must
// therefore never require sharp to load. Requiring it turned "cannot load yet" into a
// fatal error before the rest of the installation could run, and the install could
// never reach the step that builds everything else.
//
// What it does instead: makes sure the WebAssembly fallback sharp publishes is present,
// and always succeeds. Whether sharp loads is decided by sharp itself at run time.
const runtime = path.resolve(process.argv[2]);
const require = createRequire(path.join(runtime, 'package.json'));
const attachments = createRequire(require.resolve('@deepseek-ai/dsh-attachment-local'));

function packageDirectory(entry) {
  let directory = path.dirname(entry);
  while (!fs.existsSync(path.join(directory, 'package.json'))) {
    const parent = path.dirname(directory);
    if (parent === directory) return null;
    directory = parent;
  }
  return directory;
}

const sharpDir = packageDirectory(attachments.resolve('sharp'));
if (sharpDir === null) {
  console.log('sharp is not installed; nothing to prepare.');
  process.exit(0);
}

const version = JSON.parse(fs.readFileSync(path.join(sharpDir, 'package.json'), 'utf8')).version;
const wasmManifest = path.join(runtime, 'node_modules', '@img', 'sharp-wasm32', 'package.json');
let installed = null;
try { installed = JSON.parse(fs.readFileSync(wasmManifest, 'utf8')).version; } catch { /* not installed */ }
if (installed === version) {
  console.log('WebAssembly image fallback ready (' + version + ')');
  process.exit(0);
}

const result = spawnSync('npm', ['install', '--prefix', runtime, '--ignore-scripts', '--no-audit', '--no-fund', '--save-exact', '@img/sharp-wasm32@' + version], { stdio: 'inherit' });
console.log(result.status === 0
  ? 'WebAssembly image fallback ready (' + version + ')'
  : 'The WebAssembly image fallback could not be added; the rest of the installation continues.');
process.exit(0);
