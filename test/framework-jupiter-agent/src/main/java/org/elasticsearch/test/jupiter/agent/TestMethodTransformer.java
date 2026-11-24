/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.jupiter.agent;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adds {@code @Test} to the methods that are tests by their name, in the classes that extend a given base class, and in the
 * {@code @Nested} classes that are inside of them.
 * <p>
 * A class is only looked at when it is in one of the given packages. Whether it extends the base class is decided from the headers of
 * the class files of its ancestors, which are read as resources of the class loader that is loading the class and are cached. No class
 * is loaded for that and nothing is retransformed: an ancestor never has to have been loaded first, because the chain can always be
 * followed from the superclass name that each class file holds.
 * <p>
 * A {@code @Nested} class does not extend anything of interest, it is part of a test by being inside of one. So for those the chain
 * that is followed is the one of the enclosing classes, which each class file lists in its {@code InnerClasses} attribute. The
 * {@code @Nested} annotation itself is not added: Jupiter requires it, and it says something about the structure of the test that a
 * name does not.
 * <p>
 * Only the classes that turn out to be tests are parsed completely.
 */
final class TestMethodTransformer implements ClassFileTransformer {

    private static final String TEST = "Lorg/junit/jupiter/api/Test;";
    private static final String NESTED = "Lorg/junit/jupiter/api/Nested;";
    /** The annotations that make a method a test of another kind, which must not become a plain test as well. */
    private static final Set<String> OTHER_KINDS_OF_TESTS = Set.of(
        "Lorg/junit/jupiter/api/TestFactory;",
        "Lorg/junit/jupiter/api/TestTemplate;",
        "Lorg/junit/jupiter/api/RepeatedTest;",
        "Lorg/junit/jupiter/params/ParameterizedTest;"
    );

    private final String baseClass;
    private final List<String> packages;
    private final List<String> excluded;

    /** Whether a class, by internal name, is or extends the base class. */
    private final Map<String, Boolean> extendsBase = new ConcurrentHashMap<>();

    /** Whether a class, by internal name, is or extends the base class, or is a {@code @Nested} class inside of one that is a test. */
    private final Map<String, Boolean> testClasses = new ConcurrentHashMap<>();

    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong inScope = new AtomicLong();
    private final AtomicLong parsed = new AtomicLong();
    private final AtomicLong transformed = new AtomicLong();
    private final AtomicLong annotatedMethods = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();

    /**
     * @param baseClass the internal name of the class whose subclasses are transformed
     * @param packages  the internal prefixes of the packages that are looked at
     * @param excluded  the internal prefixes that are never looked at, even if they are in one of the packages
     */
    TestMethodTransformer(String baseClass, List<String> packages, List<String> excluded) {
        this.baseClass = baseClass;
        this.packages = packages;
        this.excluded = excluded;
    }

