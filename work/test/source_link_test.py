"""执行真实 openSource 分支，记录 Android Intent；不代表已安装 GitHub 的真机验收。"""
import argparse
from pathlib import Path
import subprocess
import tempfile

STUBS = {
    'Context.java': 'package android.content; public class Context {}',
    'ComponentName.java': 'package android.content; public class ComponentName {}',
    'Activity.java': '''package android.app;
public class Activity extends android.content.Context {
    public static android.content.Intent opened;
    public android.content.pm.PackageManager getPackageManager() { return null; }
    public void startActivity(android.content.Intent intent) { opened = intent; }
}''',
    'Uri.java': '''package android.net;
public class Uri {
    private String value;
    public static Uri parse(String value) { Uri uri = new Uri(); uri.value = value; return uri; }
    public String toString() { return value; }
}''',
    'Intent.java': '''package android.content;
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE";
    public static boolean githubAvailable;
    public String action, category, target;
    public android.net.Uri data;
    public Intent(String action, android.net.Uri data) { this.action = action; this.data = data; }
    public Intent(Intent source) {
        action = source.action; data = source.data; category = source.category;
    }
    public Intent addCategory(String value) { category = value; return this; }
    public Intent setPackage(String value) { target = value; return this; }
    public ComponentName resolveActivity(android.content.pm.PackageManager manager) {
        return githubAvailable ? new ComponentName() : null;
    }
}''',
    'SourceLinkProbe.java': '''package com.owl.auxiliary;
import android.app.Activity;
import android.content.Intent;
public class SourceLinkProbe {
    public static void main(String[] args) throws Exception {
        ModuleActivity activity = new ModuleActivity();
        java.lang.reflect.Method open = ModuleActivity.class.getDeclaredMethod("openSource");
        open.setAccessible(true);
        for (boolean installed : new boolean[]{true, false}) {
            Intent.githubAvailable = installed;
            open.invoke(activity);
            Intent result = Activity.opened;
            String expected = installed ? "com.github.android" : null;
            if (!java.util.Objects.equals(expected, result.target)
                    || !ModuleActivity.SOURCE_URL.equals(result.data.toString())
                    || !Intent.ACTION_VIEW.equals(result.action)
                    || !Intent.CATEGORY_BROWSABLE.equals(result.category)) {
                throw new AssertionError("GitHub 优先或网页回退错误");
            }
        }
        System.out.println("PASS: GitHub 可用时定向 App，不可用时浏览器处理同一 HTTPS 地址");
    }
}'''
}


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
    with tempfile.TemporaryDirectory(prefix='source-link-', dir=root / 'work') as temp:
        folder = Path(temp)
        classes = folder / 'classes'
        classes.mkdir()
        classpath = ';'.join(map(str, [classes] + dependencies))
        sources = list((root / 'src/src').rglob('*.java'))
        compile_args = [javac, '-J-Dstderr.encoding=UTF-8', '-encoding', 'UTF-8',
                        '-cp', classpath, '-d', str(classes)]
        run(compile_args + list(map(str, sources)))
        for name, content in STUBS.items():
            (folder / name).write_text(content, encoding='utf-8')
        run(compile_args + [str(folder / name) for name in STUBS])
        run([java, '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', classpath,
             'com.owl.auxiliary.SourceLinkProbe'])


if __name__ == '__main__':
    main()
