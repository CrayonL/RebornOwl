package com.owl.auxiliary;

public final class UnlockLessonsFeatureTest {
    public static void main(String[] args) {
        if (UnlockLessonsFeature.hasBooleanState(StartupTask.class)) {
            throw new AssertionError("dependency holder must not be reported as a working unlock target");
        }
        if (!UnlockLessonsFeature.hasBooleanState(State.class)) {
            throw new AssertionError("boolean instance state not detected");
        }
        System.out.println("PASS: ineffective startup target rejected; instance state recognized");
    }

    static final class StartupTask {
        static boolean global;
        final Object dependency;
        StartupTask(Object dependency) { this.dependency = dependency; }
    }

    static final class State {
        boolean enabled;
    }
}
