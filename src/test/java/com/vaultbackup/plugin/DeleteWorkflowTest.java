// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupDeletionRefusedException;
import com.vaultbackup.core.BackupEngine;
import com.vaultbackup.core.BackupOptions;
import com.vaultbackup.core.BackupRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Real disposable archive/confirmation tests, plus separately counted source-only GUI wiring checks. */
public final class DeleteWorkflowTest {
    private static int checks;
    private static int sourceChecks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static void sourceCheck(boolean value, String name) {
        if (!value) throw new AssertionError("source-only: " + name);
        sourceChecks++;
    }

    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempDirectory("vaultbackup-delete-workflow-");
        try {
            Path root = Files.createDirectory(fixture.resolve("server"));
            BackupEngine engine = new BackupEngine(BackupOptions.defaults(root, fixture.resolve("backups"), fixture.resolve("state")));
            Files.writeString(root.resolve("world.dat"), "disposable test data");
            BackupRecord first = engine.createBackup("first");
            Instant now = Instant.now();
            DeleteConfirmation warned = new DeleteConfirmation(first.id(), now.plusSeconds(30),
                    DeleteConfirmation.needsLastBackupWarning(engine.listBackups(), first.id()));
            check(warned.lastBackupWarning(), "one verified catalogue entry requests explicit warning");
            check(warned.matches(first.id(), now), "confirmation is scoped to selected ID and time");
            check(!warned.matches("different-id", now), "confirmation cannot change target");
            check(!warned.matches(first.id(), now.plusSeconds(30)), "confirmation expires at deadline");
            check(!warned.matches(first.id(), now.plusSeconds(31)), "expired confirmation stays rejected");
            check(!DeleteConfirmation.needsLastBackupWarning(List.of(), first.id()), "empty advisory list does not invent a backup");
            check(!DeleteConfirmation.needsLastBackupWarning(List.of(first), "different-id"), "warning selection checks the target");

            BackupRecord second = engine.createBackup("second");
            List<BackupRecord> oldCatalogue = engine.listBackups();
            DeleteConfirmation ordinary = new DeleteConfirmation(first.id(), now.plusSeconds(30),
                    DeleteConfirmation.needsLastBackupWarning(oldCatalogue, first.id()));
            check(!ordinary.lastBackupWarning(), "two-backup preview records no last-backup consent");
            engine.delete(second.id(), Set.of(), false);
            AtomicInteger expectedCount = new AtomicInteger();
            AtomicInteger diagnosticCount = new AtomicInteger();
            try {
                engine.delete(first.id(), Set.of(), ordinary.lastBackupWarning());
                throw new AssertionError("stale ordinary confirmation deleted the final recovery point");
            } catch (BackupDeletionRefusedException expected) {
                DeleteFeedback.report(expected, reason -> {
                    check(reason == BackupDeletionRefusedException.Reason.LAST_GOOD_CONFIRMATION_REQUIRED,
                            "stale preview routes to new consent request");
                    expectedCount.incrementAndGet();
                }, error -> diagnosticCount.incrementAndGet());
            }
            check(expectedCount.get() == 1 && diagnosticCount.get() == 0,
                    "expected consent refusal does not invoke scary diagnostic logger path");
            check(Files.exists(first.zipPath()) && Files.exists(first.metadataPath()), "refusal performs no deletion");
            DeleteConfirmation reconfirmed = new DeleteConfirmation(first.id(), Instant.now().plusSeconds(30), true);
            engine.delete(first.id(), Set.of(), reconfirmed.lastBackupWarning());
            check(engine.listBackups().isEmpty(), "fresh warned confirmation can delete last backup");

            BackupDeletionRefusedException reserved = new BackupDeletionRefusedException(
                    BackupDeletionRefusedException.Reason.RESTORE_PROTECTED, first.id());
            DeleteFeedback.report(reserved, reason -> {
                check(reason == BackupDeletionRefusedException.Reason.RESTORE_PROTECTED, "reserved refusal classification");
                expectedCount.incrementAndGet();
            }, error -> diagnosticCount.incrementAndGet());
            check(diagnosticCount.get() == 0, "restore-protected refusal also avoids unexpected failure logger");
            for (Exception error : List.of(new IOException("Backup is missing or corrupt"),
                    new IOException("Cannot delete the last verified good backup"),
                    new IOException("wrapper", reserved), new IllegalStateException("unexpected"))) {
                int before = diagnosticCount.get();
                DeleteFeedback.report(error, reason -> { throw new AssertionError("string/wrapped error falsely classified as expected"); },
                        actual -> { check(actual == error, "unexpected exception retains original diagnostics"); diagnosticCount.incrementAndGet(); });
                check(diagnosticCount.get() == before + 1, "unexpected failure invokes diagnostic logger path");
            }
            for (String language : List.of("ja_JP", "en_US")) {
                for (var reason : BackupDeletionRefusedException.Reason.values()) {
                    String message = Messages.text(language, DeleteFeedback.messageKey(reason));
                    check(!message.isBlank() && !message.contains("{0}"), "localized typed reason " + language + "/" + reason);
                    check(!message.equals(Messages.text(language, "job.failed", "delete")), "expected refusal is not generic failed text");
                }
                String warning = Messages.text(language, "delete.last.warning");
                check(warning.contains("0") && warning.contains(language.equals("ja_JP") ? "復元" : "recovery point"),
                        "last-backup warning explicitly explains zero recovery points " + language);
                check(Messages.text(language, "menu.delete.confirm.last").contains("0"), "GUI final action labels zero-recovery consent " + language);
                check(Messages.text(language, "delete.noneleft").contains("0") || language.equals("en_US")
                        && Messages.text(language, "delete.noneleft").startsWith("No verified backups"), "zero-backup result is clear " + language);
            }

            // These inspect wiring only. No Bukkit inventory/client execution is claimed.
            Path source = Path.of(args.length == 0 ? "src/main/java/com/vaultbackup/plugin" : args[0]);
            String menus = Files.readString(source.resolve("BackupMenus.java"));
            String plugin = Files.readString(source.resolve("VaultBackupPlugin.java"));
            sourceCheck(menus.contains("if (plugin.requestDelete(who,id)) deleteFinal(who,id,page);"), "GUI only advances after accepted preview");
            sourceCheck(plugin.contains("boolean requestDelete(CommandSender sender, String id)"), "request result is explicit");
            sourceCheck(menus.contains("plugin.cancelDelete(who,id); openInfo(who,id,page);"), "cancel clears pending confirmation");
            sourceCheck(menus.contains("warnLast ? \"menu.delete.confirm.last\" : \"menu.delete.confirm\""), "warned final action is visually distinct");
            sourceCheck(plugin.contains("engine.delete(id, restores.protectedBackupIds(), pending.lastBackupWarning())"), "confirmed warning passed to authoritative backend");
            sourceCheck(plugin.contains("if (sender instanceof Player p) menus.deleteFinal(p,id,0);"), "stale GUI confirmation returns to final warning");
            int refusalCallback = plugin.indexOf("DeleteFeedback.report(error, reason -> main(() -> {");
            int offlineGuard = plugin.indexOf("if (sender instanceof Player p && !p.isOnline()) return;", refusalCallback);
            int renewedConsent = plugin.indexOf("pendingDeletes.put(actor(sender), new DeleteConfirmation", refusalCallback);
            sourceCheck(offlineGuard > refusalCallback && offlineGuard < renewedConsent,
                    "offline player cannot receive renewed warned consent without seeing warning");
            sourceCheck(plugin.contains("if (!allowed(sender,\"delete\")) return false;") && plugin.contains("if (!allowed(sender,\"delete\")) return;"), "permission checks remain in both steps");
            sourceCheck(menus.contains("open.get(player.getUniqueId()) != m") && menus.contains("!plugin.allowed(player,action.permission)"), "stale menu and permission guards retained");
            sourceCheck(menus.contains("t(\"menu.delete.norecovery\"),t(\"menu.delete.pinned\")"), "zero-recovery and reservation warnings visible before confirmation");
            System.out.println("PASS DeleteWorkflowTest (" + checks + " executed checks; " + sourceChecks + " source-only GUI wiring checks; no real client)");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
