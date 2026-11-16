// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.util.Properties;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A root lease belongs to the JVM, not to a plugin instance. There is deliberately
 * no close method: disabling, failed startup, and garbage collection must not
 * allow a waiting helper to replace a root that this JVM may still be using.
 *
 * A plugin reload creates a second plugin instance in the same JVM. The first
 * instance's lease is still in force (it is never released), so the second
 * instance inherits it instead of treating its own JVM as a competing server.
 */
final class RestoreRootLease {
    /** Another process holds the guard: a helper replacing this root right now, or a second server on it. */
    static final class HeldElsewhereException extends IOException {
        private static final long serialVersionUID = 1L;
        HeldElsewhereException(String message) { super(message); }
    }
    /** JVM-wide marker (system properties outlive plugin class loaders) naming the guards this JVM holds. */
    private static final String HELD_PROPERTY_PREFIX = "com.vaultbackup.rootguard.";
    final Path root, state;
    /** True when an earlier plugin instance of this JVM acquired the guard and this instance reuses it. */
    final boolean inherited;
    private final FileChannel channel;
    /** Null for an inherited lease: the lock object belongs to the earlier instance's retained channel. */
    private final FileLock lock;

    private RestoreRootLease(Path root, Path state, FileChannel channel, FileLock lock) {
        this.root=root;this.state=state;this.channel=channel;this.lock=lock;this.inherited=lock==null;
    }

    static RestoreRootLease acquire(Path serverRoot, Path stateDirectory)throws IOException {
        Path root=serverRoot.toAbsolutePath().normalize();
        if(root.getParent()==null)throw new IOException("A filesystem root cannot be restored");
        // Validate the parent, but do not require/create the root. It may be absent
        // during a partial rename, which must be classified only after the guard.
        Path realParent=RestoreFiles.directory(root.getParent(),false);
        root=realParent.resolve(root.getFileName());
        Path state=stateDirectory.toAbsolutePath().normalize();
        RestoreFiles.external(root,state,"State directory");
        state=RestoreFiles.directory(state,true);
        root=guardIdentity(root,state);
        Path guard=state.resolve(RestoreFiles.guardName(root));
        String heldProperty=HELD_PROPERTY_PREFIX+guard;
        FileChannel channel=RestoreFiles.openLockFile(guard);
        FileLock lock;
        try {lock=channel.tryLock();}
        catch(OverlappingFileLockException e) {
            // On some operating systems closing ANY channel for this file can
            // release this JVM's original OS lock. Keep the collision channel
            // open too, including across plugin class-loader reloads.
            retainChannelUntilExit(channel);
            // Only a lease this JVM recorded itself may be inherited. Any other
            // overlapping lock in this JVM is an unknown holder: fail closed.
            if(!"held".equals(System.getProperty(heldProperty)))
                throw new IOException("Server root guard is locked by an unknown holder inside this JVM. Restart the JVM.",e);
            RestoreRootLease lease=new RestoreRootLease(root,state,channel,null);
            Runtime.getRuntime().addShutdownHook(new Thread(lease::retainUntilProcessExit,"VaultBackupPro-inherited-guard"));
            return lease;
        } catch(IOException | RuntimeException e) {channel.close();throw e;}
        if(lock==null) {
            channel.close();throw new HeldElsewhereException("Offline restore or another server holds the root guard. Do not start this server.");
        }
        RestoreRootLease lease=new RestoreRootLease(root,state,channel,lock);
        // Runtime holds this hook strongly even if every plugin/coordinator
        // reference is discarded. The hook itself NEVER releases the lease.
        Runtime.getRuntime().addShutdownHook(new Thread(lease::retainUntilProcessExit,"VaultBackupPro-lifetime-guard"));
        // Recorded only after the lock and its retaining hook exist; never cleared.
        System.setProperty(heldProperty,"held");
        return lease;
    }

    private static Path guardIdentity(Path root,Path state)throws IOException {
        if(Files.isDirectory(root,RestoreFiles.NOFOLLOW) && !Files.isSymbolicLink(root)) {
            try {return RestoreFiles.directory(root,false);}
            catch(IOException renameRace) {
                if(Files.exists(root,RestoreFiles.NOFOLLOW))throw renameRace;
                // It disappeared between metadata checks; use the old binding below.
            }
        }
        // Existing roots use the same canonical spelling as v1.1 helpers. During
        // the first rename gap, the old active job retains that spelling even on
        // case-insensitive filesystems. This read is only a lock identity hint;
        // it never authenticates a job or permits startup with an absent root.
        try {
            Properties active=RestoreFiles.load(state.resolve("active-restore.properties"));
            String id=RestoreFiles.required(active,"jobId");
            if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return root;
            Properties job=RestoreFiles.load(state.resolve("restore-jobs").resolve(id).resolve("job.properties"));
            Path bound=Path.of(RestoreFiles.required(job,"serverRoot"));
            if(id.equals(RestoreFiles.required(job,"jobId"))
                    && RestoreFiles.required(active,"backupId").equals(RestoreFiles.required(job,"backupId"))
                    && Path.of(RestoreFiles.required(job,"stateDirectory")).equals(state)
                    && bound.isAbsolute() && bound.equals(bound.normalize()) && bound.equals(root)
                    && RestoreFiles.directory(bound.getParent(),false).equals(root.getParent()))return bound;
        } catch(IOException | RuntimeException unverifiedHint) { /* A missing/invalid hint never proves startup safety. */ }
        return root;
    }

    static void retainChannelUntilExit(FileChannel channel) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if(channel.isOpen()) { /* Never close a competing channel before OS teardown. */ }
        },"VaultBackupPro-overlapping-guard"));
    }

    private void retainUntilProcessExit() {
        if(channel.isOpen() && (lock==null || lock.isValid())) { /* OS process teardown releases the lease. */ }
    }
}
