"""真实 DEX 语义定位和实际注册回调：参数换序、额外字段、分支、歧义及功能隔离。"""
import argparse
from pathlib import Path
import subprocess
import tempfile
from clock_hook_test import FIXTURES, fixture_dex

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

MODELS = {
    'ChangedSettings': ('SessionDebugSettings(allowSessionOverride=', 'Integer', 'maxSessionLength', 'alwaysGradeCorrect'),
    'ChangedMulti': ('TimedMultiSessionPractice(sessionXp=', 'int', 'initialXpRampSessionTime', 'unrelatedFlag'),
    'ChangedMatch': ('TimedMatchPractice(initialSessionTime=', 'int', 'initialSessionTime', 'unrelatedFlag'),
    'ChangedSpeech': ('SpeechRecognitionResult(score=', 'double', 'score', 'unrelatedFlag'),
    'ChangedPassage': ('PassageCorrectnessInfo(percentCorrect=', 'float', 'percentCorrect', 'unrelatedFlag'),
    'ChangedGraded': ('Graded(blameType=', 'int', 'unrelatedCount', 'correct'),
}

PROBE = r'''
package io.github.crayonl.rebornowl;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public class ModelHookProbe {
    public static void main(String[] args) throws Throwable {
        Logx.setMirror(false); DexFixture.load(args[0]);
        XC_LoadPackage.LoadPackageParam lpp = new XC_LoadPackage.LoadPackageParam();
        lpp.classLoader = ModelHookProbe.class.getClassLoader();
        new SkipFeature().check(lpp); new SkipFeature().apply(lpp);
        check(XposedBridge.hooks.size() == 1, "跳课只修改课程设置");
        verify(ChangedSettings.class, 4, null, 0);
        XposedBridge.hooks.clear();
        new InfiniteTimeFeature().check(lpp); new InfiniteTimeFeature().apply(lpp);
        check(XposedBridge.hooks.size() == 2, "无限时间不能影响课程长度");
        verify(ChangedMulti.class, 4, 31, 5940); verify(ChangedMatch.class, 4, 31, 5940);
        XposedBridge.hooks.clear();
        new CorrectFeature().check(lpp); new CorrectFeature().apply(lpp);
        check(XposedBridge.hooks.size() == 10, "四个构造器加六个判题方法，不能包含其他布尔方法");
        verify(ChangedSettings.class, 1, false, true); verify(ChangedSpeech.class, 4, 0.5d, 1.0d);
        verify(ChangedPassage.class, 4, 0.5f, 1.0f); verify(ChangedGraded.class, 1, false, true);
        check(ModelContract.parameters(SameBranch.class, "value", int.class).values().contains(0), "分支合流保持同一参数");
        reject(() -> ModelContract.parameters(DifferentBranch.class, "value", int.class));
        reject(() -> ModelContract.fieldName(DexCode.descriptor(DuplicateLabel.class), "value"));
        reject(() -> ModelContract.parameters(ChangedSpeech.class, "score", float.class));
        reject(() -> ModelContract.model(lpp.classLoader, "Ambiguous(value=", "value", int.class));
        Map<Constructor<?>, int[]> user = UserContract.parameters(TestUser.class, Tier.class);
        int[] indices = user.values().iterator().next();
        check(indices[0] == 2 && indices[1] == 5, "序列化字段插入后必须重新定位 hasMax，不能固定偏移");
        System.out.println("MODEL_HOOK_PASS: renamed/reordered/added fields, real callbacks, isolation, branch merge, ambiguity and serialized offsets");
    }
    private static void verify(Class<?> type, int index, Object before, Object after) throws Throwable {
        Constructor<?> ctor = type.getConstructors()[0];
        Object[] original = {"label", false, 91L, 81, 31}; original[index] = before;
        Object[] changed = XposedBridge.rewrite(ctor, original);
        check(Objects.equals(changed[index], after) && Objects.equals(original[index], before), "只修改目标值，输入不变");
        for (int i = 0; i < original.length; i++) { if (i != index) { check(changed[i] == original[i], "无关参数被修改"); } }
        Object instance = ctor.newInstance(changed);
        check(Objects.equals(type.getField(index == 1 ? "flag" : "value").get(instance), after), "实际构造结果没有写入语义字段");
    }
    private interface Checked { void run(); }
    private static void reject(Checked task) {
        try { task.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("不明确的契约必须失败");
    }
    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
class SameBranch {
    final int field;
    SameBranch(int value, boolean choose) { int moved; if (choose) { moved = value; } else { moved = value; } field = moved; }
    public String toString() { return new StringBuilder("Same(value=").append(field).append(")").toString(); }
}
class DifferentBranch {
    final int field;
    DifferentBranch(int left, int right, boolean choose) { field = choose ? left : right; }
    public String toString() { return new StringBuilder("Different(value=").append(field).append(")").toString(); }
}
class DuplicateLabel {
    int a, b;
    public String toString() { return new StringBuilder("Duplicate(value=").append(a).append(", value=").append(b).toString(); }
}
class AmbiguousOne {
    final int field;
    AmbiguousOne(int value) { field = value; }
    public String toString() { return new StringBuilder("Ambiguous(value=").append(field).toString(); }
}
class AmbiguousTwo {
    final int field;
    AmbiguousTwo(int value) { field = value; }
    public String toString() { return new StringBuilder("Ambiguous(value=").append(field).toString(); }
}
enum Tier { GOLD, PREMIUM }
class TestUser {
    TestUser(int mask, String id, Tier tier, boolean inserted, int other, boolean max, String name) {}
}
class Descriptor {
    Descriptor(String name, TestSerializer serializer, int count) {}
    void add(String name, boolean optional) {}
}
class TestSerializer {
    static final Descriptor D;
    static {
        D = new Descriptor("io.github.crayonl.rebornowl.TestUser", new TestSerializer(), 6);
        D.add("id", true); D.add("subscriberLevel", true); D.add("inserted", true);
        D.add("other", true); D.add("hasMax", true); D.add("name", true);
    }
    Object deserialize(Object input) { return new TestUser(0, "", Tier.GOLD, false, 5, false, ""); }
}
'''


