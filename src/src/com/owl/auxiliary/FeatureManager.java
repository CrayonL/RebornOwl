package com.owl.auxiliary;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 功能编排：读开关 → 逐个 apply，失败仅记日志。
 */
public final class FeatureManager {

    private static final List<Feature> ALL = new ArrayList<>();

    static {
        ALL.add(new DebugModeFeature());
        ALL.add(new MusicUnlockFeature());
        ALL.add(new PremiumFeature());
        ALL.add(new InfiniteTimeFeature());
        ALL.add(new TimezoneFeature());
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
            // 显式配置优先；配置没写时用功能默认值（defaultOn）。
            boolean on = Config.getOr(f.key(), f.defaultOn());
            if (!on) {
                Logx.i("[跳过] " + f.label() + "（未开启）");
                continue;
            }
            try {
                f.apply(lpp);
                Logx.i("[完成] " + f.label());
            } catch (Throwable t) {
                Logx.w("[失败] " + f.label() + " - " + t, t);
            }
        }
        Logx.i("========== 全部开关处理完毕 ==========");
    }
}
