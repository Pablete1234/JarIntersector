package me.pablete1234;

import org.objectweb.asm.tree.ClassNode;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public record Jar(Map<String, ClassNode> jar) {
    public ClassNode get(String name) {
        return jar.get(name);
    }

    public enum Search {
        SUPER(true, false),
        ITF(false, true),
        BOTH(true, true);

        private final boolean parent;
        private final boolean itf;

        Search(boolean parent, boolean itf) {
            this.parent = parent;
            this.itf = itf;
        }

        Stream<String> stream(ClassNode node) {
            if (!itf) return Stream.of(node.superName);
            if (!parent) return node.interfaces.stream();
            return Stream.concat(Stream.of(node.superName), node.interfaces.stream());
        }
    }

    /**
     * Recursively find class data
     * @param node The starting class
     * @param extractor What data to extract off of each class in the parent tree
     * @param search Search for direct parents, interfaces, or both?
     * @return Stream of all extracted data
     * @param <T> Type of data to extract
     */
    public <T> Stream<T> find(ClassNode node, Function<ClassNode, Collection<T>> extractor, Search search) {
        if (node == null) return Stream.empty();
        return Stream.concat(
                extractor.apply(node).stream(),
                search.stream(node).map(jar::get).flatMap(cls -> find(cls, extractor, search)));
    }

    /**
     * Find alternative interfaces that would be a replacement for base
     * @param base The base interface to search the tree up from
     * @param filter Filter for what interfaces are allowed
     * @return A stream of interfaces that would replace base strictly with classes that pass the filter
     */
    public Stream<String> findInterfaces(String base, Set<String> filter) {
        if (filter.contains(base)) return Stream.of(base);
        ClassNode baseCls = get(base);
        if (baseCls == null) return Stream.empty();
        return baseCls.interfaces.stream().flatMap(pItf -> findInterfaces(pItf, filter));
    }

}
