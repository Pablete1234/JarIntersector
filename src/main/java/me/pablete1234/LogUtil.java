package me.pablete1234;

import org.objectweb.asm.tree.ClassNode;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

class LogUtil {
    private final Level level;

    LogUtil(String level) {
        this.level = Level.of(level);
    }

    public void jar(Map<String, ClassNode> jar) {
        if (level.ordinal() < Level.FULL.ordinal()) return;
        System.out.println("Written all classes & methods: ");
        new TreeMap<>(jar).forEach((str, cls) ->
                cls.methods.forEach(m -> System.out.println(str + "#" + m.name + m.desc)));
    }

    public void replacedParent(String cls1, String cls2) {
        modified("  replaced parent " + cls1 + " with " + cls2);
    }

    public <T> void replacedInterfaces(Collection<T> a, Collection<T> b) {
        modified("  replaced interfaces " + a + " with " + b);
    }

    public void modified(String str) {
        if (level.ordinal() < Level.MODIFIED.ordinal()) return;
        System.out.println(str);
    }

    public boolean removing(boolean removing, Object obj) {
        if (removing && level.ordinal() >= Level.MODIFIED.ordinal())
            System.out.println("  removing " + obj);
        return removing;
    }

    enum Level {
        NONE,
        MODIFIED,
        FULL;

        static Level of(String level) {
            if (level == null) return MODIFIED;
            try {
                return Level.valueOf(level.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid log level '" + level + "', " +
                        "expected one of " + Arrays.toString(Level.values()) + ", defaulting to " + MODIFIED);
            }
            return MODIFIED;
        }
    }
}
