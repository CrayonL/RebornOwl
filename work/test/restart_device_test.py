"""点击重启后检查真实新进程、前台页面及配置加载；不代替按钮发起启动。"""
import argparse
import subprocess
import time

STABLE_SECONDS = 3


def adb(options, *command):
    result = subprocess.run(
        [options.adb, "-s", options.serial, *command],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10,
    )
    if result.returncode and command[:3] != ("shell", "pidof", "com.duolingo"):
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout.strip()


def verify(options):
    assert options.previous_pid.isdecimal(), "previous-pid 必须为进程号"
    deadline = time.monotonic() + options.timeout
    stable_since = None
    last_pid = None
    while time.monotonic() < deadline:
        current = adb(options, "shell", "pidof", "com.duolingo")
        if current != last_pid:
            stable_since = None
            last_pid = current
        foreground = adb(options, "shell", "dumpsys", "activity", "activities")
        resumed = [line for line in foreground.splitlines() if "topResumedActivity=" in line]
        if current.isdecimal() and current != options.previous_pid:
            logs = adb(options, "logcat", "-d", "--pid=" + current, "-v", "brief", "OwlAux:I", "AndroidRuntime:E", "*:S")
            if any("com.duolingo/" in line for line in resumed) and "Config 已加载:" in logs:
                assert "FATAL EXCEPTION" not in logs, logs
                stable_since = stable_since or time.monotonic()
            else:
                stable_since = None
        else:
            stable_since = None
        if stable_since and time.monotonic() - stable_since >= STABLE_SECONDS:
            print("RESTART_DEVICE_PASS old_pid=" + options.previous_pid + " new_pid=" + current)
            print("\n".join(resumed))
            print("\n".join(line for line in logs.splitlines() if "Config 已加载:" in line))
            return
        time.sleep(0.5)
    raise AssertionError("重启后未确认新进程及多邻国前台：pid=" + current + " foreground=" + str(resumed))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--previous-pid", required=True)
    parser.add_argument("--timeout", type=float, default=20)
    verify(parser.parse_args())


if __name__ == "__main__":
    main()
