package com.owl.auxiliary;

/** 构造参数边界测试；宿主类型来源由 host_contract_test.py 独立核对。 */
public final class CorrectFeatureTest {
    public static void main(String[] args) {
        checkScore(float.class, 0.25f, 1.0f);
        checkScore(double.class, 0.25d, 1.0d);
        CorrectFeature.ArgumentOverride graded =
                new CorrectFeature.ArgumentOverride(1, boolean.class, Boolean.TRUE);
        Object[] original = {"typo", false, "answer"};
        Object[] corrected = graded.rewrite(original);
        require(Boolean.TRUE.equals(corrected[1]), "correct flag must become true");
        require(Boolean.FALSE.equals(original[1]), "input array must be preserved");
        require(corrected[0] == original[0] && corrected[2] == original[2], "other fields unchanged");
        require(!graded.supports(new Class<?>[]{boolean.class}), "short signature rejected");
        require(!graded.supports(new Class<?>[]{String.class, int.class}), "mask is not correctness");
        expectRejected(graded, new Object[]{"typo", 0});
        expectRejected(graded, new Object[]{"typo", null});
        expectRejected(graded, new Object[]{"typo"});
        expectRejected(graded, null);
        System.out.println("PASS: constructor types, values, isolation and invalid arguments");
    }

    private static void checkScore(Class<?> type, Object before, Object after) {
        CorrectFeature.ArgumentOverride rule = new CorrectFeature.ArgumentOverride(0, type, after);
        require(rule.supports(new Class<?>[]{type, Object.class}), "score signature supported");
        require(!rule.supports(new Class<?>[]{int.class, Object.class}), "integer mask rejected");
        Object marker = new Object();
        Object[] result = rule.rewrite(new Object[]{before, marker});
        require(result[0].equals(after), "score value and boxed type must match");
        require(result[1] == marker, "score metadata preserved");
    }

    private static void expectRejected(CorrectFeature.ArgumentOverride rule, Object[] args) {
        try {
            rule.rewrite(args);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid constructor arguments accepted");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
