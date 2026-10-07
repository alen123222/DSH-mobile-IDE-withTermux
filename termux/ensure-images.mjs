import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import fs from 'node:fs';

const runtime = path.resolve(process.argv[2]);
const require = createRequire(path.join(runtime, 'package.json'));
const attachments = createRequire(require.resolve('@deepseek-ai/dsh-attachment-local'));
try { attachments('sharp'); process.exit(0); } catch { /* Android needs the official WASM build. */ }
let directory = path.dirname(attachments.resolve('sharp'));
while (!fs.existsSync(path.join(directory, 'package.json'))) {
  const parent = path.dirname(directory);
  if (parent === directory) throw new Error('Cannot locate sharp package');
  directory = parent;
}
const version = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8')).version;
const result = spawnSync('npm', ['install', '--prefix', runtime, '--ignore-scripts', '--no-audit', '--no-fund', '--save-exact', `@img/sharp-wasm32@${version}`], { stdio: 'inherit' });
if (result.status !== 0) throw new Error('Could not install screenshot image support');
attachments('sharp');
console.log('Screenshot image support ready');
