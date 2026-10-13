// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact supported Minecraft minors across Spigot and Paper's Bukkit version formats. */
final class ServerVersions {
    private static final Pattern SUPPORTED = Pattern.compile("^(26\\.[23])(?:-R0\\.1(?:-SNAPSHOT)?|\\.build\\.[0-9]+(?:-[A-Za-z0-9._-]+)?)?$");
    private ServerVersions() {}
    static String minecraftVersion(String bukkitVersion) {
        Matcher match = SUPPORTED.matcher(bukkitVersion == null ? "" : bukkitVersion);
        return match.matches() ? match.group(1) : "";
    }
}
