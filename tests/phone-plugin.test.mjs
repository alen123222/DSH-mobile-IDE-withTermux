import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

// Exercise the real plugin with an identity tool factory, without installing DSH.
const source = fs.readFileSync(new URL('../bridge/phone-plugin.mjs', import.meta.url), 'utf8');
const { apply } = await import('data:text/javascript;base64,' + Buffer.from(
  'const defineTool = value => value;\n' + source.slice(source.indexOf('export const name'))).toString('base64'));

test('empty accessibility tree reports the failed screenshot and its blocking windows', async () => {
  const previousFetch = globalThis.fetch;
  const previousVision = process.env.POCKET_PHONE_VISION;
  process.env.POCKET_PHONE_VISION = '1';
  const calls = [];
  const blockingWindows = [{ package: 'other.app', type: 1 }];
  globalThis.fetch = async (_url, options) => {
    const action = JSON.parse(options.body).action;
    calls.push(action);
    return { json: async () => action === 'observe'
      ? { ok: true, package: 'com.tencent.mm', count: 0, nodes: [] }
      : { ok: false, error: 'Use a single app window for screenshots', blockingWindows } };
  };
  try {
    const registered = new Map();
    apply({ tools: { register: tool => registered.set(tool.name, tool) },
      attachments: { saveImage: () => { throw new Error('No image should be saved'); } } });
    const result = JSON.parse((await registered.get('phone_observe').execute({})).text);
    assert.equal(result.ok, false);
    assert.match(result.error, /Use a single app window/);
    assert.deepEqual(result.screenshotError.blockingWindows, blockingWindows);
    assert.match(result.hint, /Do not guess/);
    assert.deepEqual(calls, ['observe', 'screenshot']);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousVision === undefined) delete process.env.POCKET_PHONE_VISION;
    else process.env.POCKET_PHONE_VISION = previousVision;
  }
});

test('empty accessibility tree delivers a successful screenshot as an image attachment', async () => {
  const previousFetch = globalThis.fetch;
  const previousVision = process.env.POCKET_PHONE_VISION;
  process.env.POCKET_PHONE_VISION = '1';
  globalThis.fetch = async (_url, options) => ({ json: async () => JSON.parse(options.body).action === 'observe'
    ? { ok: true, package: 'com.tencent.mm', count: 0, nodes: [] }
    : { ok: true, input: { nativeConnectionAvailable: true }, imageBase64: Buffer.from('image fixture').toString('base64') } });
  try {
    const registered = new Map();
    apply({ tools: { register: tool => registered.set(tool.name, tool) },
      attachments: { saveImage: async ({ data }) => {
        assert.equal(data.toString(), 'image fixture');
        return { attachmentId: 'screen', mediaType: 'image/jpeg', bytes: data.length, width: 100, height: 200 };
      } } });
    const result = await registered.get('phone_observe').execute({});
    assert.equal(JSON.parse(result.text).ok, true);
    assert.equal(JSON.parse(result.text).input.nativeConnectionAvailable, true);
    assert.match(JSON.parse(result.text).note, /phone_type/);
    assert.match(JSON.parse(result.text).note, /Never tap keyboard/);
    assert.equal(JSON.parse(result.image).attachmentId, 'screen');
    assert.equal(registered.get('phone_observe').output.render({}, result)[1].type, 'image');
  } finally {
    globalThis.fetch = previousFetch;
    if (previousVision === undefined) delete process.env.POCKET_PHONE_VISION;
    else process.env.POCKET_PHONE_VISION = previousVision;
  }
});
