"""真机受控异常测试：备份后临时改模块配置，finally 原样恢复；不进入课程。"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

INTERNAL = '/data/user/0/com.duolingo/app_owl-auxiliary/config.properties'
EXTERNAL = '/sdcard/Android/data/com.duolingo/files/owl-auxiliary.properties'
KEYS = ('debug', 'music', 'premium', 'gametime', 'date', 'zoneid', 'correct', 'unlock', 'skip')
LABELS = {'调试模式', '音乐课程解锁', '会员解锁', '无限时间', '日期', '地区', '强制正确', '解锁关卡', '直接通关'}


def adb(*command, data=None):
    return subprocess.run(prefix + list(command), input=data, check=True,
                          capture_output=True, timeout=25).stdout

def read(path):
    return adb('exec-out', 'su', '-c', 'cat ' + path)

def write(path, data):
    adb('shell', 'su', '-c', 'cat > ' + path, data=data)

def nodes(name):
    adb('shell', '/system/bin/app_process',
        '-Djava.class.path=/data/local/tmp/owl-entry-test.dex:/system/framework/uiautomator.jar',
        '/system/bin', 'com.owl.auxiliary.UiDump', '/sdcard/owl-floating-ui.xml')
    xml = adb('exec-out', 'cat', '/sdcard/owl-floating-ui.xml')
    (out / (name + '.xml')).write_bytes(xml)
    return list(ET.fromstring(xml).iter('node'))

def labels(items):
    return {n.get('text') for n in items}

def tap(items, label):
    matches = [n for n in items if label == n.get('text')]
    assert len(matches) == 1, (label, len(matches))
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', matches[0].get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.5)

def launch(name):
    adb('shell', 'am', 'force-stop', 'com.duolingo')
    adb('shell', 'monkey', '-p', 'com.duolingo', '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(4)
    return nodes(name)

def screenshot(name):
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))


def main():
    global prefix, out
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    prefix = [args.adb, '-s', args.serial]
    out = Path(__file__).resolve().parents[1] / 'device'

    original = {path: read(path) for path in (INTERNAL, EXTERNAL)}
    for index, data in enumerate(original.values()):
        (out / ('ui-test-backup-' + str(index) + '.properties')).write_bytes(data)
    try:
        all_on = ''.join(key + '=true\n' for key in KEYS)
        write(EXTERNAL, original[EXTERNAL] + ('\n' + all_on + 'selected_date=\ntime_zone=Asia/Shanghai\n').encode())
        items = launch('all-features')
        pid = adb('shell', 'pidof', 'com.duolingo').decode().strip()
        log = adb('logcat', '-d', '--pid=' + pid, '-s', 'OwlAux:I', 'AndroidRuntime:E').decode('utf-8')
        (out / 'all-features-runtime.txt').write_text(log, encoding='utf-8')
        assert set(re.findall(r'\[加载完成\] ([^\r\n]+)', log)) == LABELS, log
        assert '部分功能运行异常' not in labels(items), '全开启启动出现异常'
        print('PASS 真机九项功能全部安装 Hook，启动无异常', flush=True)
        exercise_notice(original[EXTERNAL])
    finally:
        adb('shell', 'am', 'force-stop', 'com.duolingo')
        for path, data in original.items():
            write(path, data)
            assert hashlib.sha256(read(path)).digest() == hashlib.sha256(data).digest(), path
        items = launch('restored-home')
        assert '部分功能运行异常' not in labels(items), '恢复配置后仍出现异常'
        print('PASS 原模块配置逐字节恢复，正常启动无异常', flush=True)


def exercise_notice(original):
    invalid = original + b'\ndate=true\nselected_date=invalid-test-date\nskip=false\n'
    write(EXTERNAL, invalid)
    items = launch('failure-notice')
    assert '部分功能运行异常' in labels(items)
    screenshot('failure-notice')
    tap(items, '知道了')
    items = nodes('notice-preference')
    assert '是否不再提示此问题？' in labels(items)
    screenshot('notice-preference')
    tap(items, '下次仍提示')
    items = launch('remind-again')
    assert '部分功能运行异常' in labels(items)
    tap(items, '知道了')
    tap(nodes('ignore-choice'), '不再提示')
    items = launch('ignored-after-restart')
    assert '部分功能运行异常' not in labels(items), '同一问题忽略未持久化'
    print('PASS 知道了后出现二次弹窗；仍提示及不再提示跨重启生效', flush=True)
    write(EXTERNAL, invalid + b'selected_date=another-invalid-date\n')
    items = launch('new-failure')
    assert '部分功能运行异常' in labels(items), '新异常未重新提醒'
    print('PASS 新异常重新提醒', flush=True)


if __name__ == '__main__':
    main()
