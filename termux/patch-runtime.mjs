import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { sdkSessionPatchPlan } from './sdk-session-patch.mjs';

const runtime = path.resolve(process.argv[2]);
const require = createRequire(path.join(runtime, 'package.json'));
const scripts = path.dirname(fileURLToPath(import.meta.url));
function packageDir(name, base = require) {
  let target;
  try { target = base.resolve(`${name}/package.json`); }
  catch { target = base.resolve(name); }
  let directory = path.dirname(target);
  while (true) {
    const file = path.join(directory, 'package.json');
    if (fs.existsSync(file) && JSON.parse(fs.readFileSync(file, 'utf8')).name === name) return directory;
    const parent = path.dirname(directory);
    if (parent === directory) throw new Error(`Cannot locate ${name}`);
    directory = parent;
  }
}
const dsh = packageDir('@deepseek-ai/dsh');
if (JSON.parse(fs.readFileSync(path.join(dsh, 'package.json'))).version !== '0.2.0-rc.2') throw new Error('This port requires DSH 0.2.0-rc.2');
const system = packageDir('@deepseek-ai/node-addon-system');
if (JSON.parse(fs.readFileSync(path.join(system, 'package.json'))).version !== '0.1.2') throw new Error('Unsupported node-addon-system version');
const session = packageDir('@deepseek-ai/dsh-session-persistence-jsonl');
const subprocess = packageDir('@deepseek-ai/dsh-subprocess-local');
const appBoot = packageDir('@deepseek-ai/dsh-app-boot');
const nativeRequire = createRequire(path.join(subprocess, 'package.json'));
const koffi = packageDir('koffi', nativeRequire), pty = packageDir('node-pty', nativeRequire);
const plans = [];
const sdkPlan = sdkSessionPatchPlan(runtime);
if (sdkPlan.original !== sdkPlan.output) plans.push(sdkPlan);
function replace(file, before, after) {
  const previousPlan = plans.find(p => p.file === file);
  const original = previousPlan?.original ?? fs.readFileSync(file, 'utf8');
  const source = previousPlan?.output ?? original;
  if (source.includes(after)) return;
  if (source.split(before).length !== 2) throw new Error(`Patch mismatch; refusing a partial port: ${file}\n${before}`);
  const output = source.replace(before, after);
  if (previousPlan) previousPlan.output = output;
  else plans.push({ file, original, output });
}
replace(path.join(system, 'lib/flock.js'), "platform !== 'linux' && platform !== 'darwin'", "platform !== 'linux' && platform !== 'darwin' && platform !== 'android'");
const sessionFile = path.join(session, 'lib/index.js');
replace(sessionFile, 'await link(tmp, finalPath);', 'await (process.platform === "android" ? pocketPublishExclusive(tmp, finalPath) : link(tmp, finalPath));');
replace(sessionFile, 'await internals.fs.link(staged, currentPath);', 'await (internals.platform === "android" ? pocketPublishExclusive(staged, currentPath) : internals.fs.link(staged, currentPath));');
const sessionSource = plans.find(p => p.file === sessionFile)?.output ?? fs.readFileSync(sessionFile, 'utf8');
if (!sessionSource.includes('import { publishExclusiveAndroid as pocketPublishExclusive }')) {
  const preamble = 'import { publishExclusiveAndroid as pocketPublishExclusive } from "@pocket/android-system";\n';
  const plan = plans.find(p => p.file === sessionFile);
  if (plan) plan.output = preamble + plan.output;
  else plans.push({ file: sessionFile, original: sessionSource, output: preamble + sessionSource });
}
const inspectors = fs.readdirSync(path.join(subprocess, 'lib')).filter(name => name.endsWith('.js')).map(name => path.join(subprocess, 'lib', name))
  .filter(file => fs.readFileSync(file, 'utf8').includes('return new LinuxProcessInspector(arch, internals)'));
if (inspectors.length !== 1) throw new Error('Could not uniquely locate Android process-inspector patch');
replace(inspectors[0], 'if (platform === "linux") return new LinuxProcessInspector(arch, internals);',
  'if (platform === "linux" || platform === "android") return new LinuxProcessInspector(arch, internals);');
