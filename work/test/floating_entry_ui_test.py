"""在真实多邻国页面检查悬浮设置入口，不修改功能开关。"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def adb(args, *command):
    result = subprocess.run(
        [args.adb, '-s', args.serial, *command],
        check=True, capture_output=True, text=True, encoding='utf-8', timeout=20)
    return result.stdout


def nodes(args):
    adb(args, 'shell', '/system/bin/app_process',
        '-Djava.class.path=/data/local/tmp/owl-entry-test.dex:/system/framework/uiautomator.jar',
        '/system/bin', 'com.owl.auxiliary.UiDump', '/sdcard/owl-floating-ui.xml')
    xml = adb(args, 'exec-out', 'cat', '/sdcard/owl-floating-ui.xml')
    result = list(ET.fromstring(xml).iter('node'))
    assert any(n.get('package') == 'com.duolingo' for n in result), '当前不是多邻国页面'
    return result


def verify_ball(items):
    balls = [n for n in items if n.get('content-desc') == '猫头鹰助手设置']
    assert len(balls) == 1, f'每个页面应有且仅有一个设置悬浮球，实际 {len(balls)}'
    ball = balls[0]
    assert ball.get('clickable') == 'true', '悬浮球必须可点击'
    bounds = list(map(int, re.findall(r'\d+', ball.get('bounds', ''))))
    assert len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1], '悬浮球不可见'
    return bounds


def verify_panel(items):
    labels = {n.get('text') for n in items}
    assert '猫头鹰助手R' in labels, '点击后必须打开插件设置'
    assert '调试模式' in labels, '插件设置应包含原功能开关'
    assert '关闭' in labels, '插件设置必须可以关闭'
    assert not any('悬浮球显示在所有界面' in (label or '') for label in labels), '不再保留旧显示范围开关'
    assert '重置悬浮球位置' not in labels, '按用户要求移除重置按钮'
    switches = [n for n in items if n.get('class') == 'android.widget.Switch']
    assert len(switches) == 8, '八项功能均应使用右侧开关'
    assert not any(n.get('class') == 'android.widget.CheckBox' for n in items), '不使用勾选框'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expect', choices=['ball', 'panel', 'host-settings'], default='ball')
    parser.add_argument('--open', action='store_true')
    args = parser.parse_args()
    items = nodes(args)
    if args.expect == 'panel':
        verify_panel(items)
    else:
        bounds = verify_ball(items)
        if args.expect == 'host-settings':
            assert not any(n.get('text') == '猫头鹰助手R' for n in items), '宿主设置顶部不能残留模块面板'
        if args.open:
            adb(args, 'shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
            time.sleep(0.5)
            verify_panel(nodes(args))
    print(f'PASS floating entry: {args.expect}, open={args.open}')


if __name__ == '__main__':
    main()
