package me.pablete1234;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JarIntersector {
    private static LogUtil log;

    public static void main(String[] args) throws IOException {
        if (args.length != 3 && args.length != 4) {
            System.out.println("Usage: java JarIntersector <inputJar1> <inputJar2> <outputJar> [logging]");
            return;
        }

        String inputJar1 = args[0];
        String inputJar2 = args[1];
        String outputJar = args[2];
        JarIntersector.log = new LogUtil(args.length == 4 ? args[3] : null);

        Map<String, ClassNode> classes1 = getClassNodes(inputJar1);
        Map<String, ClassNode> classes2 = getClassNodes(inputJar2);

        Map<String, ClassNode> commonClasses = new HashMap<>(classes1.size());
        classes1.forEach((name, class1) -> {
            var class2 = classes2.get(name);
            if (class2 == null) {
                log.modified("Removing class: " + name);
                return;
            }
            commonClasses.put(name, getCommonClassNode(class1, classes1, class2, classes2));
        });
        createCommonJar(outputJar, commonClasses);

        log.jar(commonClasses);
    }

    private static Map<String, ClassNode> getClassNodes(String jarPath) throws IOException {
        Map<String, ClassNode> classNodes = new HashMap<>(1024);

        try (JarInputStream jarInputStream = new JarInputStream(new FileInputStream(jarPath))) {
            JarEntry entry;
            while ((entry = jarInputStream.getNextJarEntry()) != null) {
                if (!entry.getName().endsWith(".class")) continue;
                ClassReader classReader = new ClassReader(jarInputStream);
                ClassNode classNode = new ClassNode();
                classReader.accept(classNode, 0);
                classNodes.put(classNode.name, classNode);
            }
        }
        return classNodes;
    }

    private static void createCommonJar(String outputJar, Map<String, ClassNode> commonClasses) throws IOException {
        try (JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(outputJar))) {
            for (Map.Entry<String, ClassNode> entry : commonClasses.entrySet()) {
                ClassWriter classWriter = new ClassWriter(0);
                entry.getValue().accept(classWriter);

                JarEntry jarEntry = new JarEntry(entry.getKey() + ".class");
                jarOutputStream.putNextEntry(jarEntry);
                jarOutputStream.write(classWriter.toByteArray());
                jarOutputStream.closeEntry();
            }
        }
    }

    // Main point, merging two classes
    private static ClassNode getCommonClassNode(ClassNode cls1, Map<String, ClassNode> jar1, ClassNode cls2, Map<String, ClassNode> jar2) {
        log.modified("Inspecting class: " + cls1.name);
        cls1.interfaces = resolveInterfaces(cls1, jar1, cls2, jar2);
        cls1.methods = intersect(cls1.methods, findRecursive(cls2, jar2, c -> c.methods), MethodKey::new);
        cls1.fields = intersect(cls1.fields, findRecursive(cls2, jar2, c -> c.fields), FieldKey::new);
        cls1.innerClasses = intersect(cls1.innerClasses, cls2.innerClasses.stream(), InnerClassKey::new);

        return cls1;
    }

    private static <T> Stream<T> findRecursive(ClassNode node, Map<String, ClassNode> jar, Function<ClassNode, Collection<T>> extractor) {
        if (node == null) return Stream.empty();
        return Stream.concat(
                extractor.apply(node).stream(),
                Stream.concat(Stream.of(node.superName), node.interfaces.stream()).map(jar::get).flatMap(cls -> findRecursive(cls, jar, extractor)));
    }

    private static <T, K> List<T> intersect(List<T> a, Stream<T> b, Function<T, K> key) {
        Set<K> bKeys = b.map(key).collect(Collectors.toSet());
        List<T> copy = new ArrayList<>(a);
        copy.removeIf(el -> {
            K k = key.apply(el);
            return log.removing(!bKeys.contains(k), k);
        });
        return copy;
    }

    private static List<String> resolveInterfaces(ClassNode cls1, Map<String, ClassNode> jar1, ClassNode cls2, Map<String, ClassNode> jar2) {
        Set<String> interfacesB = findRecursive(cls2, jar2, c -> c.interfaces).collect(Collectors.toSet());
        List<String> newItf = cls1.interfaces.stream().flatMap(itf -> getInterfaces(itf, jar1, interfacesB)).toList();
        log.replaced(cls1.interfaces, newItf);
        return newItf;
    }

    private static Stream<String> getInterfaces(String base, Map<String, ClassNode> jar, Set<String> allowed) {
        if (allowed.contains(base)) return Stream.of(base);
        ClassNode baseCls = jar.get(base);
        if (baseCls == null) return Stream.empty();
        return baseCls.interfaces.stream().flatMap(pItf -> getInterfaces(pItf, jar, allowed));
    }


    private static final int ACCESS_MODIFIERS = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED;

    record MethodKey(String name, String desc, int access) {
        public MethodKey(MethodNode method) {
            this(method.name, method.desc, method.access & ACCESS_MODIFIERS);
        }

        @Override
        public String toString() {
            return "Method[" + Modifier.toString(access) + " " + name + desc + "]";
        }
    }

    record FieldKey(String name, String desc, int access) {
        public FieldKey(FieldNode field) {
            this(field.name, field.desc, field.access & ACCESS_MODIFIERS);
        }

        @Override
        public String toString() {
            return "Field[" + Modifier.toString(access) + " "  + name + desc + "]";
        }
    }

    record InnerClassKey(String name, int access) {
        public InnerClassKey(InnerClassNode inner) {
            this(inner.name, inner.access & ACCESS_MODIFIERS);
        }
        @Override
        public String toString() {
            return "InnerClass[" + Modifier.toString(access) + " " + name + "]";
        }
    }

    static class LogUtil {
        private final Level level;

        private LogUtil(String level) {
            this.level = Level.of(level);
        }

        public void jar(Map<String, ClassNode> jar) {
            if (level.ordinal() < Level.FULL.ordinal()) return;
            System.out.println("Written all classes & methods: ");
            new TreeMap<>(jar).forEach((str, cls) ->
                    cls.methods.forEach(m -> System.out.println(str + "#" + m.name + m.desc)));
        }

        public <T> void replaced(Collection<T> a, Collection<T> b) {
            if (level.ordinal() < Level.MODIFIED.ordinal()) return;

            if (!Set.copyOf(a).equals(Set.copyOf(b)))
                log.modified("  replaced " + a + " with " + b);
        }

        public void modified(String str) {
            if (level.ordinal() < Level.MODIFIED.ordinal()) return;
            System.out.println(str);
        }

        public boolean removing(boolean removing, Object obj) {
            if (level.ordinal() >= Level.MODIFIED.ordinal())
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

}