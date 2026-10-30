package com.owl.auxiliary;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** 离线验证 DexIndex 的正确性：在工作站上直接喂 Duolingo 的 dex。 */
public class DexIndexTest {

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "work/dl/dex";
        String[] keys = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length)
                : new String[]{
                "Graded(blameType=",
                "SpeechRecognitionResult(score=",
                "CharacterMatch(base=",
                "WordMatch(base=",
                "ListenMatch(base=",
                "PassageCorrectnessInfo(percentCorrect=",
                "MatchChallenge(instruction=",
                "SessionDebugSettings(allowSessionOverride=",
                "TimedMultiSessionPractice(sessionXp=",
                "TimedMatchPractice(initialSessionTime=",
                "User(adsConfig=", "Music(courseSummary=",
                ", maxSessionLength=", Targets.S_PATH_LEVEL, Targets.S_PATH_SESSION_STATE,
        };

        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".dex"));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("no dex in " + dir);
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        long t0 = System.currentTimeMillis();
        for (File f : files) {
            DexIndex.addDexBytes(Files.readAllBytes(Paths.get(f.getAbsolutePath())));
        }
        System.out.println("已载入 dex: " + files.length + "，耗时 " + (System.currentTimeMillis() - t0) + "ms");

        for (String k : keys) {
            long t1 = System.currentTimeMillis();
            List<String> hit = DexIndex.findClassesContaining(k);
            if (hit.isEmpty()) {
                throw new AssertionError("Missing production target: " + k);
            }
            System.out.println("\n### " + k + "  → " + hit.size() + " 个类（"
                    + (System.currentTimeMillis() - t1) + "ms）");
            for (String h : hit) {
                System.out.println("    " + h);
            }
        }
    }
}
