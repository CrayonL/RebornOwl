package com.owl.auxiliary;

import java.nio.file.*;

/** 输出生产解析结果，供真实四版 DEX 的独立字段契约核对。 */
public final class ModelContractDexTest {
    public static void main(String[] args) throws Exception {
        Logx.setMirror(false);
        try (java.util.stream.Stream<Path> files = Files.list(Paths.get(args[0]))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.toString().endsWith(".dex")) { DexIndex.addDexBytes(Files.readAllBytes(file)); }
            }
        }
        String[][] targets = {
            {Targets.S_SESSION_DEBUG, "maxSessionLength"}, {Targets.S_SESSION_DEBUG, "alwaysGradeCorrect"},
            {Targets.S_SPEECH, "score"}, {Targets.S_PASSAGE, "percentCorrect"}, {Targets.S_GRADED, "correct"},
            {Targets.S_TIMED_MULTI, "initialXpRampSessionTime"}, {Targets.S_TIMED_MATCH, "initialSessionTime"}
        };
        for (String[] target : targets) {
            java.util.List<String> names = DexIndex.findClassesContaining(target[0]);
            if (names.size() != 1) { throw new AssertionError("模型不唯一 " + names); }
            String owner = names.get(0), field = ModelContract.fieldName(owner, target[1]);
            int count = 0;
            for (DexCode method : DexCode.methods(owner)) {
                if (!method.name.equals("<init>")) { continue; }
                Integer index = ModelContract.storedParameter(method, field);
                if (index == null) { continue; }
                count++;
                System.out.println("PROPERTY\t" + owner + "\t" + target[1] + "\t" + field + "\t" + index + "\t" + String.join(",", method.parameters));
            }
            if (count == 0) { throw new AssertionError("没有参数写入 " + owner + "." + target[1]); }
        }
    }
}
