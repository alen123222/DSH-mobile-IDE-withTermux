import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { gunzipSync } from 'node:zlib';

// Only terminal rendering uses a bundled WebView. Chat and navigation are Compose.
const packages = [
  { name: '@xterm/xterm', version: '5.5.0', files: ['lib/xterm.js', 'css/xterm.css', 'LICENSE'] },
  { name: '@xterm/addon-fit', version: '0.10.0', files: ['lib/addon-fit.js', 'LICENSE'] },
];
const output = path.resolve('app/src/main/assets/terminal');
fs.mkdirSync(output, { recursive: true });
const manifest = [];
for (const pkg of packages) {
  const response = await fetch(`https://registry.npmjs.org/${encodeURIComponent(pkg.name)}/${pkg.version}`);
  if (!response.ok) throw new Error(`Registry returned ${response.status}`);
  const meta = await response.json();
  const archive = Buffer.from(await (await fetch(meta.dist.tarball)).arrayBuffer());
  const digest = `sha512-${crypto.createHash('sha512').update(archive).digest('base64')}`;
  if (digest !== meta.dist.integrity) throw new Error(`Integrity mismatch: ${pkg.name}`);
  const cache = path.resolve('.cache/assets', pkg.name.replaceAll('/', '-'));
  fs.mkdirSync(cache, { recursive: true });
  const tarball = path.join(cache, 'package.tgz');
  fs.writeFileSync(tarball, archive);
  const unpacked = gunzipSync(archive);
  const selected = new Map(pkg.files.map(file => [`package/${file}`, file]));
  for (let offset = 0; offset + 512 <= unpacked.length;) {
    const header = unpacked.subarray(offset, offset + 512);
    if (header.every(b => b === 0)) break;
    const name = header.subarray(0, 100).toString().replace(/\0.*$/, '');
    const size = Number.parseInt(header.subarray(124, 136).toString().replace(/\0.*$/, '').trim(), 8);
    if (!Number.isSafeInteger(size) || size < 0 || offset + 512 + size > unpacked.length) throw new Error('Malformed tar archive');
    if (selected.has(name)) {
      const target = path.join(cache, 'package', selected.get(name));
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, unpacked.subarray(offset + 512, offset + 512 + size));
      selected.delete(name);
    }
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  if (selected.size) throw new Error('Missing terminal assets');
  for (const file of pkg.files) {
    const filename = file === 'LICENSE' ? `${pkg.name.split('/').pop()}.LICENSE` : path.basename(file);
    fs.copyFileSync(path.join(cache, 'package', file), path.join(output, filename));
  }
  manifest.push({ name: pkg.name, version: pkg.version, tarball: meta.dist.tarball, integrity: digest });
}
fs.writeFileSync(path.join(output, 'dependencies.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log('Pinned terminal assets downloaded and integrity verified.');
