import { createRequire } from 'node:module';
import { getSystemErrorName } from 'node:util';
let binding;
export async function publishExclusiveAndroid(source, target) {
  binding ??= createRequire(import.meta.url)('./publish.node');
  const errno = binding.publish(source, target);
  if (errno) {
    const code = getSystemErrorName(-errno);
    throw Object.assign(new Error(`${code}: cannot publish session file`), { code, errno, syscall: 'renameat2', path: source, dest: target });
  }
}
