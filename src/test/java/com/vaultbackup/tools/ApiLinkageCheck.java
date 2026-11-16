// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.tools;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Static linkage check: every class, method and field the given classes reference must exist,
 * with the same descriptor and the same class/interface kind, on the given API classpath.
 * Usage: ApiLinkageCheck <label> <classes-dir-or-jar> <include-prefix[,prefix...]> <exclude-prefix[,...]|-> <api.jar>...
 */
public final class ApiLinkageCheck {
    private final Map<String, ClassModel> api = new HashMap<>();
    private final Map<String, ClassModel> own = new HashMap<>();
    private final Set<String> problems = new TreeSet<>();
    private int references;

    public static void main(String[] args) throws Exception {
        if (args.length < 5) throw new IllegalArgumentException("usage: label classes include exclude api...");
        ApiLinkageCheck check = new ApiLinkageCheck();
        for (int i = 4; i < args.length; i++) for (String part : args[i].split(java.io.File.pathSeparator)) if (!part.isBlank()) check.load(Path.of(part), check.api);
        check.load(Path.of(args[1]), check.own);
        List<String> include = List.of(args[2].split(","));
        List<String> exclude = args[3].equals("-") ? List.of() : List.of(args[3].split(","));
        int classes = 0;
        for (Map.Entry<String, ClassModel> entry : check.own.entrySet()) {
            String name = entry.getKey();
            if (include.stream().noneMatch(name::startsWith) || exclude.stream().anyMatch(name::startsWith)) continue;
            classes++;
            check.verify(name, entry.getValue());
        }
        if (classes == 0) throw new AssertionError(args[0] + ": no classes selected");
        if (!check.problems.isEmpty()) {
            check.problems.forEach(problem -> System.err.println("LINKAGE " + args[0] + ": " + problem));
            throw new AssertionError(args[0] + ": " + check.problems.size() + " unresolved API references");
        }
        System.out.println("PASS ApiLinkageCheck " + args[0] + " (" + classes + " classes, " + check.references + " external references resolved)");
    }

    private void load(Path path, Map<String, ClassModel> target) throws IOException {
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) put(target, Files.readAllBytes(file));
            }
        } else if (Files.isRegularFile(path)) {
            try (JarFile jar = new JarFile(path.toFile())) {
                for (ZipEntry entry : java.util.Collections.list(jar.entries())) {
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                    try (InputStream in = jar.getInputStream(entry)) { put(target, in.readAllBytes()); }
                }
            }
        }
    }

    private static void put(Map<String, ClassModel> target, byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        target.putIfAbsent(model.thisClass().asInternalName(), model);
    }

    private void verify(String owner, ClassModel model) {
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof ClassEntry type) {
                String name = type.asInternalName();
                while (name.startsWith("[")) name = name.substring(1);
                if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
                if (name.length() == 1) continue; // primitive array
                if (!external(name)) continue;
                references++;
                if (find(name) == null) problems.add(owner + " -> missing class " + name);
            } else if (entry instanceof MemberRefEntry member) {
                String target = member.owner().asInternalName();
                if (target.startsWith("[") || !external(target)) continue;
                references++;
                Resolved resolved = find(target);
                if (resolved == null) { problems.add(owner + " -> missing class " + target); continue; }
                String name = member.name().stringValue(), descriptor = member.type().stringValue();
                if (entry instanceof FieldRefEntry) {
                    if (!hasField(target, name, descriptor, new HashSet<>())) problems.add(owner + " -> missing field " + target + "." + name + ":" + descriptor);
                } else {
                    boolean interfaceRef = entry instanceof InterfaceMethodRefEntry;
                    if (resolved.isInterface() != interfaceRef) problems.add(owner + " -> " + target + " is " + (resolved.isInterface() ? "an interface" : "a class") + " but is referenced as " + (interfaceRef ? "an interface" : "a class") + " (" + name + descriptor + ")");
                    else if (!hasMethod(target, name, descriptor, new HashSet<>())) problems.add(owner + " -> missing method " + target + "." + name + descriptor);
                }
            }
        }
    }

    /** JDK and the plugin's own classes are resolved by the JVM/own jar; everything else must be on the API classpath. */
    private boolean external(String name) {
        return !own.containsKey(name) && !name.startsWith("java/") && !name.startsWith("javax/") && !name.startsWith("jdk/") && !name.startsWith("sun/");
    }

    private record Resolved(ClassModel model, Class<?> runtime) {
        boolean isInterface() { return model != null ? model.flags().has(AccessFlag.INTERFACE) : runtime.isInterface(); }
    }

    private Resolved find(String internalName) {
        ClassModel model = api.get(internalName);
        if (model == null) model = own.get(internalName);
        if (model != null) return new Resolved(model, null);
        if (internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/")) {
            try { return new Resolved(null, Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader())); }
            catch (ClassNotFoundException | LinkageError missing) { return null; }
        }
        return null;
    }

    private boolean hasMethod(String type, String name, String descriptor, Set<String> seen) {
        Deque<String> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            Resolved resolved = find(current);
            if (resolved == null) continue; // An unavailable supertype cannot prove absence; reported only if nothing matches.
            if (resolved.model() != null) {
                for (MethodModel method : resolved.model().methods())
                    if (method.methodName().equalsString(name) && method.methodType().equalsString(descriptor)) return true;
                resolved.model().superclass().ifPresent(parent -> queue.add(parent.asInternalName()));
                resolved.model().interfaces().forEach(parent -> queue.add(parent.asInternalName()));
            } else {
                for (java.lang.reflect.Method method : resolved.runtime().getDeclaredMethods())
                    if (method.getName().equals(name) && java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString().equals(descriptor)) return true;
                if (name.equals("<init>")) for (java.lang.reflect.Constructor<?> c : resolved.runtime().getDeclaredConstructors())
                    if (java.lang.invoke.MethodType.methodType(void.class, c.getParameterTypes()).toMethodDescriptorString().equals(descriptor)) return true;
                if (resolved.runtime().getSuperclass() != null) queue.add(resolved.runtime().getSuperclass().getName().replace('.', '/'));
                for (Class<?> parent : resolved.runtime().getInterfaces()) queue.add(parent.getName().replace('.', '/'));
                if (resolved.runtime().isInterface()) queue.add("java/lang/Object");
            }
        }
        return false;
    }

    private boolean hasField(String type, String name, String descriptor, Set<String> seen) {
        Deque<String> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            Resolved resolved = find(current);
            if (resolved == null) continue;
            if (resolved.model() != null) {
                for (FieldModel field : resolved.model().fields())
                    if (field.fieldName().equalsString(name) && field.fieldType().equalsString(descriptor)) return true;
                resolved.model().superclass().ifPresent(parent -> queue.add(parent.asInternalName()));
                resolved.model().interfaces().forEach(parent -> queue.add(parent.asInternalName()));
            } else {
                for (java.lang.reflect.Field field : resolved.runtime().getDeclaredFields())
                    if (field.getName().equals(name) && field.getType().descriptorString().equals(descriptor)) return true;
                if (resolved.runtime().getSuperclass() != null) queue.add(resolved.runtime().getSuperclass().getName().replace('.', '/'));
                for (Class<?> parent : resolved.runtime().getInterfaces()) queue.add(parent.getName().replace('.', '/'));
            }
        }
        return false;
    }
}
