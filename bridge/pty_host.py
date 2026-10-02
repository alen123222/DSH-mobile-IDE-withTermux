"""A real POSIX PTY for Termux. JSON lines carry bytes; no command parsing."""
import base64
import codecs
import errno
import fcntl
import json
import os
import pty
import select
import signal
import struct
import subprocess
import sys
import termios


def emit(value):
    print(json.dumps(value, separators=(",", ":")), flush=True)


def main():
    cwd, shell = sys.argv[1:3]
    master, slave = pty.openpty()

    def child_setup():
        os.setsid()
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)

    env = {**os.environ, "TERM": "xterm-256color", "COLORTERM": "truecolor"}
    child = subprocess.Popen([shell, "-l"], cwd=cwd, env=env, stdin=slave, stdout=slave,
                             stderr=slave, preexec_fn=child_setup)
    os.close(slave)
    pending = b""

    def stop(_sig=None, _frame=None):
        raise SystemExit()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        while True:
            ready, _, _ = select.select([master, sys.stdin.fileno()], [], [], 0.5)
            if master in ready:
                try:
                    data = os.read(master, 32768)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                emit({"type": "data", "data": base64.b64encode(data).decode("ascii")})
            if sys.stdin.fileno() in ready:
                chunk = os.read(sys.stdin.fileno(), 65536)
                if not chunk:
                    break
                pending += chunk
                while b"\n" in pending:
                    line, pending = pending.split(b"\n", 1)
                    message = json.loads(line)
                    if message["type"] == "input":
                        data = base64.b64decode(message["data"], validate=True)
                        while data:
                            written = os.write(master, data)
                            data = data[written:]
                    elif message["type"] == "resize":
                        size = struct.pack("HHHH", int(message["rows"]), int(message["cols"]), 0, 0)
                        fcntl.ioctl(master, termios.TIOCSWINSZ, size)
                    elif message["type"] == "close":
                        return
            if child.poll() is not None and not ready:
                break
    finally:
        os.close(master)
        try:
            os.killpg(child.pid, signal.SIGHUP)
        except ProcessLookupError:
            pass
        try:
            child.wait(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait()
        emit({"type": "exit", "code": child.returncode})


if __name__ == "__main__":
    main()
