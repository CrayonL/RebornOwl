package com.owl.auxiliary;

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
        ALL.add(new MusicUnlockFeature());
        ALL.add(new PremiumFeature());
        ALL.add(new InfiniteTimeFeature());
        ALL.add(new TimezoneFeature(true));
        ALL.add(new TimezoneFeature(false));
        ALL.add(new CorrectFeature());
        ALL.add(new UnlockLessonsFeature());
        ALL.add(new SkipFeature());
        // 拽姐视频通话无独立开关：解锁逻辑已并入 PremiumFeature（会员解锁开启即同步生效）。
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
            FeatureDiagnostics.begin(f, Config.getOr(f.key(), f.defaultOn()));
        }
        for (Feature f : ALL) {
            try {
                f.check(lpp);
            } catch (Throwable t) {
                FeatureDiagnostics.failure(f.key(), "目标检查失败：" + t, t);
            }
            FeatureDiagnostics.checked(f.key());
            // 显式配置优先；配置没写时用功能默认值（defaultOn）。
            boolean on = Config.getOr(f.key(), f.defaultOn());
            if (!on) {
                Logx.i("[跳过] " + f.label() + "（未开启）");
                continue;
            }
            try {
                f.apply(lpp);
                FeatureDiagnostics.complete(f.key());
                Logx.i((FeatureDiagnostics.hasProblems(f.key()) ? "[存在异常] " : "[加载完成] ") + f.label());
            } catch (Throwable t) {
                FeatureDiagnostics.failure(f.key(), "加载失败：" + t, t);
            }
        }
        Logx.i("========== 全部开关处理完毕 ==========");
    }
}
