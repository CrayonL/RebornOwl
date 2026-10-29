"""在真机点击日期和确定后，核对宿主实际保存的日期日志。"""
import argparse
import datetime
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--pid", required=True)
    parser.add_argument("--expected-date", required=True)
    options = parser.parse_args()
    assert options.pid.isdecimal(), "pid 必须为进程号"
    datetime.date.fromisoformat(options.expected_date)
    result = subprocess.run(
        [options.adb, "-s", options.serial, "logcat", "-d", "--pid=" + options.pid,
         "-v", "time", "OwlAux:I", "*:S"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10, check=True,
    )
    dates = re.findall(r"日期设置：日期 → (\d{4}-\d{2}-\d{2})", result.stdout)
    assert dates, "没有找到日期保存记录"
    assert dates[-1] == options.expected_date, f"所选 {options.expected_date}，实际保存 {dates[-1]}"
    print("CALENDAR_SELECTION_PASS saved=" + dates[-1] + " pid=" + options.pid)


if __name__ == "__main__":
    main()
