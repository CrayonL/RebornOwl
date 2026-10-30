package com.owl.auxiliary;

/** 解锁操作副本不能改变首页的当前学习位置或真实进度。 */
public final class UnlockLessonsFeatureTest {
    public enum State { LOCKED, UNLOCKED, ACTIVE, PASSED, LEGENDARY, UNIT_TEST }

    static final class Level {
        final State state;
        final int completed;
        final Object data;

        Level(State state, int completed, Object data) {
            this.state = state;
            this.completed = completed;
            this.data = data;
        }

        public static Level copy(Level original, State state, int completed, Object data, int mask) {
            return new Level(state, (mask & 4) != 0 ? original.completed : completed,
                    (mask & 16) != 0 ? original.data : data);
        }
    }

    public static void main(String[] args) throws Exception {
        UnlockLessonsFeature.SessionCopy copier =
                new UnlockLessonsFeature.SessionCopy(Level.class, State.class);
        for (State state : State.values()) { verifyState(copier, state); }
        verifyPathAnchor(copier);
        verifyNode(copier);
        verifySession(copier);
        verifyPopup(copier);
        expectRejected(copier, null);
        expectRejected(copier, new Object());
        expectRejected(copier, new Level(null, 0, new Object()));
        System.out.println("PASS UnlockLessonsFeature: session copies unlock; original path anchor, "
                + "progress and other states preserved; invalid targets rejected");
    }

    public static final class Vector extends java.util.AbstractList<Object> {
        private final Object[] items;
        Vector(Object... items) { this.items = items; }
        public Object get(int index) { return items[index]; }
        public int size() { return items.length; }
        public Vector with(int index, Object value) {
            Object[] result = items.clone();
            result[index] = value;
            return new Vector(result);
        }
    }

    public static final class Unit {
        public final String a;
        public final Vector b;
        Unit(String index, Vector levels) { a = index; b = levels; }
        public static Unit a(Unit original, String index, Vector levels, int mask) {
            return new Unit((mask & 1) != 0 ? original.a : index, levels);
        }
    }

    public static final class Section {
        public final String a;
        public final int b;
        public final Vector c;
        public Section(int index, String id, Vector units) { a = id; b = index; c = units; }
    }

    private static void verifyPopup(UnlockLessonsFeature.SessionCopy copier) throws Exception {
        UnlockPopupData data = new UnlockPopupData(Section.class, Unit.class, Vector.class);
        Level passed = new Level(State.PASSED, 6, new Object());
        Level active = new Level(State.ACTIVE, 2, new Object());
        Level locked = new Level(State.LOCKED, 0, new Object());
        Unit done = new Unit("70", new Vector(passed));
        Unit current = new Unit("71", new Vector(active, locked));
        Section original = new Section(5, "section", new Vector(done, current));
        Section result = (Section) data.copySection(original, copier);
        require(result != original && result.a == original.a && result.b == original.b,
                "只替换弹层路径副本，保留阶段标识");
        require(result.c.get(0) == done, "已完成单元不应被复制");
        Unit copy = (Unit) result.c.get(1);
        require(copy.a == current.a && copy.b.get(0) == active, "单元编号与当前关卡保持");
        require(((Level) copy.b.get(1)).state == State.ACTIVE, "弹层的后续关卡未解锁");
        require(current.b.get(1) == locked && locked.state == State.LOCKED,
                "原始路径或锁定状态被污染");
        require(data.copySection(result, copier) == result, "无需更改时应复用弹层副本");
        Section empty = new Section(0, "empty", new Vector());
        require(data.copySection(empty, copier) == empty, "空阶段应保持原值");
    }

    private static void verifySession(UnlockLessonsFeature.SessionCopy copier) throws Exception {
        java.lang.reflect.Method rewrite = UnlockLessonsFeature.class.getDeclaredMethod(
                "rewriteSession", Object[].class, UnlockLessonsFeature.SessionCopy.class);
        for (int length : new int[]{9, 11}) {
            Object[] args = new Object[length];
            for (int index = 0; index < length; index++) { args[index] = new Object(); }
            Level original = new Level(State.LOCKED, 2, new Object());
            args[0] = original;
            Object[] result = (Object[]) rewrite.invoke(null, args, copier);
            require(((Level) result[0]).state == State.ACTIVE, "点击会话仍被锁定");
            require(original.state == State.LOCKED && args[0] == original, "不得修改原始路径");
            for (int index = 1; index < length; index++) {
                require(result[index] == args[index], "不得更改会话参数或当前学习位置");
            }
            require(rewrite.invoke(null, result, copier) == result, "委托构造不可重复创建副本");
        }
    }

    private static void verifyNode(UnlockLessonsFeature.SessionCopy copier) throws Exception {
        int length = 23;
        for (State state : State.values()) {
            Object[] args = new Object[length];
            for (int index = 0; index < length; index++) { args[index] = new Object(); }
            Level original = new Level(state, 2, new Object());
            args[2] = original;
            Object[] result = UnlockLessonsFeature.rewriteNode(args, copier);
            boolean locked = state == State.LOCKED || state == State.UNLOCKED;
            require(original.state == state && args[2] == original, "不能改写原始关卡或入参");
            require(((Level) result[2]).state == (locked ? State.ACTIVE : state), "显示副本状态错误");
            for (int index = 0; index < length; index++) {
                if (index != 2) {
                    require(result[index] == args[index], "当前关卡定位、点击操作及其他参数必须保持");
                }
            }
            require(locked || result == args, "已可学和已完成节点不能变化");
        }
    }

    private static void verifyState(UnlockLessonsFeature.SessionCopy copier, State state)
            throws Exception {
        Level original = new Level(state, 2, new Object());
        Level result = (Level) copier.copyLevel(original);
        boolean locked = state == State.LOCKED || state == State.UNLOCKED;
        require(result.state == (locked ? State.ACTIVE : state), "操作副本状态错误：" + state);
        require(original.state == state, "原始路径状态被修改");
        require(result.completed == 2 && result.data == original.data, "课程进度或数据被修改");
        require((result != original) == locked, "只为锁定关卡创建副本");
        require(copier.copyLevel(result) == result, "重复处理应复用已可学副本");
    }

    private static void verifyPathAnchor(UnlockLessonsFeature.SessionCopy copier) throws Exception {
        Level[] path = {new Level(State.PASSED, 6, new Object()),
                new Level(State.ACTIVE, 2, new Object()), new Level(State.LOCKED, 0, new Object()),
                new Level(State.LOCKED, 0, new Object())};
        for (Level level : path) { copier.copyLevel(level); }
        int current = -1;
        for (int index = 0; index < path.length; index++) {
            if (path[index].state == State.ACTIVE) { current = index; }
        }
        require(current == 1, "解锁不得把当前关卡从 1 移到末尾 " + current);
    }

    private static void expectRejected(UnlockLessonsFeature.SessionCopy copier, Object value)
            throws Exception {
        try {
            copier.copyLevel(value);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("接受了无效关卡参数");
    }

    private static void require(boolean valid, String message) {
        if (!valid) { throw new AssertionError(message); }
    }
}