def sources():
    result = {key: value for key, value in FIXTURES.items() if key.startswith('de/') or key.endswith('DexFixture.java')}
    result['de/robv/android/xposed/XposedBridge.java'] = BRIDGE
    for name, (marker, kind, value_label, flag_label) in MODELS.items():
        first_target = marker.endswith('(' + value_label + '=')
        first_value, helper_value = ('value', 'text') if first_target else ('text', 'value')
        helper_type, helper_label = ('String', 'text') if first_target else (kind, value_label)
        result[f'io/github/crayonl/rebornowl/{name}.java'] = f'''
package io.github.crayonl.rebornowl;
public class {name} {{
    public final String text; public final boolean flag; public final long wide;
    public final int unrelated; public final {kind} value;
    public {name}(String text, boolean flag, long wide, int unrelated, {kind} value) {{
        this.text=text; this.flag=flag; this.wide=wide; this.unrelated=unrelated; this.value=value;
    }}
    private static void helper(String suffix, {helper_type} target, StringBuilder builder, boolean flag) {{
        builder.append(flag).append(suffix).append(target);
    }}
    public String toString() {{
        StringBuilder b = new StringBuilder("{marker}"); b.append({first_value}).append(", {flag_label}=");
        helper(", {helper_label}=", {helper_value}, b, flag);
        return b.append(", wide=").append(wide).append(", unrelated=").append(unrelated).append(")").toString();
    }}
}}
'''
    result['com/duolingo/core/language/Language.java'] = 'package com.duolingo.core.language; public class Language {}'
    for kind in ('CharacterMatch', 'WordMatch', 'ListenMatch'):
        result[f'io/github/crayonl/rebornowl/Changed{kind}.java'] = f'''
package io.github.crayonl.rebornowl;
public class Changed{kind} {{
    public String toString() {{ return "{kind}(base="; }}
    public boolean renamed(String a, String b) {{ return false; }}
    public boolean reordered(com.duolingo.core.language.Language language, String text) {{ return false; }}
    public boolean unrelated() {{ return false; }}
    public boolean equals(Object other) {{ return false; }}
}}
'''
    result['io/github/crayonl/rebornowl/ModelHookProbe.java'] = PROBE
    return result


def run(command):
    subprocess.run(command, check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    dependencies = [args.sdk / 'platforms/android-35/android.jar', root / 'work/libxposed-api-101.0.1.jar', root / 'work/xposed-api.jar']
    with tempfile.TemporaryDirectory(prefix='model-hook-', dir=root / 'work') as temp:
        folder = Path(temp)
        classes = folder / 'classes'
        classes.mkdir()
        classpath = ';'.join(map(str, [classes] + dependencies))
        compiler = [str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-Xlint:-options', '-cp', classpath, '-d', str(classes)]
        run(compiler + list(map(str, (root / 'src/src').rglob('*.java'))))
        fixtures = sources()
        for name, content in fixtures.items():
            target = folder / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
        run(compiler + [str(folder / name) for name in fixtures])
        dex = fixture_dex(args, folder, classes)
        run([str(args.jdk / 'bin/java.exe'), '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp',
             classpath, 'io.github.crayonl.rebornowl.ModelHookProbe', str(dex)])


if __name__ == '__main__':
    main()
