/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.junit.jupiter.patch;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;

/**
 * Build-time patcher that turns the stock Jupiter engine into the one used by Elasticsearch tests. It does two things.
 * <p>
 * <b>Test discovery.</b> It makes the engine discover {@code public void testXxx()} methods without {@code @Test}.
 * <p>
 * Jupiter decides whether a method is a test in a single place: {@code IsTestMethod#test(Method)}, which is used both to resolve
 * methods and to decide whether a class contains any tests at all. In the pinned engine version that method is a compiler generated
 * bridge whose body is {@code return super.test(method)}. The patcher turns it into
 * {@code return super.test(method) || TestNamedMethods.isPublicNonStaticTestNamedMethod(method)} by rewriting its {@code ireturn}.
 * <p>
 * <b>Engine id.</b> It changes {@code JupiterTestEngine#getId()} from {@code junit-jupiter} to {@link #ENGINE_ID}, so that this engine can
 * be told apart from the stock one: it is selected, excluded and reported under its own id.
 * <p>
 * The patch is deliberately brittle. It only applies to the exact classes it was written against (see {@link #EXPECTED_SHA256}) and it
 * fails the build, with the new hashes, when any of them changes. An engine upgrade therefore forces somebody to look at the
 * upstream change and confirm the patch is still correct, rather than silently producing an engine that no longer does what we expect.
 * <p>
 * Usage: {@code JupiterEnginePatcher <stock-engine-jar> <output-dir>}. Only the patched classes are written to the output directory.
 */
public final class JupiterEnginePatcher {

    private static final String PREDICATES_PACKAGE = "org/junit/jupiter/engine/discovery/predicates/";
    static final String IS_TEST_METHOD = PREDICATES_PACKAGE + "IsTestMethod.class";
    static final String IS_TESTABLE_METHOD = PREDICATES_PACKAGE + "IsTestableMethod.class";
    static final String JUPITER_TEST_ENGINE = "org/junit/jupiter/engine/JupiterTestEngine.class";

    /** The id of the stock engine, which {@code JupiterTestEngine#getId()} returns before it is patched. */
    static final String STOCK_ENGINE_ID = "junit-jupiter";
    /** The id of the patched engine. */
    static final String ENGINE_ID = "es-jupiter-engine";

    /**
     * SHA-256 of the classes of {@code org.junit.jupiter:junit-jupiter-engine} 6.1.3 that this patch was written for. When bumping the
     * JUnit version, review the upstream change to {@code IsTestableMethod}, {@code IsTestMethod} and {@code JupiterTestEngine}, and
     * update these hashes.
     */
    static final List<String[]> EXPECTED_SHA256 = List.of(
        new String[] { IS_TEST_METHOD, "0d1965735b6d41171a4b3ff3560910e590674d17c25b3d6f3df5b764b6412222" },
        new String[] { IS_TESTABLE_METHOD, "59aa529ab28286d3a9ab293fd6fb3b511c69b89d24a9bdb1315baf45871df9f3" },
        new String[] { JUPITER_TEST_ENGINE, "1845365c9be37a0ad71bc99b2ea20341f34237159fce39c607ea122d6c59436a" }
    );

    private static final ClassDesc IS_TESTABLE_METHOD_DESC = ClassDesc.ofInternalName(PREDICATES_PACKAGE + "IsTestableMethod");
    // Named rather than referenced, the patcher does not have the main source set on its classpath.
    private static final ClassDesc TEST_NAMED_METHODS_DESC = ClassDesc.of("org.elasticsearch.test.junit.jupiter.patch.TestNamedMethods");
    private static final MethodTypeDesc TEST_METHOD_DESC = MethodTypeDesc.of(CD_boolean, ClassDesc.of("java.lang.reflect.Method"));

