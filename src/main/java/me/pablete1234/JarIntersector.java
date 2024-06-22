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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static me.pablete1234.Jar.Search.BOTH;
import static me.pablete1234.Jar.Search.SUPER;

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

        Jar jar1 = new Jar(getClassNodes(inputJar1));
        Jar jar2 = new Jar(getClassNodes(inputJar2));

        Map<String, ClassNode> commonClasses = new HashMap<>(jar1.jar().size());
        jar1.jar().forEach((name, class1) -> {
            var class2 = jar2.get(name);
            if (class2 == null) {
                log.modified("Removing class: " + name);
                return;
            }
            commonClasses.put(name, getCommonClassNode(class1, jar1, class2, jar2));
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
    private static ClassNode getCommonClassNode(ClassNode cls1, Jar jar1, ClassNode cls2, Jar jar2) {
        log.modified("Inspecting class: " + cls1.name);
        var oldSuper = cls1.superName;
        var oldItfs = cls1.interfaces;
        boolean parent = resolveSuper(cls1, jar1, jar2.find(cls2, c -> List.of(c.superName), SUPER).collect(Collectors.toSet()));
        boolean itfs = resolveInterfaces(cls1, jar1, jar2.find(cls2, c -> c.interfaces, BOTH).collect(Collectors.toSet()));
        if ((itfs || parent) && cls1.signature != null) {
            String oldSign = cls1.signature;
            cls1.signature = rewriteSignature(cls1.signature, oldSuper, cls1.superName, oldItfs, cls1.interfaces);
            if (!oldSign.equals(cls1.signature))
                log.modified("  replaced signature " + oldSign + " with " + cls1.signature);
        }

        // Simple merges, keep only what's in both
        cls1.methods = intersect(cls1.methods, jar2.find(cls2, c -> c.methods, BOTH), MethodKey::new);
        cls1.fields = intersect(cls1.fields, jar2.find(cls2, c -> c.fields, BOTH), FieldKey::new);
        cls1.innerClasses = intersect(cls1.innerClasses, cls2.innerClasses.stream(), InnerClassKey::new);

        return cls1;
    }

    private static String rewriteSignature(String signature,
                                           String oldSuper, String newSuper,
                                           List<String> oldItfs, List<String> newItfs) {
        if (!oldSuper.equals(newSuper)) signature = signature.replaceFirst(regexEscape(oldSuper), newSuper);

        // Interfaces were just stripped, not replaced. Strip them from signature too
        Set<String> toStrip = new HashSet<>(oldItfs);
        if (toStrip.containsAll(oldItfs)) {
            newItfs.forEach(toStrip::remove);
            // Nothing to rewrite
            if (toStrip.isEmpty()) return signature;

            // Ends up being something like: L(com\/example\/A|com\/example\/B)(<.*>)?;
            var escaped = toStrip.stream().map(JarIntersector::regexEscape).collect(Collectors.joining("|"));
            return signature.replaceAll("L(" + escaped + ")(<.*>)?;","");
        } else { // Replaced signatures are not worth handling, just void the generic data.
            return null;
        }
    }

    private static String regexEscape(String className) {
        return className.replace("/", "\\/");
    }

    private static boolean resolveInterfaces(ClassNode cls1, Jar jar1, Set<String> bItf) {
        var newItf = cls1.interfaces.stream().flatMap(itf -> jar1.findInterfaces(itf, bItf)).distinct().toList();
        if (cls1.interfaces.equals(newItf)) return false;
        log.replacedInterfaces(cls1.interfaces, newItf);
        cls1.interfaces = newItf;
        return true;
    }

    private static boolean resolveSuper(ClassNode cls1, Jar jar1, Set<String> bSupers) {
        ClassNode current = cls1;
        while (!bSupers.contains(current.superName)) {
            ClassNode parent = jar1.get(current.superName);
            if (parent == null) break; // Parent not found in same jar. Could be extending a library, keep it.
            current = parent;
        }
        if (cls1.superName.equals(current.superName)) return false;
        log.replacedParent(cls1.superName, current.superName);
        cls1.superName = current.superName;
        return true;
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

}