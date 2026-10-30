"""记录模块实际注册的音乐回调；未解锁账号的服务端结果须另行实测。"""
import argparse
from pathlib import Path
import subprocess
import tempfile

from clock_hook_test import FIXTURES as CLOCK_FIXTURES

MODELS = [
    'nt8', 'fk4', 'ok4', 'pk4', 'zmq',
    'com.duolingo.core.music.licensed.LicensedMusicPathData',
    'com.duolingo.data.home.music.LicensedMusicDetails',
    'com.duolingo.data.home.music.LicensedSessionData',
    'com.duolingo.data.home.music.SongLandingPathData$LicensedSongLandingData',
]
ACCESS = 'com.duolingo.core.music.licensed.LicensedMusicAccess'
BRIDGE = r'''
package de.robv.android.xposed;
import java.lang.reflect.*;
import java.util.*;
public class XposedBridge {
    public static final Map<Member, XC_MethodHook> hooks = new LinkedHashMap<>();
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook hook) {
        if (hooks.put(method, hook) != null) { throw new AssertionError("重复 Hook"); }
        return null;
    }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> type, XC_MethodHook hook) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) { hookMethod(ctor, hook); }
        return Collections.emptySet();
    }
    public static Object[] rewrite(Constructor<?> ctor, Object[] args) throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = ctor; param.args = args;
        hooks.get(ctor).beforeHookedMethod(param);
        return param.args;
    }
}
'''
PROBE = r'''
import com.owl.auxiliary.*;
import com.duolingo.core.music.licensed.LicensedMusicAccess;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.*;
import java.util.*;
public class MusicHookProbe {
    public static void main(String[] names) throws Throwable {
        Logx.setMirror(false);
        XC_LoadPackage.LoadPackageParam lpp = new XC_LoadPackage.LoadPackageParam();
        lpp.classLoader = MusicHookProbe.class.getClassLoader();
        new MusicUnlockFeature().apply(lpp);
        Set<String> installed = new HashSet<>();
        for (Member member : XposedBridge.hooks.keySet()) { installed.add(member.getDeclaringClass().getName()); }
        check(installed.equals(new HashSet<>(Arrays.asList(names))), "音乐入口、会话和题目权限必须全部覆盖，不能 Hook 枚举构造器");
        check(XposedBridge.hooks.size() == names.length + 1, "只 Hook 含权限参数的构造器，包含序列化重载");
        for (Member member : XposedBridge.hooks.keySet()) { verify((Constructor<?>) member); }
        check(LicensedMusicAccess.NONE.name().equals("NONE"), "不得修改全局枚举单例");
        verifyMissingTarget(lpp);
        System.out.println("PASS: 九种音乐模型、十个构造入口、NONE/TASTER/FULL/null、参数隔离和缺失目标显式失败");
    }
    private static void verify(Constructor<?> ctor) throws Throwable {
        Class<?>[] types = ctor.getParameterTypes();
        int index = Arrays.asList(types).indexOf(LicensedMusicAccess.class);
        check(index >= 0, "不包含权限的构造器不能被 Hook");
        Object marker = new Object();
        for (LicensedMusicAccess state : new LicensedMusicAccess[]{
                LicensedMusicAccess.NONE, LicensedMusicAccess.TASTER, LicensedMusicAccess.FULL,
                null, LicensedMusicAccess.OTHER}) {
            Object[] original = new Object[types.length];
            Arrays.fill(original, marker);
            original[index] = state;
            Object[] changed = XposedBridge.rewrite(ctor, original);
            Object expected = state == LicensedMusicAccess.NONE || state == LicensedMusicAccess.TASTER
                    ? LicensedMusicAccess.FULL : state;
            check(changed[index] == expected, "权限转换不符合目标：" + ctor + " " + state);
            check(original[index] == state, "不得原地修改输入参数数组");
            for (int i = 0; i < original.length; i++) {
                if (i != index) { check(changed[i] == marker, "认证信息、歌曲或掩码参数被改动"); }
            }
        }
        expectBad(ctor, null);
        expectBad(ctor, new Object[0]);
        Object[] wrong = new Object[types.length];
        Arrays.fill(wrong, marker);
        expectBad(ctor, wrong);
    }
    private static void expectBad(Constructor<?> ctor, Object[] args) throws Throwable {
        try { XposedBridge.rewrite(ctor, args); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("非法参数未显式失败");
    }
    private static void verifyMissingTarget(XC_LoadPackage.LoadPackageParam lpp) throws Throwable {
        XposedBridge.hooks.clear();
        lpp.classLoader = new ClassLoader(lpp.classLoader) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("com.duolingo.data.home.music.LicensedSessionData")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
        try { new MusicUnlockFeature().apply(lpp); }
        catch (ReflectiveOperationException | IllegalStateException expected) {
            check(XposedBridge.hooks.isEmpty(), "目标核对失败前不得安装部分 Hook");
            return;
        }
        throw new AssertionError("缺失目标被静默忽略");
    }
    private static void check(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }
}
'''


def fixtures():
    sources = {k: v for k, v in CLOCK_FIXTURES.items() if k.startswith('de/')}
    sources['de/robv/android/xposed/XposedBridge.java'] = BRIDGE
    helper = sources['de/robv/android/xposed/XposedHelpers.java']
    sources['de/robv/android/xposed/XposedHelpers.java'] = helper.replace(
        'public class XposedHelpers {', '''public class XposedHelpers {
        public static Object getStaticObjectField(Class<?> type, String name) throws Exception {
            return type.getField(name).get(null);
        }''')
    sources[ACCESS.replace('.', '/') + '.java'] = '''package com.duolingo.core.music.licensed;
    public enum LicensedMusicAccess { FULL, TASTER, NONE, OTHER }'''
    sources['com/owl/auxiliary/Locator.java'] = '''package com.owl.auxiliary;
    public class Locator {
        public static Class<?> byKeyOrName(String key, String fallback, ClassLoader loader) throws Exception {
            return Class.forName("nt8", true, loader);
        }
        public static Class<?> findByName(String name, ClassLoader loader) {
            try { return Class.forName(name, true, loader); } catch (ClassNotFoundException e) { return null; }
        }
    }'''
    for model in MODELS:
        package, _, name = model.rpartition('.')
        code = f'package {package};\n' if package else ''
        code += f'public class {name} {{ public {name}() {{}}\n'
        code += f'public {name}(Object before, {ACCESS} access, Object after) {{}}\n'
        if name == 'LicensedMusicDetails':
            code += f'public {name}(int mask, Object before, {ACCESS} access, Object after) {{}}\n'
        sources[model.replace('.', '/') + '.java'] = code + '}'
    sources['MusicHookProbe.java'] = PROBE
    return sources


def run(command):
    subprocess.run(command, check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    deps = [args.sdk / 'platforms/android-35/android.jar', root / 'work/libxposed-api-101.0.1.jar',
            root / 'work/xposed-api.jar']
    with tempfile.TemporaryDirectory(prefix='music-hook-', dir=root / 'work') as temp:
        folder = Path(temp)
        classes = folder / 'classes'
        classes.mkdir()
        classpath = ';'.join(map(str, [classes] + deps))
        compiler = [str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes)]
        run(compiler + list(map(str, (root / 'src/src').rglob('*.java'))))
        sources = fixtures()
        for name, content in sources.items():
            path = folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        run(compiler + [str(folder / name) for name in sources])
        run([str(args.jdk / 'bin/java.exe'), '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
             '-cp', classpath, 'MusicHookProbe'] + MODELS)


if __name__ == '__main__':
    main()