replace(path.join(koffi, 'lib/native/base/base.cc'), '#if defined(__linux__)\n    const char *pathname = filename;',
  '#if defined(__linux__) && !defined(__ANDROID__)\n    const char *pathname = filename;');
replace(path.join(pty, 'binding.gyp'), 'OS=="mac" or OS=="solaris"', 'OS=="mac" or OS=="solaris" or OS=="android"');
replace(path.join(appBoot, 'lib/index.js'),
  'const addon = createRequire(import.meta.url)("node-addon-require-builtin");',
  'const addon = createRequire(import.meta.url)(process.platform === "android" ? "@pocket/android-loader" : "node-addon-require-builtin");');

// All exact matches were checked before the first mutation. Keep original bytes
// beside each patched distribution file so the installed diff stays reviewable.
for (const plan of plans) {
  if (!fs.existsSync(plan.file + '.pocket-original')) fs.writeFileSync(plan.file + '.pocket-original', plan.original);
  fs.writeFileSync(plan.file, plan.output);
}
const androidSystem = path.join(runtime, 'node_modules/@pocket/android-system');
const androidLoader = path.join(runtime, 'node_modules/@pocket/android-loader');
const flockPackage = path.join(runtime, 'node_modules/@deepseek-ai/node-addon-system-android-arm64');
fs.mkdirSync(androidSystem, { recursive: true });
fs.mkdirSync(androidLoader, { recursive: true });
fs.mkdirSync(path.join(flockPackage, 'bin'), { recursive: true });
fs.copyFileSync(path.join(scripts, 'android-system.mjs'), path.join(androidSystem, 'index.mjs'));
fs.copyFileSync(path.join(scripts, 'android-loader.cjs'), path.join(androidLoader, 'index.cjs'));
fs.writeFileSync(path.join(androidLoader, 'package.json'), JSON.stringify({ name: '@pocket/android-loader', version: '0.1.0', type: 'commonjs', exports: './index.cjs', license: 'MIT' }));
fs.writeFileSync(path.join(androidSystem, 'package.json'), JSON.stringify({ name: '@pocket/android-system', version: '0.1.0', type: 'module', exports: './index.mjs', license: 'MIT' }));
fs.writeFileSync(path.join(flockPackage, 'package.json'), JSON.stringify({ name: '@deepseek-ai/node-addon-system-android-arm64', version: '0.1.2', license: 'BSD-3-Clause', description: 'Local Android build of upstream flock.c' }));
fs.writeFileSync(path.join(runtime, 'native-paths.json'), JSON.stringify({ dsh, system, session, subprocess, appBoot, koffi, pty, androidSystem, androidLoader, flockPackage }));
const auditFile = path.join(runtime, 'pocket-patches.json');
const previousAudit = fs.existsSync(auditFile) ? JSON.parse(fs.readFileSync(auditFile, 'utf8')).files : [];
const audit = new Map((previousAudit || []).map(entry => [entry.path, entry]));
for (const p of plans) audit.set(path.relative(runtime, p.file), {
  path: path.relative(runtime, p.file), before: crypto.createHash('sha256').update(p.original).digest('hex'), after: crypto.createHash('sha256').update(p.output).digest('hex'),
});
for (const file of [path.join(system, 'lib/flock.js'), sessionFile, inspectors[0], path.join(koffi, 'lib/native/base/base.cc'), path.join(pty, 'binding.gyp'), path.join(appBoot, 'lib/index.js')]) {
  if (!fs.existsSync(file + '.pocket-original')) continue;
  audit.set(path.relative(runtime, file), {
    path: path.relative(runtime, file),
    before: crypto.createHash('sha256').update(fs.readFileSync(file + '.pocket-original')).digest('hex'),
    after: crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
  });
}
fs.writeFileSync(auditFile, JSON.stringify({ dsh: '0.2.0-rc.2', port: '0.1.0', files: [...audit.values()] }, null, 2));
console.log(`Applied ${plans.length} verified Android source changes.`);
