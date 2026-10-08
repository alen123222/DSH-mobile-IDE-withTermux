#!/data/data/com.termux/files/usr/bin/bash
set -eu
umask 077
ROOT="${POCKET_HOME:-$HOME/.local/share/dsh-pocket}"
mkdir -p "$ROOT"
exec 9>"$ROOT/first-run.lock"
# A retry watches the existing installation instead of starting a second apt.
flock -n 9 || exit 75
exec >"$ROOT/first-run.log" 2>&1
trap 'code=$?; if [ "$code" -ne 0 ]; then printf "\nInstallation failed (exit %s)\n" "$code"; fi' EXIT
echo '[1/3] Installing Node.js and Python. Checking mirrors and downloading packages may take several minutes.'
export DEBIAN_FRONTEND=noninteractive
# Compilers pulled in by Python's recommended pip package belong to the separate
# engine installer. The bridge needs only the Node/Python runtimes.
pkg install -y --no-install-recommends -o Dpkg::Options::=--force-confold -o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 nodejs-lts python
echo '[2/3] Runtime installed. Preparing the local service.'
