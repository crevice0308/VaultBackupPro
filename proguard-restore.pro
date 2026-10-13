# SPDX-License-Identifier: GPL-3.0-only
-include proguard-common.pro

# The manifest's Main-Class remains valid for java -jar. No Bukkit/Spigot API or
# ProGuard runtime is part of this standalone executable.
-keep public class com.vaultbackup.restore.RestoreMain {
    public static void main(java.lang.String[]);
}
