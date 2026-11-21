package io.github.crayonl.rebornowl;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Scanner;

public final class PremiumFeatureTest {
    public static void main(String[] args) {
        checkNoVideoCallReferences(PremiumFeature.class);
        checkNoVideoCallReferences(Targets.class);
        checkDashboardStatus(SubscriptionFeature.VIDEO_CALL_IN_PATH, true);
        checkDashboardStatus(SubscriptionFeature.VIDEO_CALL_IN_PRACTICE_HUB, true);
        checkDashboardStatus(SubscriptionFeature.NO_SUPER_PROMOS, false);
        checkDashboardStatus(null, false);
        System.out.println("PASS: 已移除旧视频通话依赖，两种通话入口均保留宿主状态判断");
    }

    private static void checkNoVideoCallReferences(Class<?> type) {
        String name = type.getSimpleName();
        try (Scanner bytes = new Scanner(type.getResourceAsStream(name + ".class"),
                StandardCharsets.ISO_8859_1.name())) {
            String compiled = bytes.useDelimiter("\\A").next();
            for (String target : new String[] {"VideoCallActivity", "VideoCallCallOrigin", "SubscriptionFeatureGroup"}) {
                if (compiled.contains(target)) {
                    throw new AssertionError(name + " 仍依赖旧的视频通话目标：" + target);
                }
            }
        }
    }

    private static void checkDashboardStatus(SubscriptionFeature feature, boolean expectedNative) {
        Object[] args = {Collections.emptySet(), Collections.emptySet(), feature};
        if (PremiumFeature.usesNativeDashboardStatus(args) != expectedNative) {
            throw new AssertionError("面板状态处理与预期不符：" + feature);
        }
    }

    private enum SubscriptionFeature {
        VIDEO_CALL_IN_PATH, VIDEO_CALL_IN_PRACTICE_HUB, NO_SUPER_PROMOS
    }
}
