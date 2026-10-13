# SPDX-License-Identifier: GPL-3.0-only
# Shared rules for the independently processed plugin and offline helper.
# Inputs, library jars, outputs and private build-only mapping paths are supplied
# by Gradle. These rules are part of the public corresponding source.

# Conservative symbol obfuscation only: do not remove code or change control flow.
-dontshrink
-dontoptimize
-dontusemixedcaseclassnames

# Keep package boundaries, including the plugin namespace advertised in plugin.yml.
# Class/member names can still be shortened unless a rule below preserves them.
-keeppackagenames com.vaultbackup.**

# Runtime annotations drive Bukkit event discovery. Record metadata and generic /
# nesting metadata remain internally consistent, and line numbers aid diagnosis.
# BootstrapMethods, StackMapTable and nest attributes are handled by ProGuard.
-keepattributes Exceptions,InnerClasses,EnclosingMethod,Signature,*Annotation*,Record,PermittedSubclasses,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep record component field/accessor names together. Record class names may be
# obfuscated. There is no reflective record serializer in the current protocol.
-keepclassmembers class com.vaultbackup.** extends java.lang.Record {
    <fields>;
    public <methods>;
}

# The JVM and java.lang.Enum can discover these methods reflectively.
-keepclassmembers enum com.vaultbackup.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static final ** *;
}

# Defensive preservation of Java serialization hooks if introduced later. This
# does NOT promise compatibility for persisted Java-serialized object graphs.
# Current backup metadata and restore jobs use explicit textual fields instead.
-keepclassmembers class com.vaultbackup.** implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    private void readObjectNoData();
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Deliberately no -ignorewarnings, global -dontwarn, string rewriting,
# -allowaccessmodification, -overloadaggressively or -dontpreverify.
