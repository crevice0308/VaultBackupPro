// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.io.IOException;
import java.util.Objects;

/** Expected restore-protection refusal, distinct from corrupt, missing or unreadable data. */
public final class BackupDeletionRefusedException extends IOException {
    public enum Reason { RESTORE_PROTECTED }
    private final Reason reason;
    private final String backupId;

    public BackupDeletionRefusedException(Reason reason, String backupId) {
        super(switch (Objects.requireNonNull(reason)) {
            case RESTORE_PROTECTED -> "Backup is reserved for restore: " + backupId;
        });
        this.reason = reason;
        this.backupId = Objects.requireNonNull(backupId);
    }

    public Reason reason() { return reason; }
    public String backupId() { return backupId; }
}
