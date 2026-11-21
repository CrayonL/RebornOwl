package io.github.crayonl.rebornowl;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** 在真实 APK 的 DEX 上执行生产定位器，结构和调用语义由独立契约测试核对。 */
public final class AutoAdaptDexTest {
    public static void main(String[] args) throws Exception {
        Logx.setMirror(false);
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(Paths.get(args[0]))) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                if (file.toString().endsWith(".dex")) { DexIndex.addDexBytes(Files.readAllBytes(file)); }
            }
        }
        report("CLOCK", DexIndex.findClassesWithMethod(null, "Ljava/util/Calendar;", "Ljava/util/TimeZone;"));
        List<String> models = DexIndex.findClassesWithMethod(null, descriptor(Targets.C_DASH_STATUS), "Ljava/util/Set;");
        report("MODEL", models);
        String section = descriptor(Targets.C_PATH_SECTION);
        report("FILTER", DexIndex.findClassesWithMethod(null, section, section));
        report("UNIT", DexIndex.findClassesContaining(Targets.S_PATH_UNIT));
        report("LEVEL", DexIndex.findClassesContaining(Targets.S_PATH_LEVEL));
        report("CONVERTER", DexIndex.findClassesContaining(Targets.S_PATH_CONVERTER));
        report("TREATMENT", DexIndex.findClassesContaining(Targets.S_PATH_TREATMENT));
        report("POPUP", DexIndex.findClassesContaining(Targets.S_PATH_POPUP));
        report("NODE", DexIndex.findClassesContaining("Node(key="));
    }

    private static String descriptor(String name) { return "L" + name.replace('.', '/') + ";"; }

    private static void report(String label, List<String> matches) {
        System.out.println("RESULT\t" + label + "\t" + String.join("\t", matches));
    }
}
