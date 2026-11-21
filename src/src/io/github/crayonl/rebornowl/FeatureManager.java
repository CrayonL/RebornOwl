package io.github.crayonl.rebornowl;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 所有功能先检查结构；只为已开启功能安装 Hook，异常集中报告。
 */
public final class FeatureManager {

    private static final List<Feature> ALL = new ArrayList<>();

    static {
        ALL.add(new DebugModeFeature());
        ALL.add(new PremiumFeature());
        ALL.add(new XpBoostFeature());
        ALL.add(new InfiniteTimeFeature());
        ALL.add(new TimezoneFeature(true));
        ALL.add(new TimezoneFeature(false));
        ALL.add(new CorrectFeature());
        ALL.add(new UnlockLessonsFeature());
        ALL.add(new SkipFeature());
    }

    private static volatile boolean sApplied = false;

    private FeatureManager() {}

    public static List<Feature> all() {
        return ALL;
    }

    public static synchronized void applyAll(XC_LoadPackage.LoadPackageParam lpp) {
        if (sApplied) {
            return;
        }
        sApplied = true;
        for (Feature f : ALL) {
            FeatureDiagnostics.begin(f, enabled(f));
        }
        for (Feature f : ALL) {
            try {
                f.check(lpp);
            } catch (Throwable t) {
                FeatureDiagnostics.failure(f.key(), "目标检查失败：" + t, t);
            }
            FeatureDiagnostics.checked(f.key());
            if (FeatureDiagnostics.hasProblems(f.key())) {
                Logx.i("[检查失败，未加载] " + f.label());
                continue;
            }
            // 显式配置优先；配置没写时用功能默认值（defaultOn）；隐藏功能始终启用。
            boolean on = enabled(f);
            if (!on) {
                Logx.i("[跳过] " + f.label() + "（未开启）");
                continue;
            }
            try {
                FeatureDiagnostics.attempted(f.key());
                f.apply(lpp);
                FeatureDiagnostics.complete(f.key());
                Logx.i((FeatureDiagnostics.hasProblems(f.key()) ? "[存在异常] " : "[加载完成] ") + f.label());
            } catch (Throwable t) {
                FeatureDiagnostics.failure(f.key(), "加载失败：" + t, t);
            }
        }
        Logx.i("========== 全部开关处理完毕 ==========");
    }

    /** 隐藏功能（{@link Feature#alwaysOn()}）无视配置；其余按配置或默认值。 */
    public static boolean enabled(Feature f) {
        return f.alwaysOn() || Config.getOr(f.key(), f.defaultOn());
    }

    /** 设置面板要展示的功能（隐藏入口的功能不占设置行）。 */
    public static List<Feature> settings() {
        List<Feature> visible = new ArrayList<>();
        for (Feature f : ALL) {
            if (f.settingsVisible()) {
                visible.add(f);
            }
        }
        return visible;
    }
}