    @Override
    @SuppressForbidden(reason = "the agent has no logging, and a class that is not transformed would silently lose its tests")
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain domain, byte[] bytes) {
        if (className == null || loader == null || classBeingRedefined != null) {
            return null;
        }
        offered.incrementAndGet();
        if (isInScope(className) == false) {
            return null;
        }
        inScope.incrementAndGet();
        long start = System.nanoTime();
        try {
            return transformInScope(loader, bytes);
        } catch (Throwable t) {
            // a class that cannot be transformed has to be loaded all the same, but not silently: its tests would just not be found
            System.err.println("[es-jupiter-agent] could not transform " + className + ": " + t);
            return null;
        } finally {
            nanos.addAndGet(System.nanoTime() - start);
        }
    }

    private byte[] transformInScope(ClassLoader loader, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if ((reader.getAccess() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) {
            return null;
        }
        if (isTestClass(reader, loader) == false) {
            return null;
        }
        parsed.incrementAndGet();
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        int added = 0;
        for (MethodNode method : node.methods) {
            if (isNamedTestMethod(method) && isAnnotatedAsAnotherKindOfTest(method) == false && isAnnotatedAsTest(method) == false) {
                method.visitAnnotation(TEST, true).visitEnd();
                added++;
            }
        }
        if (added == 0) {
            return null;
        }
        transformed.incrementAndGet();
        annotatedMethods.addAndGet(added);
        // no code is changed, so the frames that are there stay valid and none have to be computed
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private boolean isTestClass(ClassReader reader, ClassLoader loader) {
        String superName = reader.getSuperName();
        if (superName != null && isOrExtendsBase(superName, loader)) {
            return true;
        }
        // only member classes have an enclosing class, and their names say so, which spares reading anything for all the others
        return reader.getClassName().indexOf('$') >= 0 && isNestedInTestClass(headerOf(reader), loader);
    }

    private boolean isNestedInTestClass(Header header, ClassLoader loader) {
        return header.nested && header.outer != null && isTestClassByName(header.outer, loader);
    }

    private boolean isTestClassByName(String className, ClassLoader loader) {
        if (className.equals(baseClass)) {
            return true;
        }
        if (isInScope(className) == false) {
            return false;
        }
        Boolean known = testClasses.get(className);
        if (known != null) {
            return known;
        }
        Header header = headerOf(className, loader);
        boolean result = header != null
            && ((header.superName != null && isOrExtendsBase(header.superName, loader)) || isNestedInTestClass(header, loader));
        testClasses.put(className, result);
        return result;
    }

    /** What is needed of a class to place it: its superclass, whether it is {@code @Nested}, and what it is nested in. */
    private record Header(String superName, boolean nested, String outer) {}

    private static Header headerOf(ClassReader reader) {
        String name = reader.getClassName();
        boolean[] nested = new boolean[1];
        String[] outer = new String[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (visible && descriptor.equals(NESTED)) {
                    nested[0] = true;
                }
                return null;
            }

            @Override
            public void visitInnerClass(String innerName, String outerName, String simpleName, int access) {
                if (innerName.equals(name)) {
                    outer[0] = outerName;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Header(reader.getSuperName(), nested[0], outer[0]);
    }

    private static Header headerOf(String className, ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(className + ".class")) {
            return in == null ? null : headerOf(new ClassReader(in));
        } catch (IOException e) {
            return null;
        }
    }

    /** The same rule as the {@code JUnit3MethodProvider} of the randomized runner: public, not static, no parameters, no result. */
    static boolean isNamedTestMethod(MethodNode method) {
        int access = method.access;
        return method.name.startsWith("test")
            && method.desc.equals("()V")
            && (access & Opcodes.ACC_PUBLIC) != 0
            && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
    }

    private static boolean isAnnotatedAsTest(MethodNode method) {
        return method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(TEST));
    }

    private static boolean isAnnotatedAsAnotherKindOfTest(MethodNode method) {
        if (method.visibleAnnotations == null) {
            return false;
        }
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (OTHER_KINDS_OF_TESTS.contains(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInScope(String className) {
        for (String prefix : excluded) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        for (String prefix : packages) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOrExtendsBase(String className, ClassLoader loader) {
        if (className.equals(baseClass)) {
            return true;
        }
        // an ancestor outside of the packages cannot extend the base class, so there is nothing to read
        if (isInScope(className) == false) {
            return false;
        }
        Boolean known = extendsBase.get(className);
        if (known != null) {
            return known;
        }
        String superName = superNameOf(className, loader);
        boolean result = superName != null && isOrExtendsBase(superName, loader);
        extendsBase.put(className, result);
        return result;
    }

    private static String superNameOf(String className, ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(className + ".class")) {
            return in == null ? null : new ClassReader(in).getSuperName();
        } catch (IOException e) {
            return null;
        }
    }

    /** Loads the classes that {@link #transform} uses, by transforming a class that has everything it looks for. */
    void warmUp() {
        String warmUp = TestMethodTransformer.class.getName().replace('.', '/') + "$WarmUp";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "testWarmUp", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = warmUp;
        node.superName = baseClass;
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        // the base class is its own superclass for the purpose of this, so no class file has to be found
        new TestMethodTransformer(baseClass, List.of(warmUp), List.of()).transformInScope(
            TestMethodTransformer.class.getClassLoader(),
            writer.toByteArray()
        );
    }

    String stats() {
        return "[es-jupiter-agent] offered="
            + offered
            + " in-scope="
            + inScope
            + " parsed="
            + parsed
            + " transformed="
            + transformed
            + " annotated-methods="
            + annotatedMethods
            + " time-in-transformer="
            + (nanos.get() / 1_000_000)
            + "ms";
    }
}