    private JupiterEnginePatcher() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: JupiterEnginePatcher <stock-engine-jar> <output-dir>");
        }
        Path engineJar = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);

        byte[] patchedIsTestMethod;
        byte[] patchedEngine;
        try (ZipFile zip = new ZipFile(engineJar.toFile())) {
            verifyUnchanged(zip);
            patchedIsTestMethod = patchIsTestMethod(read(zip, IS_TEST_METHOD));
            patchedEngine = patchEngineId(read(zip, JUPITER_TEST_ENGINE));
        }

        write(outputDir.resolve(IS_TEST_METHOD), patchedIsTestMethod);
        write(outputDir.resolve(JUPITER_TEST_ENGINE), patchedEngine);
    }

    private static void write(Path output, byte[] bytes) throws IOException {
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }

    /** Fails if any class the patch depends on differs from the version the patch was written for. */
    static void verifyUnchanged(ZipFile zip) throws IOException {
        StringBuilder problems = new StringBuilder();
        for (String[] expected : EXPECTED_SHA256) {
            String actual = sha256(read(zip, expected[0]));
            if (actual.equals(expected[1]) == false) {
                problems.append("\n  ").append(expected[0]).append(": expected ").append(expected[1]).append(" but was ").append(actual);
            }
        }
        if (problems.isEmpty() == false) {
            throw new IllegalStateException(
                "The junit-jupiter-engine classes changed, so the test method discovery patch must be reviewed against the new version. "
                    + "If it is still correct, update EXPECTED_SHA256 in "
                    + JupiterEnginePatcher.class.getSimpleName()
                    + "."
                    + problems
            );
        }
    }

    /**
     * Rewrites the {@code ireturn} of {@code IsTestMethod#test(Method)} so that the result is
     * {@code stockResult || TestNamedMethods.isPublicNonStaticTestNamedMethod(method)}.
     */
    static byte[] patchIsTestMethod(byte[] original) {
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(original);

        MethodModel test = model.methods()
            .stream()
            .filter(m -> m.methodName().equalsString("test") && m.methodTypeSymbol().equals(TEST_METHOD_DESC))
            .reduce((a, b) -> {
                throw new IllegalStateException("IsTestMethod declares test(Method) more than once");
            })
            .orElseThrow(() -> new IllegalStateException("IsTestMethod does not declare test(Method) anymore"));

        // The method must be exactly `return super.test(method)`: an invokespecial of IsTestableMethod#test followed by a single ireturn.
        var code = test.code().orElseThrow(() -> new IllegalStateException("IsTestMethod#test(Method) has no code"));
        List<CodeElement> instructions = code.elementStream().filter(e -> e instanceof java.lang.classfile.Instruction).toList();
        boolean callsSuper = instructions.stream()
            .anyMatch(
                e -> e instanceof InvokeInstruction invoke
                    && invoke.opcode() == Opcode.INVOKESPECIAL
                    && invoke.owner().asSymbol().equals(IS_TESTABLE_METHOD_DESC)
                    && invoke.name().equalsString("test")
                    && invoke.typeSymbol().equals(TEST_METHOD_DESC)
            );
        long returns = instructions.stream().filter(e -> e instanceof ReturnInstruction).count();
        if (callsSuper == false || returns != 1 || instructions.size() != 4) {
            throw new IllegalStateException(
                "IsTestMethod#test(Method) is not the expected `return super.test(method)` bridge: " + instructions
            );
        }

        CodeTransform orWithNamedMethod = (builder, element) -> {
            if (element instanceof ReturnInstruction ret && ret.opcode() == Opcode.IRETURN) {
                // stack: [stockResult]
                Label done = builder.newLabel();
                builder.dup();
                builder.ifne(done);
                builder.pop();
                builder.aload(1);
                builder.invokestatic(TEST_NAMED_METHODS_DESC, "isPublicNonStaticTestNamedMethod", TEST_METHOD_DESC);
                builder.labelBinding(done);
                builder.ireturn();
            } else {
                builder.with(element);
            }
        };
        MethodTransform patchTest = MethodTransform.transformingCode(orWithNamedMethod);

        byte[] patched = classFile.transformClass(model, ClassTransform.transformingMethods(m -> m == test, patchTest));
        return patched;
    }

    /**
     * Replaces the constant returned by {@code JupiterTestEngine#getId()}. The method must be exactly {@code return "junit-jupiter"}.
     */
    static byte[] patchEngineId(byte[] original) {
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(original);

        MethodModel getId = model.methods()
            .stream()
            .filter(m -> m.methodName().equalsString("getId") && m.methodTypeSymbol().equals(MethodTypeDesc.of(CD_String)))
            .reduce((a, b) -> {
                throw new IllegalStateException("JupiterTestEngine declares getId() more than once");
            })
            .orElseThrow(() -> new IllegalStateException("JupiterTestEngine does not declare getId() anymore"));

        var code = getId.code().orElseThrow(() -> new IllegalStateException("JupiterTestEngine#getId() has no code"));
        List<CodeElement> instructions = code.elementStream().filter(e -> e instanceof java.lang.classfile.Instruction).toList();
        boolean returnsStockId = instructions.size() == 2
            && instructions.get(0) instanceof ConstantInstruction.LoadConstantInstruction load
            && STOCK_ENGINE_ID.equals(load.constantValue())
            && instructions.get(1) instanceof ReturnInstruction ret
            && ret.opcode() == Opcode.ARETURN;
        if (returnsStockId == false) {
            throw new IllegalStateException(
                "JupiterTestEngine#getId() is not the expected `return \"" + STOCK_ENGINE_ID + "\"`: " + instructions
            );
        }

        CodeTransform replaceId = (builder, element) -> {
            if (element instanceof ConstantInstruction.LoadConstantInstruction) {
                builder.ldc(ENGINE_ID);
            } else {
                builder.with(element);
            }
        };
        return classFile.transformClass(
            model,
            ClassTransform.transformingMethods(m -> m == getId, MethodTransform.transformingCode(replaceId))
        );
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IllegalStateException("The junit-jupiter-engine jar no longer contains " + name);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
