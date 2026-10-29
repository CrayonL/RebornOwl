package com.owl.auxiliary;

public final class PremiumFeatureTest {
    public static void main(String[] args) {
        if (PremiumFeature.dashboardInvoke(Factory.class).getParameterTypes()[0] != Object.class) {
            throw new AssertionError("wrong overload selected");
        }
        reject(OldPurchaseFactory.class);
        reject(WrongReturn.class);
        reject(StaticFactory.class);
        System.out.println("PASS: dashboard overload, old factory, return type and static mismatch");
    }

    private static void reject(Class<?> candidate) {
        try {
            PremiumFeature.dashboardInvoke(candidate);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("accepted incompatible factory " + candidate.getName());
    }

    static final class Factory {
        public Object invoke(Object value) { return value; }
        public Object invoke() { return null; }
    }

    static final class OldPurchaseFactory {
        public Object invoke() { return null; }
    }

    static final class WrongReturn {
        public boolean invoke(Object value) { return false; }
    }

    static final class StaticFactory {
        public static Object invoke(Object value) { return value; }
    }
}
