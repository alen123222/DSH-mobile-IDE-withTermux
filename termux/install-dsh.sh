#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT="${POCKET_HOME:-$HOME/.local/share/dsh-pocket}"
RUNTIME="$ROOT/runtime"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
if [ "${PREFIX:-}" != /data/data/com.termux/files/usr ]; then
  echo 'This installer targets the standard com.termux installation.'; exit 1
fi
if [ "$(uname -m)" != aarch64 ]; then echo 'Android arm64 is required.'; exit 1; fi
mkdir -p "$RUNTIME"
exec > >(tee "$ROOT/install.log") 2>&1
trap 'status=$?; if [ "$status" -ne 0 ]; then printf "\nDSH installation failed (exit %s). Log: %s/install.log\n" "$status" "$ROOT"; fi' EXIT
echo '[1/6] Checking the Termux build environment'
pkg install -y python clang make cmake pkg-config git ripgrep ndk-sysroot libandroid-spawn
if ! command -v node >/dev/null; then pkg install -y nodejs-lts; fi
if ! command -v npm >/dev/null; then pkg install -y npm; fi
# Installing is not upgrading: a Termux set up earlier can hold a cmake whose binary
# needs a libc++ that is upgradable but not upgraded, and the native build then dies
# with "cannot locate symbol ... referenced by cmake", which names neither cause nor
# cure. Ask the toolchain to run, and bring the packages forward once if it cannot.
if ! cmake --version >/dev/null 2>&1; then
  echo '[*] The build tools cannot run as installed (stale libraries); upgrading Termux packages'
  # Keep the configuration already on the device: a custom mirror makes dpkg stop and
  # ask about sources.list, which in a script is an error rather than a question.
  pkg upgrade -y -o Dpkg::Options::=--force-confold || true
fi
if ! cmake --version >/dev/null 2>&1; then
  echo 'The build tools still cannot run after an upgrade; cmake --version keeps failing.'
  exit 1
fi
node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (!(major >= 24 || (major === 22 && minor >= 19))) throw Error("Node 22.19+ or 24+ is required")'

echo '[2/6] Downloading pinned DSH 0.2.0-rc.2 into its own installation'
if [ ! -f "$RUNTIME/package.json" ]; then printf '{"name":"dsh-pocket-runtime","private":true}\n' > "$RUNTIME/package.json"; fi
if [ ! -f "$RUNTIME/.pocket-ready" ]; then
  npm install --prefix "$RUNTIME" --ignore-scripts --no-audit --no-fund --save-exact @deepseek-ai/dsh@0.2.0-rc.2
fi

echo '[3/6] Applying checked Android patches'
node "$SCRIPTS/ensure-images.mjs" "$RUNTIME"
node "$SCRIPTS/patch-runtime.mjs" "$RUNTIME"
get_path() { node -e 'console.log(require(process.argv[1])[process.argv[2]])' "$RUNTIME/native-paths.json" "$1"; }
PTY="$(get_path pty)"
KOFFI="$(get_path koffi)"
SYSTEM="$(get_path system)"
FLOCK="$(get_path flockPackage)"
ANDROID_SYSTEM="$(get_path androidSystem)"
export CC=clang CXX=clang++ PYTHON="$(command -v python)"
export GYP_DEFINES="android_ndk_path="
NODE_HEADERS="$ROOT/node-headers"
mkdir -p "$NODE_HEADERS/include/node"
if [ -f "$PREFIX/include/node/node.h" ]; then
  cp -R "$PREFIX/include/node/." "$NODE_HEADERS/include/node/"
else
  echo 'Node development headers are missing from the Termux installation.'
  echo 'Install a current Termux nodejs or nodejs-lts package, then retry.'; exit 1
fi
NODE_GYP="$(npm root -g)/npm/node_modules/node-gyp/bin/node-gyp.js"
if [ ! -f "$NODE_GYP" ]; then echo 'npm bundled node-gyp was not found'; exit 1; fi

echo '[4/6] Building Android/Bionic native modules'
if ! (cd "$RUNTIME" && node -e 'require(process.argv[1])' "$PTY") >/dev/null 2>&1; then
  (cd "$PTY" && env -u CFLAGS -u CXXFLAGS -u CPPFLAGS node "$NODE_GYP" rebuild --nodedir="$NODE_HEADERS")
fi
if ! (cd "$RUNTIME" && node -e 'require(process.argv[1])' "$KOFFI") >/dev/null 2>&1; then
  (cd "$KOFFI" && env -u CFLAGS -u CXXFLAGS -u CPPFLAGS LDFLAGS="-landroid-spawn" node cnoke.cjs -P . -D src/koffi --prebuild --release)
fi
clang -shared -fPIC -O2 -DNAPI_VERSION=8 -DNODE_GYP_MODULE_NAME=system -I"$NODE_HEADERS/include/node" "$SYSTEM/src/flock.c" -o "$FLOCK/bin/system.node"
clang -shared -fPIC -O2 -DNAPI_VERSION=8 -DNODE_GYP_MODULE_NAME=publish -I"$NODE_HEADERS/include/node" "$SCRIPTS/publish.c" -o "$ANDROID_SYSTEM/publish.node"

echo '[5/6] Installing an isolated command wrapper'
mkdir -p "$RUNTIME/bin"
cat > "$RUNTIME/bin/dsh-pocket" <<EOF
#!$PREFIX/bin/bash
export DSH_HOME="\${DSH_HOME:-$ROOT/dsh-home}"
exec "$PREFIX/bin/node" --expose-internals "$RUNTIME/node_modules/@deepseek-ai/dsh/lib/bin.js" --patch "$SCRIPTS/android.patch.yml" "\$@"
EOF
chmod 700 "$RUNTIME/bin/dsh-pocket"

echo '[6/6] Testing native locking, publication, PTY, and SDK initialization'
node "$SCRIPTS/smoke.mjs" "$RUNTIME"
printf 'DSH 0.2.0-rc.2; pocket port 0.1.0\n' > "$RUNTIME/.pocket-ready"
echo 'DSH engine is ready. Return to DSH Pocket and refresh the environment.'
