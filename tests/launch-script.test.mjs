// Reconstructs the scripts TermuxConnection.kt builds and hands to
// RUN_COMMAND, then runs `bash -n` on them.
//
// Bug this exists for: guardScript() ended in a raw string whose trailing
// newline Kotlin's trimIndent() removed, so `fi` was glued to the next
// fragment. bash rejected the whole bootstrap with
//   line 37: syntax error: unexpected end of file from `if' command on line 30
// A syntax check in CI is the only thing that catches a broken launch script
// before it reaches a phone.
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bash = process.env.BASH || (process.platform === 'win32'
  ? path.resolve(execFileSync('where.exe', ['git'], { encoding: 'utf8' }).trim().split(/\r?\n/)[0], '..', '..', 'bin', 'bash.exe')
  : 'bash');
const TOKEN = 'a'.repeat(64);

const asScriptBlock = text => (text.endsWith('\n') ? text : text + '\n');
const trimIndent = text => {
  const lines = text.replace(/^\n/, '').replace(/\s+$/, '').split('\n');
  const indents = lines.filter(l => l.trim()).map(l => l.match(/^ */)[0].length);
  const common = indents.length ? Math.min(...indents) : 0;
  return lines.map(l => l.slice(common)).join('\n');
};

function guardScript(token) {
  return trimIndent(`
        export POCKET_TOKEN='${token}'
        if node -e "
          const t = process.env.POCKET_TOKEN;
          fetch('http://127.0.0.1:8765/v1/health', { headers: { Authorization: 'Bearer ' + t }, signal: AbortSignal.timeout(1500) })
            .then(r => { if (r.ok) { console.log('DSH Pocket: local service already running'); process.exit(0); } process.exit(1); })
            .catch(() => process.exit(1));
        " 2>/dev/null; then exit 0; fi
        if node -e "fetch('http://127.0.0.1:8765/v1/health', { signal: AbortSignal.timeout(1200) }).then(() => process.exit(0)).catch(() => process.exit(1))" 2>/dev/null; then
          echo 'DSH Pocket: port 8765 is held by a service this app cannot authenticate with.'
          echo 'Run in Termux: pkill -f server.mjs'
          exit 1
        fi
    `);
}

function assetsScript(folders) {
  let out = `set -eu\numask 077\nROOT="$HOME/.local/share/dsh-pocket"\n`;
  for (const folder of folders) {
    out += `mkdir -p "$ROOT/${folder}"\n`;
    for (const name of fs.readdirSync(path.join(root, folder)).sort()) {
      out += `printf '%s' '${fs.readFileSync(path.join(root, folder, name)).toString('base64')}' | base64 -d > "$ROOT/${folder}/${name}"\n`;
    }
  }
  return out;
}

function bootstrapScript() {
  const config = Buffer.from(JSON.stringify({ token: TOKEN, port: 8765 })).toString('base64');
  return asScriptBlock(assetsScript(['bridge', 'termux']))
    + `printf '%s' '${config}' | base64 -d > "$ROOT/connection.json"\n`
    + "if ! command -v node >/dev/null || ! command -v python >/dev/null; then\n echo '请在 Termux 执行: pkg install nodejs-lts python'; exit 1; fi\n"
    + asScriptBlock(guardScript(TOKEN))
    + 'export POCKET_HOME="$ROOT"\nexec node "$ROOT/bridge/server.mjs"\n';
}

function upgradeScript() {
  return `set -eu\nexport POCKET_UPDATE_TOKEN='${TOKEN}'\nnode --input-type=module <<'POCKET_UPDATE'\n`
    + trimIndent(`
                const headers = { Authorization: 'Bearer ' + process.env.POCKET_UPDATE_TOKEN };
                const data = await (await fetch('http://127.0.0.1:8765/v1/health', {headers})).json();
            `)
    + `\nPOCKET_UPDATE\nunset POCKET_UPDATE_TOKEN\n`
    + asScriptBlock(assetsScript(['bridge', 'termux']))
    + 'export POCKET_HOME="$ROOT"\nexec node "$ROOT/bridge/server.mjs"\n';
}

function check(label, script) {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-sh-')), 'script.sh');
  fs.writeFileSync(file, script);
  try {
    execFileSync(bash, ['-n', file], { stdio: 'pipe' });
  } catch (error) {
    assert.fail(`${label} is not valid bash:\n${error.stderr?.toString()}`);
  }
  fs.rmSync(path.dirname(file), { recursive: true, force: true });
}

test('the bootstrap script is syntactically valid bash', () => {
  const script = bootstrapScript();
  // The exact failure the phone reported: `fi` glued to the next statement on
  // one line. bash calls this "unexpected end of file from `if`". A glued `fi`
  // is `fi` plus spaces/tabs; a real newline must separate them, so match
  // horizontal whitespace only.
  assert.doesNotMatch(script, /fi[ \t]+export POCKET_HOME/, 'a script block must not be glued to the next fragment');
  assert.match(script, /fi\nexport POCKET_HOME/);
  check('bootstrap', script);
});

test('the upgrade script is syntactically valid bash', () => {
  const script = upgradeScript();
  assert.doesNotMatch(script, /unset POCKET_UPDATE_TOKEN\nexport/, 'no fragment may be glued');
  check('upgrade', script);
});

test('trimIndent alone would have produced the broken script', () => {
  // Guards the regression itself: if this assertion ever stops holding, the
  // asScriptBlock wrapper is no longer necessary and can be reconsidered.
  const broken = trimIndent(`
        if true; then
          echo hi
        fi
    `);
  assert.doesNotMatch(broken, /\n$/, 'trimIndent eats the trailing newline');
  assert.notEqual(broken, asScriptBlock(broken));
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-sh-')), 'broken.sh');
  fs.writeFileSync(file, broken + 'export X=1\n');
  assert.throws(() => execFileSync(bash, ['-n', file], { stdio: 'pipe' }), 'the glued form really is a syntax error');
  fs.writeFileSync(file, asScriptBlock(broken) + 'export X=1\n');
  execFileSync(bash, ['-n', file], { stdio: 'pipe' });
  fs.rmSync(path.dirname(file), { recursive: true, force: true });
});

test('the guard script is valid on its own and standalone-safe', () => {
  check('guard alone', asScriptBlock(guardScript(TOKEN)));
  // It must also survive being the last thing in a script, with `set -eu` on.
  check('guard under set -eu', `set -eu\n${asScriptBlock(guardScript(TOKEN))}`);
});