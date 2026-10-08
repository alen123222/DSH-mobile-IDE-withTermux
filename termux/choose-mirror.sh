#!/data/data/com.termux/files/usr/bin/bash
# Chooses the Termux package source before anything is downloaded: it decides whether
# the several hundred megabytes of the first install take minutes or the better part
# of an hour, and the answer depends on where the user is, not on us.
#
# The mirror files under $PREFIX/etc/termux/mirrors are the ones Termux itself ships,
# and each defines MAIN. The address therefore comes from that list instead of being
# hardcoded here, and an unknown or missing entry leaves the current source alone.
set -eu
TARGET="${1:-global}"
MIRRORS="$PREFIX/etc/termux/mirrors"
MAIN=""
if [ "$TARGET" = "china" ]; then
  for m in mirrors.tuna.tsinghua.edu.cn mirrors.aliyun.com mirrors.ustc.edu.cn mirrors.sdu.edu.cn mirrors.bfsu.edu.cn; do
    if [ -f "$MIRRORS/chinese_mainland/$m" ]; then
      . "$MIRRORS/chinese_mainland/$m"
      break
    fi
  done
else
  if [ -f "$MIRRORS/default" ]; then . "$MIRRORS/default"; fi
fi
if [ -z "$MAIN" ]; then
  echo 'No matching mirror entry; keeping the current package source.'
  exit 0
fi
printf 'deb %s stable main\n' "$MAIN" > "$PREFIX/etc/apt/sources.list"
echo "Package source: $MAIN"
apt update >/dev/null 2>&1 || true
