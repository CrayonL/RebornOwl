"""记录真实模块安装的回调并执行；宿主签名另由真实 DEX 契约核对。"""
import argparse
from pathlib import Path
import subprocess
import tempfile

FIXTURES = {
    'de/robv/android/xposed/XC_MethodHook.java': r'''
package de.robv.android.xposed;
import java.lang.reflect.Member;
public class XC_MethodHook {
    public static class MethodHookParam {
        public Member method;
        public Object[] args;
        private Object result;
        public void setResult(Object value) { result = value; }
        public Object getResult() { return result; }
    }
    public class Unhook {}
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
}
''',
    'de/robv/android/xposed/XposedBridge.java': r'''
package de.robv.android.xposed;
import java.lang.reflect.Member;
import java.util.LinkedHashMap;
import java.util.Map;
public class XposedBridge {
    public static final Map<Member, XC_MethodHook> hooks = new LinkedHashMap<>();
    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook hook) {
        if (hooks.put(method, hook) != null) { throw new AssertionError("重复安装时钟 Hook"); }
        return null;
    }
    public static Object read(String name, Object... args) throws Throwable {
        for (Map.Entry<Member, XC_MethodHook> item : hooks.entrySet()) {
            if (!item.getKey().getName().equals(name)) { continue; }
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = item.getKey(); param.args = args;
            item.getValue().beforeHookedMethod(param);
            return param.getResult();
        }
        throw new AssertionError("没有安装 " + name);
    }
}
''',
    'de/robv/android/xposed/XposedHelpers.java': r'''
package de.robv.android.xposed;
public class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }
}
''',
    'de/robv/android/xposed/callbacks/XC_LoadPackage.java': r'''
package de.robv.android.xposed.callbacks;
public class XC_LoadPackage {
    public static class LoadPackageParam { public ClassLoader classLoader; }
}
''',
    'bb7.java': r'''
import java.time.*;
import java.util.*;
public class bb7 {
    public nqk a() { throw new AssertionError(); }
    public LocalDateTime c() { throw new AssertionError(); }
    public kotlinx.datetime.TimeZone d() { throw new AssertionError(); }
    public Calendar e(TimeZone zone) { throw new AssertionError(); }
    public ZoneId f() { throw new AssertionError(); }
    public Instant g() { throw new AssertionError(); }
    public LocalDate h() { throw new AssertionError(); }
}
''',
    'nqk.java': r'''
import java.time.Instant;
public class nqk {
    public final Instant value;
    public nqk(long seconds, int nanos) { value = Instant.ofEpochSecond(seconds, nanos); }
}
''',
    'kotlinx/datetime/TimeZone.java': r'''
package kotlinx.datetime;
import java.time.ZoneId;
public class TimeZone {
    public final ZoneId value;
    public TimeZone(ZoneId value) { this.value = value; }
}
''',
    'ClockHookProbe.java': r'''
import com.owl.auxiliary.*;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.time.*;
import java.util.*;
public class ClockHookProbe {
    public static void main(String[] args) throws Throwable {
        Logx.setMirror(false);
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        if (args[0].equals("failure")) { verifyFailure(); return; }
        for (boolean date : new boolean[]{false, true}) {
            for (boolean region : new boolean[]{false, true}) { verify(date, region, args); }
        }
        System.out.println("PASS: 四组合实际模块回调、无重复 Hook、日期单开为昨天、地区单开不改时间戳");
    }
    private static void verifyFailure() throws Throwable {
        Config.set(Config.K_DATE, true);
        XC_LoadPackage.LoadPackageParam lpp = new XC_LoadPackage.LoadPackageParam();
        lpp.classLoader = ClockHookProbe.class.getClassLoader();
        try {
            new TimezoneFeature(true).apply(lpp);
            throw new AssertionError("不兼容或歧义时钟必须失败");
        } catch (IllegalStateException expected) {
            check(XposedBridge.hooks.isEmpty(), "失败前不得安装部分 Hook");
        }
        System.out.println("PASS: 不兼容或歧义时钟明确失败，未安装任何 Hook");
    }
    private static void verify(boolean date, boolean region, String[] expectedTypes) throws Throwable {
        XposedBridge.hooks.clear();
        Config.set(Config.K_DATE, date); Config.set(Config.K_ZONEID, region);
        Config.setStr(Config.P_TIMEZONE, "America/New_York");
        ZoneId zone = ZoneId.of(region ? "America/New_York" : "Asia/Shanghai");
        LocalDate yesterday = LocalDate.now(zone).minusDays(1);
        Config.setStr(Config.P_DATE, yesterday.toString());
        XC_LoadPackage.LoadPackageParam lpp = new XC_LoadPackage.LoadPackageParam();
        lpp.classLoader = ClockHookProbe.class.getClassLoader();
        Instant before = Instant.now();
        for (Feature feature : FeatureManager.all()) {
            if (!(feature instanceof TimezoneFeature)) { continue; }
            if (Config.getOr(feature.key(), feature.defaultOn())) { feature.apply(lpp); }
        }
        check(XposedBridge.hooks.size() == (date || region ? 7 : 0), "只安装一组七个时钟回调");
        if (!date && !region) { return; }
        check(XposedBridge.hooks.keySet().stream().allMatch(m ->
                m.getDeclaringClass().getName().equals(expectedTypes[0])), "仅挂接目标版本时钟");
        Instant instant = (Instant) XposedBridge.read("g");
        if (!date) { check(!instant.isBefore(before) && !instant.isAfter(Instant.now()), "真实时间戳不偏移"); }
        LocalDate expected = date ? yesterday : LocalDate.now(zone);
        check(instant.atZone(zone).toLocalDate().equals(expected), "时间戳对应所选日期");
        check(XposedBridge.read("h").equals(expected), "宿主日期返回昨天或今天");
        check(((LocalDateTime) XposedBridge.read("c")).toLocalDate().equals(expected), "日期时间保持一致");
        check(XposedBridge.read("f").equals(zone), "日期单开保留手机原时区");
        check(((kotlinx.datetime.TimeZone) XposedBridge.read("d")).value.equals(zone), "Kotlin 地区一致");
        Object kotlinInstant = XposedBridge.read("a");
        check(kotlinInstant.getClass().getName().equals(expectedTypes[1]), "使用目标方法实际返回类型");
        Instant kotlinValue = (Instant) kotlinInstant.getClass().getField("value").get(kotlinInstant);
        check(Math.abs(Duration.between(instant, kotlinValue).toMillis()) < 1000,
                "Kotlin 时间戳一致");
        TimeZone tokyo = TimeZone.getTimeZone("Asia/Tokyo");
        Calendar calendar = (Calendar) XposedBridge.read("e", tokyo);
        check(calendar.getTimeZone().equals(tokyo), "尊重调用者显式日历时区");
        check(Math.abs(calendar.getTimeInMillis() - instant.toEpochMilli()) < 1000, "日历使用同一时间偏移");
        Calendar defaultCalendar = (Calendar) XposedBridge.read("e", new Object[]{null});
        check(defaultCalendar.getTimeZone().toZoneId().equals(zone), "未指定日历时区时使用配置地区");
    }
    private static void check(boolean ok, String reason) {
        if (!ok) { throw new AssertionError(reason); }
    }
}
''',
}


