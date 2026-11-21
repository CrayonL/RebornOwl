package io.github.crayonl.rebornowl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** 本进程所有已有功能的检查结果；未开启功能只检查结构，不安装 Hook。 */
final class FeatureDiagnostics {
    private static final Map<String, Result> RESULTS = new LinkedHashMap<>();

    private FeatureDiagnostics() {}

    static synchronized void begin(Feature feature, boolean enabled) {
        RESULTS.put(feature.key(), new Result(feature.label(), enabled));
    }

    static synchronized void checked(String key) { RESULTS.get(key).checked = true; }

    static synchronized void complete(String key) { RESULTS.get(key).complete = true; }

    static synchronized void attempted(String key) { RESULTS.get(key).attempted = true; }

    static synchronized void failure(String key, String reason, Throwable error) {
        Logx.w(reason, error);
        Result result = RESULTS.get(key);
        if (result == null) { return; }
        result.problems.add(reason);
        // 两个开关共用同一宿主时钟，不能把另一项误报为正常。
        if (Config.K_DATE.equals(key) || Config.K_ZONEID.equals(key)) {
            Result other = RESULTS.get(Config.K_DATE.equals(key) ? Config.K_ZONEID : Config.K_DATE);
            if (other != null) { other.problems.add(reason); }
        }
    }

    static synchronized boolean hasProblems(String key) {
        Result result = RESULTS.get(key);
        return result != null && !result.problems.isEmpty();
    }

    static synchronized String problems() {
        StringBuilder text = new StringBuilder();
        for (Result result : RESULTS.values()) {
            if (result.problems.isEmpty()) { continue; }
            text.append(result.label).append("：\n");
            for (String reason : result.problems) { text.append(reason).append('\n'); }
            text.append('\n');
        }
        return text.toString().trim();
    }

    /**
     * 给普通用户看的结论：只列「哪个功能现在不可用」。
     *
     * <p>用户不需要看懂类名、方法签名和异常栈；这些技术细节只写日志，供反馈时排查。
     * 已关闭的功能不在这里出现 —— 用户没开它，也就谈不上「失效」。
     */
    static synchronized String userProblems() {
        StringBuilder text = new StringBuilder();
        for (Feature feature : FeatureManager.all()) {
            Result result = RESULTS.get(feature.key());
            if (result == null || result.problems.isEmpty() || !result.enabled) { continue; }
            if (text.length() > 0) { text.append('\n'); }
            text.append("• ").append(result.label).append("：暂时不可用");
        }
        return text.toString();
    }

    static synchronized String summary() {
        StringBuilder text = new StringBuilder();
        for (Feature feature : FeatureManager.all()) {
            Result result = RESULTS.get(feature.key());
            boolean selected = Config.getOr(feature.key(), feature.defaultOn());
            text.append(feature.label()).append("：").append(status(result, selected)).append('\n');
            if (result != null) {
                for (String reason : result.problems) { text.append(reason).append('\n'); }
            }
            text.append('\n');
        }
        return text.append("未开启功能仅检查目标结构，不安装 Hook。加载完成不代表服务端授权或所有题型已验证。").toString();
    }

    private static String status(Result result, boolean selected) {
        if (result == null || !result.checked) { return "尚未完成检测"; }
        String state = result.problems.isEmpty() ? "结构检查通过" : "检测到异常";
        if (selected != result.enabled) { return state + "，开关已更改，待重启"; }
        if (!result.enabled) { return state + "，未开启"; }
        if (!result.problems.isEmpty()) { return result.attempted ? "检测到异常，可能已部分加载" : "检查失败，未安装 Hook"; }
        return result.complete ? "已加载，实际效果待验证" : "结构检查通过，尚未加载";
    }

    private static final class Result {
        final String label;
        final boolean enabled;
        final TreeSet<String> problems = new TreeSet<>();
        boolean checked;
        boolean complete;
        boolean attempted;

        Result(String label, boolean enabled) {
            this.label = label;
            this.enabled = enabled;
        }
    }
}
