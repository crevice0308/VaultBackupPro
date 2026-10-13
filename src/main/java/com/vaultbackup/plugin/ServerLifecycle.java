// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;

/**
 * Narrow, fail-closed adapter for the supported 26.2 and 26.3 server families.
 * Spigot has neither Paper's World.save(boolean) nor Server.isStopping().
 * Public CraftBukkit/Minecraft server methods are resolved at runtime rather than
 * linking this plugin against a particular implementation or shaded API.
 */
final class ServerLifecycle {
    private static volatile String language = Messages.DEFAULT_LANGUAGE;

    private ServerLifecycle() {}

    static void configureLanguage(String selected) {
        language = Messages.supported(selected) ? selected : Messages.DEFAULT_LANGUAGE;
    }

    private static String message(String key, Object... arguments) {
        return Messages.text(language, "lifecycle." + key, arguments);
    }

    /** Check the required adapter without saving data or changing lifecycle state. */
    static void verifyCompatibility() throws IOException {
        Object handle = serverHandle();
        requireBooleanMethod(handle, "saveEverything", boolean.class, boolean.class, boolean.class);
        requireBooleanMethod(handle, "hasStopped");
    }

    /**
     * Synchronously run the same save operation as vanilla save-all flush:
     * suppress routine logging, flush to storage, and force saving with autosave
     * disabled. This includes player data. A false result or invocation failure
     * aborts the backup; no command-dispatch success flag is treated as a save.
     * This is still a best-effort live save, not an atomic filesystem snapshot.
     */
    static void flushAll() throws IOException {
        if (!Bukkit.isPrimaryThread()) {
            throw new IOException(message("main-thread"));
        }
        Object handle = serverHandle();
        Method save = requireBooleanMethod(handle, "saveEverything", boolean.class, boolean.class, boolean.class);
        if (!Boolean.TRUE.equals(invoke(handle, save, true, true, true))) {
            throw new IOException(message("save-failed"));
        }
    }

    /**
     * True only when CraftBukkit has entered its server shutdown path. The flag
     * is set before plugins are disabled; ordinary plugin disable/reload leaves
     * it false. This alone never authorizes restoration while the JVM is alive.
     * Unknown adapters fail closed, allowing the caller to cancel the pending job.
     */
    static boolean isStopping() {
        try {
            Object handle = serverHandle();
            return Boolean.TRUE.equals(invoke(handle, requireBooleanMethod(handle, "hasStopped")));
        } catch (IOException | RuntimeException | LinkageError error) {
            return false;
        }
    }

    private static Object serverHandle() throws IOException {
        Object server = Bukkit.getServer();
        if (server == null) throw new IOException(message("no-server"));
        try {
            Object handle = invoke(server, server.getClass().getMethod("getServer"));
            if (handle == null) throw new IOException(message("no-handle"));
            return handle;
        } catch (NoSuchMethodException | SecurityException error) {
            throw new IOException(message("unsupported"), error);
        }
    }

    private static Method requireBooleanMethod(Object target, String name, Class<?>... arguments) throws IOException {
        try {
            Method method = target.getClass().getMethod(name, arguments);
            if (method.getReturnType() != boolean.class) {
                throw new IOException(message("signature", name));
            }
            return method;
        } catch (NoSuchMethodException | SecurityException error) {
            throw new IOException(message("missing-method", name), error);
        }
    }

    private static Object invoke(Object target, Method method, Object... arguments) throws IOException {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException | IllegalArgumentException | SecurityException error) {
            throw new IOException(message("inaccessible-method", method.getName()), error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof Error fatal) throw fatal;
            throw new IOException(message("method-failed", method.getName()), cause);
        }
    }
}
