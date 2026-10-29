"""用记录型 Toast 检查连续选择只保留最后一条提示；视觉延迟另做真机验收。"""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUB = r'''
package android.widget;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
public class Toast {
    public static final int LENGTH_SHORT = 0, LENGTH_LONG = 1;
    public static final List<Toast> active = new ArrayList<>();
    public CharSequence text;
    public static Toast makeText(Context context, CharSequence text, int duration) {
        Toast toast = new Toast(); toast.text = text; return toast;
    }
    public void cancel() { active.remove(this); }
    public void setText(CharSequence value) { text = value; }
    public void show() { active.add(this); }
}
'''
TEST = r'''
package com.owl.auxiliary;
import android.widget.Toast;
import java.lang.reflect.Method;
public class PremiumFeedbackProbe {
    public static void main(String[] args) throws Exception {
        Logx.setMirror(false);
        Method select = null;
        for (Method method : SettingsInjector.class.getDeclaredMethods()) {
            if (method.getName().equals("selectLevel")) { select = method; }
        }
        if (select == null) { throw new AssertionError("选择入口缺失"); }
        select.setAccessible(true);
        String[] levels = {"super", "max", "super", "max", "max"};
        for (String level : levels) {
            select.invoke(null, null, level);
            String expected = level.equals("max")
                    ? "已激活超级会员Max（含AI功能）" : "已激活超级会员";
            if (Toast.active.size() != 1) {
                throw new AssertionError("旧提示仍在排队，条数=" + Toast.active.size());
            }
            if (!expected.contentEquals(Toast.active.get(0).text)) {
                throw new AssertionError("提示不是最新选择");
            }
            if (!level.equals(Config.getStr(Config.P_LEVEL, ""))) {
                throw new AssertionError("选择未保存");
            }
        }
        System.out.println("PASS: 连续切换和重复点击仅保留最新提示，档位仍保存");
    }
}
'''


def run(command):
    subprocess.run(command, check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    dependencies = [args.sdk / 'platforms/android-35/android.jar',
                    root / 'work/libxposed-api-101.0.1.jar', root / 'work/xposed-api.jar']
    javac = str(args.jdk / 'bin/javac.exe')
    java = str(args.jdk / 'bin/java.exe')
    with tempfile.TemporaryDirectory(prefix='feedback-', dir=root / 'work') as temp:
        folder = Path(temp)
        classes = folder / 'classes'
        classes.mkdir()
        classpath = ';'.join(map(str, [classes] + dependencies))
        sources = list((root / 'src/src').rglob('*.java'))
        run([javac, '-J-Dstderr.encoding=UTF-8', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes),
             *map(str, sources)])
        (folder / 'Toast.java').write_text(STUB, encoding='utf-8')
        (folder / 'PremiumFeedbackProbe.java').write_text(TEST, encoding='utf-8')
        run([javac, '-J-Dstderr.encoding=UTF-8', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes),
             str(folder / 'Toast.java'), str(folder / 'PremiumFeedbackProbe.java')])
        run([java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', classpath,
             'com.owl.auxiliary.PremiumFeedbackProbe'])


if __name__ == '__main__':
    main()
