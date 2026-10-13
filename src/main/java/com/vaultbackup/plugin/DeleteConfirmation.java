// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupRecord;
import java.time.Instant;
import java.util.List;

/** Actor-scoped by the plugin; the warning records consent, not an authoritative count. */
record DeleteConfirmation(String id, Instant expires, boolean lastBackupWarning) {
    boolean matches(String requestedId, Instant now) {
        return id.equals(requestedId) && expires.isAfter(now);
    }

    static boolean needsLastBackupWarning(List<BackupRecord> verifiedCatalogue, String id) {
        // Advisory only: the engine re-verifies under lock and requires reconfirmation
        // if an ordinary confirmation becomes a last-backup deletion in the meantime.
        return verifiedCatalogue.size() == 1 && verifiedCatalogue.getFirst().id().equals(id);
    }
}
