package io.github.crayonl.rebornowl;

/** 改名与换序不能污染原始路径；相同类型的歧义必须拒绝。 */
public final class AutoAdaptModelTest {
    public static final class RenamedUnit {
        public final String number;
        public final UnlockLessonsFeatureTest.Vector lessons;
        RenamedUnit(String number, UnlockLessonsFeatureTest.Vector lessons) {
            this.number = number;
            this.lessons = lessons;
        }
        public static RenamedUnit duplicate(RenamedUnit original, String number,
                UnlockLessonsFeatureTest.Vector lessons, int mask) {
            return new RenamedUnit((mask & 1) != 0 ? original.number : number, lessons);
        }
    }

    public static final class RenamedSection {
        public static final String TYPE = "静态同类型字段不参与定位";
        public final String identity;
        public final int position;
        public final UnlockLessonsFeatureTest.Vector chapters;
        public RenamedSection(UnlockLessonsFeatureTest.Vector chapters, String identity, int position) {
            this.chapters = chapters;
            this.identity = identity;
            this.position = position;
        }
    }

    public static final class AmbiguousFields {
        public String first, second;
        public int position;
        public UnlockLessonsFeatureTest.Vector chapters;
        public AmbiguousFields(int position, String identity, UnlockLessonsFeatureTest.Vector chapters) {}
    }

    public static final class AmbiguousConstructors {
        public String identity;
        public int position;
        public UnlockLessonsFeatureTest.Vector chapters;
        public AmbiguousConstructors(int position, String identity, UnlockLessonsFeatureTest.Vector chapters) {}
        public AmbiguousConstructors(String identity, int position, UnlockLessonsFeatureTest.Vector chapters) {}
    }

    public static void main(String[] args) throws Exception {
        verifyRenamed();
        reject(AmbiguousFields.class);
        reject(AmbiguousConstructors.class);
        System.out.println("AUTO_ADAPT_MODEL_PASS: 字段与副本方法改名、构造参数换序、原路径隔离、歧义拒绝");
    }

    private static void verifyRenamed() throws Exception {
        UnlockLessonsFeatureTest.Level locked = new UnlockLessonsFeatureTest.Level(
                UnlockLessonsFeatureTest.State.LOCKED, 3, new Object());
        RenamedUnit unit = new RenamedUnit("71", new UnlockLessonsFeatureTest.Vector(locked));
        RenamedSection original = new RenamedSection(new UnlockLessonsFeatureTest.Vector(unit), "section", 5);
        UnlockLessonsFeature.SessionCopy copier = new UnlockLessonsFeature.SessionCopy(
                UnlockLessonsFeatureTest.Level.class, UnlockLessonsFeatureTest.State.class);
        UnlockPopupData data = new UnlockPopupData(RenamedSection.class, RenamedUnit.class,
                UnlockLessonsFeatureTest.Vector.class);
        RenamedSection changed = (RenamedSection) data.copySection(original, copier);
        RenamedUnit copy = (RenamedUnit) changed.chapters.get(0);
        UnlockLessonsFeatureTest.Level level = (UnlockLessonsFeatureTest.Level) copy.lessons.get(0);
        check(changed != original && changed.identity == original.identity && changed.position == original.position);
        check(copy.number == unit.number && level.state == UnlockLessonsFeatureTest.State.ACTIVE);
        check(level.completed == locked.completed && level.data == locked.data);
        check(original.chapters.get(0) == unit && unit.lessons.get(0) == locked);
        check(locked.state == UnlockLessonsFeatureTest.State.LOCKED);
        check(data.copySection(changed, copier) == changed);
    }

    private static void reject(Class<?> section) throws Exception {
        try {
            new UnlockPopupData(section, RenamedUnit.class, UnlockLessonsFeatureTest.Vector.class);
        } catch (IllegalStateException | NoSuchMethodException expected) { return; }
        throw new AssertionError("接受了歧义结构：" + section);
    }

    private static void check(boolean valid) {
        if (!valid) { throw new AssertionError("改名与换序后路径副本或原始进度不正确"); }
    }
}
