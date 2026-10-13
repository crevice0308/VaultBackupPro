// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupDeletionRefusedException;
import java.util.function.Consumer;

/** Only typed expected refusals bypass the unexpected-failure diagnostic path. */
final class DeleteFeedback {
    private DeleteFeedback() {}

    static void report(Exception error, Consumer<BackupDeletionRefusedException.Reason> expected,
                       Consumer<Exception> unexpected) {
        if (error instanceof BackupDeletionRefusedException refusal) expected.accept(refusal.reason());
        else unexpected.accept(error);
    }

    static String messageKey(BackupDeletionRefusedException.Reason reason) {
        return switch (reason) {
            case RESTORE_PROTECTED -> "delete.protected";
        };
    }
}
