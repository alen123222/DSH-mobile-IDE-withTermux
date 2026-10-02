import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../app/src/main/assets/terminal/index.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function page() {
  const sent = [], frames = [], events = new Map(), touches = new Map();
  let dimensions, terminal, observer;
  const container = { addEventListener: (name, callback) => touches.set(name, callback) };
  class Terminal {
    constructor() { terminal = this; this.rows = 24; this.cols = 80; this.focusCount = 0; }
    loadAddon() {}
    open() {}
    onData(callback) { this.data = callback; }
    onResize(callback) { this.resized = callback; }
    resize(cols, rows) { this.cols = cols; this.rows = rows; this.resized({ rows, cols }); }
    focus() { this.focusCount++; }
  }
  vm.runInNewContext(script, {
    Terminal,
    FitAddon: { FitAddon: class { proposeDimensions() { return dimensions; } } },
    PocketTerminal: {
      resize: (rows, cols) => sent.push(['resize', rows, cols]),
      ready: () => sent.push(['ready']),
      input: data => sent.push(['input', data]),
    },
    document: { getElementById: () => container },
    window: { addEventListener: (name, callback) => events.set(name, callback) },
    ResizeObserver: class { constructor(callback) { observer = callback; } observe(element) { assert.equal(element, container); } },
    requestAnimationFrame: callback => { frames.push(callback); return frames.length; },
    Uint8Array, atob,
  });
  return { sent, terminal, touches,
    layout: value => { dimensions = value; observer(); while (frames.length) frames.shift()(); },
    windowResize: () => events.get('resize')(),
  };
}

test('terminal waits for usable viewport and font metrics before starting output', () => {
  const p = page();
  for (const size of [undefined, { rows: 1, cols: 86 }, { rows: 0, cols: 0 }, { rows: NaN, cols: 86 }]) {
    p.layout(size);
    assert.deepEqual(p.sent, []);
  }
  p.layout({ rows: 51, cols: 86 });
  assert.deepEqual(p.sent, [['resize', 51, 86], ['ready']]);
  p.layout({ rows: 51, cols: 86 });
  assert.equal(p.sent.length, 2);
});

test('keyboard and window resizing never publish invalid PTY dimensions', () => {
  const p = page();
  p.layout({ rows: 51, cols: 86 });
  p.layout({ rows: 1, cols: 86 });
  assert.equal(p.terminal.rows, 51);
  p.windowResize();
  p.layout({ rows: 20, cols: 86 });
  assert.deepEqual(p.sent.at(-1), ['resize', 20, 86]);
  p.layout({ rows: 800, cols: 900 });
  assert.deepEqual(p.sent.at(-1), ['resize', 500, 500]);
  assert.equal(p.sent.filter(value => value[0] === 'ready').length, 1);
});

test('tapping terminal whitespace focuses input while preserving control keys', () => {
  const p = page();
  p.touches.get('pointerdown')();
  assert.equal(p.terminal.focusCount, 1);
  p.terminal.data('pwd\r');
  assert.deepEqual(p.sent.at(-1), ['input', 'pwd\r']);
});
