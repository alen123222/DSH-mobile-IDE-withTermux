#!/system/bin/sh
# Runs only a fake package manager in an isolated directory; never touches Termux.
set -eu
: "${TEST_ROOT:?Set an empty scratch directory}"
: "${RUNTIME_SCRIPT:?Set path to prepare-runtime.sh}"
: "${RUNTIME_SHELL:?Set path to bash}"
mkdir -p "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/pkg" <<'PKG'
#!/system/bin/sh
echo invocation >>"$TEST_ROOT/invocations"
echo 'fake download in progress'
sleep 2
exit "${TEST_PKG_EXIT:-0}"
PKG
chmod 700 "$TEST_ROOT/bin/pkg"
export PATH="$TEST_ROOT/bin:$PATH" POCKET_HOME="$TEST_ROOT/state"
"$RUNTIME_SHELL" "$RUNTIME_SCRIPT" &
first=$!
for i in 1 2 3 4 5; do
    [ ! -f "$TEST_ROOT/invocations" ] || break
    sleep 1
done
grep -q 'fake download in progress' "$POCKET_HOME/first-run.log"
set +e
"$RUNTIME_SHELL" "$RUNTIME_SCRIPT"
second=$?
set -e
[ "$second" = 75 ]
wait "$first"
[ "$(wc -l <"$TEST_ROOT/invocations" | tr -d ' ')" = 1 ]
grep -q '\[2/3\]' "$POCKET_HOME/first-run.log"
export TEST_PKG_EXIT=9
set +e
"$RUNTIME_SHELL" "$RUNTIME_SCRIPT"
failed=$?
set -e
[ "$failed" = 9 ]
grep -q 'Installation failed (exit 9)' "$POCKET_HOME/first-run.log"
echo 'PASS: live log, exclusive installation, lock release, persistent failure'
