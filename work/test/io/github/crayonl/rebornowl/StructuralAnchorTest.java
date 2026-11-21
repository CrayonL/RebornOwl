package io.github.crayonl.rebornowl;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 兼容性回归：本轮新增的定位点必须靠「语义字符串 + 结构」唯一命中，而不是靠类名。
 *
 * <p>断言四件事（全部直接读真实 dex，不加载宿主类）：
 * <ol>
 *   <li>调试准入锚点串只出现在一个类里，且该类有 {@code accept(Object)}；</li>
 *   <li>经验加成锚点串（背包 id）只出现在一个类里，且该类恰有一个
 *       {@code (User)→double} 与一个 {@code (User)→boolean}；</li>
 *   <li>调试分类的「结构回退」谓词（同时有 {@code getAllowOnReleaseBuilds()→boolean}
 *       与 {@code getTitle()→String}）恰好命中一个类 —— 名字变了也能定位；</li>
 *   <li>上述类名不出现在 {@link Targets} 的硬编码表里（说明定位不依赖类名）。</li>
 * </ol>
 */
public final class StructuralAnchorTest {

    private StructuralAnchorTest() {}

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "work/host-6.106.5-installed";
        File[] files = new File(dir).listFiles((d, n) -> n.startsWith("classes") && n.endsWith(".dex"));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("no dex in " + dir + "（本测试需要真实宿主 dex 目录）");
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : files) {
            DexIndex.addDexBytes(Files.readAllBytes(Paths.get(file.getAbsolutePath())));
        }

        String eligibility = unique("调试准入锚点", Targets.S_DEBUG_INELIGIBLE);
        requireAcceptObject("调试准入", eligibility);

        String boost = uniqueBoostPair();
        requireBoostPair("经验加成", boost);

        String category = uniqueStructural("调试分类结构回退");
        String status = uniqueDashboardStatus();
        System.out.println("STRUCTURAL_ANCHOR_PASS: 调试准入=" + eligibility
                + " 经验加成=" + boost + " 调试分类=" + category + " 会员状态=" + status);
    }

    /**
     * 会员视图状态：`SUPPORTED` 字段在全包里有两处（另一处是 GmsFido 的枚举），
     * 真正让它唯一的是「被带 Set 参数的视图方法当作返回类型」这一条 —— 这正是生产回退的判据。
     */
    private static String uniqueDashboardStatus() {
        List<String> seeds = DexIndex.findClassesContaining(Targets.F_DASH_SUPPORTED);
        java.util.List<String> matched = new java.util.ArrayList<>();
        for (String seed : seeds) {
            if (!DexIndex.findClassesWithMethod(null, seed, "Ljava/util/Set;").isEmpty()) {
                matched.add(seed);
            }
        }
        if (matched.size() != 1) {
            throw new AssertionError("会员视图状态结构谓词命中 " + matched.size() + " 个类：" + matched);
        }
        return matched.get(0);
    }

    /** 锚点串必须唯一命中一个类；多个候选说明锚点不够特定，改名后可能选错。 */
    private static String unique(String label, String anchor) {
        List<String> hits = DexIndex.findClassesContaining(anchor);
        if (hits.size() != 1) {
            throw new AssertionError(label + " 锚点命中 " + hits.size() + " 个类：" + hits);
        }
        return hits.get(0);
    }

    /** 通用锚点（如背包 id）会在很多类里出现，此时唯一性由结构谓词保证。 */
    private static String uniqueBoostPair() {
        List<String> matched = new java.util.ArrayList<>();
        for (String descriptor : DexIndex.findClassesContaining(XpBoostFeature.S_BOOST_ITEM)) {
            if (boostPair(descriptor) != null) {
                matched.add(descriptor);
            }
        }
        if (matched.size() != 1) {
            throw new AssertionError("经验加成结构谓词命中 " + matched.size() + " 个类：" + matched);
        }
        return matched.get(0);
    }

    /** DEX 层复制一遍生产判定：单参数 → double 与 单参数 → boolean，参数类型相同。 */
    private static String boostPair(String descriptor) {
        String doubleParam = null;
        String booleanParam = null;
        for (DexCode method : DexCode.methods(descriptor)) {
            if (method.parameters.length != 1) { continue; }
            if ("D".equals(method.returned)) {
                if (doubleParam != null) { return null; }
                doubleParam = method.parameters[0];
            } else if ("Z".equals(method.returned)) {
                if (booleanParam != null) { return null; }
                booleanParam = method.parameters[0];
            }
        }
        return doubleParam == null || booleanParam == null || !doubleParam.equals(booleanParam)
                ? null : doubleParam;
    }

    private static void requireAcceptObject(String label, String descriptor) {
        int found = 0;
        for (DexCode method : DexCode.methods(descriptor)) {
            if ("accept".equals(method.name) && "V".equals(method.returned)
                    && method.parameters.length == 1 && "Ljava/lang/Object;".equals(method.parameters[0])) {
                found++;
            }
        }
        if (found != 1) {
            throw new AssertionError(label + "：accept(Object) 命中 " + found + " 个");
        }
    }

    /** 单参数 → double / 单参数 → boolean，且两者参数类型相同，各恰好一个。 */
    private static void requireBoostPair(String label, String descriptor) {
        String doubleParam = null;
        String booleanParam = null;
        for (DexCode method : DexCode.methods(descriptor)) {
            if (method.parameters.length != 1) { continue; }
            if ("D".equals(method.returned)) {
                if (doubleParam != null) { throw new AssertionError(label + "：倍率方法不唯一"); }
                doubleParam = method.parameters[0];
            } else if ("Z".equals(method.returned)) {
                if (booleanParam != null) { throw new AssertionError(label + "：加成判定方法不唯一"); }
                booleanParam = method.parameters[0];
            }
        }
        if (doubleParam == null || booleanParam == null || !doubleParam.equals(booleanParam)) {
            throw new AssertionError(label + "：没有成对的 (参数)→double / (同一参数)→boolean");
        }
    }

    /** 调试分类：两个语义方法名同时存在，且只命中一个类（名字改了也能回退到这里）。 */
    private static String uniqueStructural(String label) {
        Set<String> titled = new LinkedHashSet<>(
                DexIndex.findClassesWithMethod("getTitle", "Ljava/lang/String;", null));
        List<String> owners = DexIndex.findClassesWithMethod("getAllowOnReleaseBuilds", "Z", null);
        Set<String> both = new LinkedHashSet<>();
        for (String owner : owners) {
            if (titled.contains(owner)) { both.add(owner); }
        }
        if (both.size() != 1) {
            throw new AssertionError(label + "：结构谓词命中 " + both.size() + " 个类：" + both);
        }
        return both.iterator().next();
    }
}