def run(command):
    subprocess.run(command, check=True, timeout=60)


def cases():
    old_clock = FIXTURES['bb7.java']
    new_clock = old_clock.replace('bb7', 'cf7').replace('nqk', 'hzk')
    new_instant = FIXTURES['nqk.java'].replace('nqk', 'hzk')
    collision = 'public class bb7 extends ClassValue<Object> { public Object computeValue(Class<?> c) { return null; } }'
    new = {'bb7.java': collision, 'nqk.java': 'public class nqk {}',
           'cf7.java': new_clock, 'hzk.java': new_instant}
    return [
        ('6.103.6', {}, ['bb7', 'nqk']),
        ('6.104.4', new, ['cf7', 'hzk']),
        ('missing', {'bb7.java': None, 'nqk.java': None}, ['failure']),
        ('wrong-return', dict(new, **{'cf7.java': new_clock.replace('Instant g()', 'String g()')}), ['failure']),
        ('wrong-instant', dict(new, **{'hzk.java': 'public class hzk {}'}), ['failure']),
        ('static-method', dict(new, **{'cf7.java': new_clock.replace('Instant g()', 'static Instant g()')}), ['failure']),
        ('ambiguous', {'cf7.java': new_clock, 'hzk.java': new_instant}, ['failure']),
    ]


def run_case(args, case):
    label, overrides, probe_args = case
    root = Path(__file__).resolve().parents[2]
    dependencies = [args.sdk / 'platforms/android-35/android.jar',
                    root / 'work/libxposed-api-101.0.1.jar', root / 'work/xposed-api.jar']
    with tempfile.TemporaryDirectory(prefix='clock-hook-', dir=root / 'work') as temp:
        folder = Path(temp)
        classes = folder / 'classes'
        classes.mkdir()
        classpath = ';'.join(map(str, [classes] + dependencies))
        sources = list((root / 'src/src').rglob('*.java'))
        compiler = [str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes)]
        run(compiler + list(map(str, sources)))
        fixtures = {name: content for name, content in dict(FIXTURES, **overrides).items() if content is not None}
        for name, content in fixtures.items():
            target = folder / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
        run(compiler + [str(folder / name) for name in fixtures])
        run([str(args.jdk / 'bin/java.exe'), '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
             '-cp', classpath, 'ClockHookProbe'] + probe_args)
        print('CLOCK_CASE_PASS:', label, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    for case in cases():
        run_case(args, case)


if __name__ == '__main__':
    main()
