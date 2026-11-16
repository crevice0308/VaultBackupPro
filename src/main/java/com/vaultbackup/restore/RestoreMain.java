// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;

/** Standalone Java 25 helper. No Bukkit, network, restart, or shell dependency. */
public final class RestoreMain {
    private record Job(Path file, Path dir, Properties p, Path root, Path state, Path archive,
                       Path stage, Path rollback, Path failed) {}
    private RestoreMain() {}
    public static void main(String[] args) {
        // The plugin starts this program with its output going to helper.log. A JVM without a UTF-8
        // locale would write the Japanese messages there as question marks. A terminal keeps its own encoding.
        java.io.Console console=System.console();
        if(console==null || !console.isTerminal()) {
            System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),true,java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),true,java.nio.charset.StandardCharsets.UTF_8));
        }
        int code;
        try {code=run(args);}catch(Exception e){System.err.println("復元を拒否、または失敗しました / RESTORE REFUSED OR FAILED: "+RestoreFiles.reason(e));e.printStackTrace(System.err);code=2;}
        if(code!=0)System.exit(code);
    }
    public static int run(String[] args)throws IOException {
        Path jobFile=null;boolean manual=false,stopped=false,recover=false,acceptRollback=false;
        for(int i=0;i<args.length;i++) {
            switch(args[i]) {
                case "--job" -> {if(++i>=args.length)throw new IOException("--job requires a path");jobFile=Path.of(args[i]);}
                case "--manual" -> manual=true;
                case "--i-confirm-server-stopped" -> stopped=true;
                case "--recover" -> {manual=true;recover=true;}
                case "--accept-rollback-directory" -> acceptRollback=true;
                default -> throw new IOException("Unknown argument: "+args[i]);
            }
        }
        if(jobFile==null)throw new IOException("Usage: java -jar vaultbackup-restore.jar --job /absolute/job.properties [--manual --i-confirm-server-stopped | --recover --i-confirm-server-stopped [--accept-rollback-directory]]");
        if(manual!=stopped)throw new IOException("Manual execution/recovery requires both --manual (or --recover) and --i-confirm-server-stopped");
        if(acceptRollback && !recover)throw new IOException("--accept-rollback-directory is only meaningful with --recover");
        ignoreTerminalSignals(manual);
        Job job=validateJob(jobFile,recover);
        try(FileChannel helperChannel=RestoreFiles.openLockFile(job.dir.resolve("helper.lock"))) {
            FileLock helperLock;
            try{helperLock=helperChannel.tryLock();}catch(OverlappingFileLockException e){throw new IOException("This restore helper is already running",e);}
            if(helperLock==null)throw new IOException("This restore helper is already running");
            try(helperLock) {
                boolean childOfServer=false;
                try {
                    // helper.lock prevents duplicate helpers. job.lock instead linearizes state changes
                    // with the coordinator and is deliberately released for the entire waiting period.
                    try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                        if(cancelled(job))return 3;
                        RestoreJobState.requireActive(job.state,job.p);
                        RestoreJobState.recordProcess(job.dir,job.p,ProcessHandle.current());
                        if(recover){RestoreFiles.requireDeadParent(job.p);try(Guard guard=guard(job)){recover(job,acceptRollback);return 0;}}
                        String status=RestoreFiles.status(job.dir);
                        if(Files.exists(job.dir.resolve("recovery-receipt.properties"),RestoreFiles.NOFOLLOW)) {
                            RestoreJobState.verifiedRecovery(job.dir,job.p);
                            throw new IOException("This job already completed original-root recovery and cannot restore again. Start the server to reconcile its completed-recovery receipt.");
                        }
                        if(status.equals("SUCCESS")){System.out.println(RestoreFiles.message(job.p,"Journal reports SUCCESS. No changes made; validate the receipt before startup. Rollback: ","記録は SUCCESS です。変更していません。起動前に完了証明を検証してください。元データ: ")+job.rollback);return 0;}
                        if(!Set.of("COMMITTED","READY","WAITING_FOR_MANUAL_STOP","EXPIRED","FAILED_BEFORE_SWAP").contains(status))throw new IOException("Job journal is "+status+"; inspect it and use --recover while stopped. Never resume an uncertain swap.");
                        if(!manual) {
                            if(!RestoreFiles.required(job.p,"formatVersion").equals("2") || !"MANUAL_NORMAL_STOP".equals(job.p.getProperty("shutdownPolicy")))throw new IOException("Old or unknown job protocol cannot wait automatically; review it using explicit stopped-server manual mode");
                            childOfServer=validateLivingParent(job.p);
                            status(job,"WAITING_FOR_MANUAL_STOP","Reserved. Waiting without a time limit for your normal server stop and actual JVM exit. No restart will be performed.");
                        } else RestoreFiles.requireDeadParent(job.p);
                    }
                    MarkerWatch marker=new MarkerWatch(job.dir.resolve("normal-stop.properties"));
                    if(!manual && !waitForExit(job,childOfServer,marker))return 3;
                    // Taken as soon as the exit is seen: a PLUGIN_DISABLED stop marker is only valid for a prompt exit.
                    Instant exitObserved=Instant.now();
                    Duration sinceMarker=marker.sinceFirstSeen();
                    if(!manual && !awaitCollected(job))return 3;
                    try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                        if(cancelled(job))return 3;
                        RestoreJobState.requireActive(job.state,job.p);
                        RestoreFiles.requireDeadParent(job.p);
                        if(!manual)RestoreJobState.requireNormalShutdown(job.dir,job.p,exitObserved,sinceMarker);
                        try(Guard guard=guard(job)) {
                            RestoreFiles.requireDeadParent(job.p);
                            status(job,"PARENT_EXITED","Server shutdown path and recorded JVM exit confirmed; beginning offline application");
                            // This gate remains held through staging, application, verification, and
                            // rollback. A late cancel can fail, but can NEVER falsely acknowledge.
                            execute(job);
                        }
                    }
                } catch(IOException e) {
                    try(RestoreJobState.Gate gate=RestoreJobState.lock(job.dir,Duration.ofSeconds(2))) {
                        if(gate!=null && !RestoreJobState.hasCancellation(job.dir) && RestoreJobState.waiting(RestoreFiles.status(job.dir)))
                            status(job,"FAILED_BEFORE_SWAP",RestoreFiles.reason(e));
                    }catch(Exception journalError){e.addSuppressed(journalError);}
                    throw e;
                }
            }
        }
        return 0;
    }
    /** Only the standalone helper owns stdout; plugin journal writes stay silent. */
    private static void status(Job job,String status,String message)throws IOException {
        RestoreFiles.status(job.dir,status,message);
        System.out.println("[VaultBackupPro restore] "+status+": "+RestoreFiles.load(job.dir.resolve("status.properties")).getProperty("message",""));
    }
    private static boolean cancelled(Job job)throws IOException {
        if(!RestoreJobState.hasCancellation(job.dir))return false;
        // An invalid/symlink/partial flag is an error, never permission to continue.
        RestoreJobState.acknowledgeCancellation(job.dir,job.p,"HELPER");
        System.out.println("[VaultBackupPro restore] CANCELLED: "+RestoreFiles.load(job.dir.resolve("status.properties")).getProperty("message",""));
        return true;
    }
    /**
     * A helper started by a server shares its terminal and its process group. When that terminal goes
     * away with the server (a screen or tmux session whose only program was the server), the kernel
     * hangs up everything in it, and Ctrl+C on the console reaches the helper as well. Neither may
     * end a helper that is waiting for exactly that server exit, or is swapping directories.
     * A helper run by hand keeps Ctrl+C: the operator is at the keyboard.
     */
    private static void ignoreTerminalSignals(boolean manual) {
        for(String name:manual?List.of("HUP"):List.of("HUP","INT")) {
            try {
                // No supported API offers this. The classes are looked up by name so that a runtime without them simply behaves as before.
                Class<?> signal=Class.forName("sun.misc.Signal"),handler=Class.forName("sun.misc.SignalHandler");
                signal.getMethod("handle",signal,handler).invoke(null,signal.getConstructor(String.class).newInstance(name),handler.getField("SIG_IGN").get(null));
            } catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Not on this platform or runtime. */ }
        }
    }
    /** When the waiting helper first saw the stop marker as it is now, by a clock that cannot be set. */
    private static final class MarkerWatch {
        private final Path file;
        private String seen;
        private long seenNanos;
        MarkerWatch(Path file){this.file=file;}
        void look() {
            String now;
            try {
                now=Files.exists(file,RestoreFiles.NOFOLLOW)?RestoreFiles.fileIdentity(file)+"@"+Files.getLastModifiedTime(file,RestoreFiles.NOFOLLOW).toMillis():null;
            } catch(IOException replacedThisInstant) {return;}
            if(now!=null && !now.equals(seen))seenNanos=System.nanoTime();
            seen=now;
        }
        /** Null when there is no marker. */
        Duration sinceFirstSeen() {look();return seen==null?null:Duration.ofNanos(System.nanoTime()-seenNanos);}
    }
    /** For recovery the backup storage is not needed and may be absent (an unmounted disk); only the paths are bound. */
    private static Job validateJob(Path file,boolean recover)throws IOException {
        file=file.toAbsolutePath().normalize();Properties p=RestoreFiles.load(file);
        if(!Set.of("1","2").contains(RestoreFiles.required(p,"formatVersion")))throw new IOException("Unknown restore job format");
        String id=RestoreFiles.required(p,"jobId");
        if(!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("Invalid job identifier");
        Path state=RestoreFiles.directory(Path.of(RestoreFiles.required(p,"stateDirectory")),false);
        Path expected=state.resolve("restore-jobs").resolve(id).resolve("job.properties");
        if(!file.equals(expected))throw new IOException("Job location does not match its bound state directory");
        RestoreFiles.directory(file.getParent(),false);
        Path root=Path.of(RestoreFiles.required(p,"serverRoot")).toAbsolutePath().normalize();
        if(root.getParent()==null)throw new IOException("Cannot replace filesystem root");
        RestoreFiles.directory(root.getParent(),false);
        if(Files.exists(root,RestoreFiles.NOFOLLOW))RestoreFiles.directory(root,false);
        Path configuredBackups=Path.of(RestoreFiles.required(p,"backupDirectory"));
        Path backups=recover?configuredBackups.toAbsolutePath().normalize():RestoreFiles.directory(configuredBackups,false);
        RestoreFiles.external(root,state,"State directory");RestoreFiles.external(root,backups,"Backup directory");
        Path archive=Path.of(RestoreFiles.required(p,"archive")).toAbsolutePath().normalize();
        if(!archive.startsWith(backups))throw new IOException("Archive outside bound backup directory");
        Path stage=boundSibling(p,"stage",root,".vaultbackup-stage-"+id);
        Path rollback=boundSibling(p,"rollback",root,".vaultbackup-rollback-"+id);
        Path failed=boundSibling(p,"failed",root,".vaultbackup-failed-"+id);
        if(!RestoreFiles.required(p,"nonce").matches("[0-9a-f]{64}"))throw new IOException("Invalid job authentication secret");
        return new Job(file,file.getParent(),p,root,state,archive,stage,rollback,failed);
    }
    private static Path boundSibling(Properties p,String key,Path root,String suffix)throws IOException {
        Path path=Path.of(RestoreFiles.required(p,key)).toAbsolutePath().normalize();
        if(!path.equals(root.resolveSibling(root.getFileName()+suffix)))throw new IOException("Invalid bound "+key+" path");
        if(Files.isSymbolicLink(path))throw new IOException("Symlink at "+key+" path");return path;
    }
    /**
     * @return true when the recorded server is this helper's own parent process. That relation is then
     *         what is watched: unlike a start time, it does not move when the system clock is set, and
     *         a process ID that is reused after the server has exited is never the helper's parent.
     */
    private static boolean validateLivingParent(Properties p)throws IOException {
        long pid;
        try{pid=Long.parseLong(RestoreFiles.required(p,"parentPid"));}
        catch(RuntimeException e){throw new IOException("Invalid parent process identity",e);}
        if(pid==ProcessHandle.current().pid())throw new IOException("Helper cannot be its own parent");
        Optional<ProcessHandle> parent=ProcessHandle.current().parent();
        if(parent.isPresent() && parent.get().pid()==pid && parent.get().isAlive())return true;
        switch(RestoreFiles.parentLiveness(p)) {
            case ALIVE -> { }
            case GONE -> throw new IOException("Automatic helper must be launched before parent exit; use explicit stopped-server manual mode");
            case UNCERTAIN -> throw new IOException("Parent identity cannot be established; refusing restore");
        }
        return false;
    }
    private static RestoreFiles.Liveness serverLiveness(Properties p,boolean childOfServer)throws IOException {
        if(childOfServer) {
            Optional<ProcessHandle> parent=ProcessHandle.current().parent();
            // An orphan is given another parent the moment its parent exits.
            if(parent.isPresent()) {
                long pid;
                try{pid=Long.parseLong(RestoreFiles.required(p,"parentPid"));}
                catch(RuntimeException e){throw new IOException("Invalid parent process identity",e);}
                return parent.get().pid()==pid && parent.get().isAlive()?RestoreFiles.Liveness.ALIVE:RestoreFiles.Liveness.GONE;
            }
        }
        return RestoreFiles.parentLiveness(p);
    }
    private static boolean waitForExit(Job job,boolean childOfServer,MarkerWatch marker)throws IOException {
        while(true) {
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                if(cancelled(job))return false;
                RestoreJobState.requireActive(job.state,job.p);
            }
            marker.look();
            RestoreFiles.Liveness parent=serverLiveness(job.p,childOfServer);
            if(parent==RestoreFiles.Liveness.GONE)break;
            if(parent==RestoreFiles.Liveness.UNCERTAIN)throw new IOException("Parent process identity became uncertain; refusing restore");
            // There is deliberately no clock deadline after the bounded startup handshake.
            try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while waiting for JVM exit",e);}
        }
        return true;
    }
    /**
     * The server has exited: it is no longer this helper's parent. Its process can still be listed for
     * a while, as an exit status that nobody has collected yet. Where that state is recognised
     * ({@link RestoreFiles#exitedUncollected}) the process already counts as gone; elsewhere this waits
     * for the entry to disappear instead of refusing the restore. A cancellation is honoured meanwhile.
     *
     * @return false when the reservation was cancelled
     */
    private static boolean awaitCollected(Job job)throws IOException {
        while(true) {
            RestoreFiles.Liveness recorded=RestoreFiles.parentLiveness(job.p);
            if(recorded==RestoreFiles.Liveness.GONE)return true;
            if(recorded==RestoreFiles.Liveness.UNCERTAIN)throw new IOException("The recorded server JVM's process cannot be identified. No restore is permitted.");
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                if(cancelled(job))return false;
                RestoreJobState.requireActive(job.state,job.p);
            }
            try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while waiting for the exited server process to be collected",e);}
        }
    }
    private record Guard(FileChannel channel,FileLock lock) implements AutoCloseable {
        @Override public void close()throws IOException{lock.release();channel.close();}
    }
    private static Guard guard(Job job)throws IOException {
        FileChannel channel=RestoreFiles.openLockFile(job.state.resolve(RestoreFiles.guardName(job.root)));
        FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException e){RestoreRootLease.retainChannelUntilExit(channel);throw new IOException("Server root guard is still held",e);}
        if(lock==null){channel.close();throw new IOException("Server root guard is still held by a running server/helper. No restore permitted.");}
        return new Guard(channel,lock);
    }
    private static void checkWorldLocks(Path root)throws IOException {
        if(!Files.exists(root,RestoreFiles.NOFOLLOW))return;
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                if(file.getFileName().toString().equals("session.lock")) {
                    if(!attrs.isRegularFile() || attrs.isSymbolicLink())throw new IOException("Unsafe world session lock: "+file);
                    try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE)) {
                        FileLock lock;
                        try{lock=channel.tryLock();}catch(OverlappingFileLockException e){throw new IOException("World is in use: "+file,e);}
                        if(lock==null)throw new IOException("World is in use: "+file);lock.release();
                    }
                }return FileVisitResult.CONTINUE;
            }
        });
    }
    /** True when the directory at the rollback path is the original server root this job moved there. */
    private static boolean movedOriginal(Job job)throws IOException {
        if(!Files.exists(job.rollback,RestoreFiles.NOFOLLOW))return false;
        return switch(RestoreJobState.isOriginalRoot(job.dir,job.p,job.rollback)) {
            case YES -> true;
            // Created by somebody else: it is never moved into the server root, and it proves nothing about the root.
            case NO -> false;
            // No identities on this filesystem: the journal and the path are all there is, as before 1.3.0.
            case UNKNOWN -> true;
        };
    }
    private static void execute(Job job)throws IOException {
        boolean originalMoved=false,installed=false,moveAttempted=false,stageCreated=false;
        try {
            RestoreFiles.directory(job.root,false);checkWorldLocks(job.root);
            for(Path target:List.of(job.stage,job.rollback,job.failed))if(Files.exists(target,RestoreFiles.NOFOLLOW))throw new IOException("Recovery path already exists: "+target);
            long needed=Long.parseLong(RestoreFiles.required(job.p,"totalBytes"))+RestoreFiles.SPACE_MARGIN_BYTES;
            long available=Files.getFileStore(job.root.getParent()).getUsableSpace();
            if(available<needed)throw new IOException("Not enough free space next to server-root to extract the backup: "+needed+" bytes needed, "+available+" available");
            // Before anything is moved: which directory is the original. Rollback decisions rely on this, not on a path merely existing.
            RestoreJobState.recordRootIdentity(job.dir,job.p,job.root);
            status(job,"STAGING","Extracting and verifying archive into a sibling directory; original root unchanged");
            RestoreFiles.createPrivateDirectory(job.stage);stageCreated=true;
            if(!Files.getFileStore(job.stage).equals(Files.getFileStore(job.root)))throw new IOException("Stage and server-root must be on the same filesystem");
            RestoreArchive.Verified verified=RestoreArchive.extract(job.archive,RestoreFiles.required(job.p,"archiveSha256"),job.stage);
            if(!verified.manifestSha256().equals(RestoreFiles.required(job.p,"manifestSha256")))throw new IOException("Preview manifest changed");
            if(verified.entries().size()!=Integer.parseInt(RestoreFiles.required(job.p,"entryCount")) || verified.totalBytes()!=Long.parseLong(RestoreFiles.required(job.p,"totalBytes")))throw new IOException("Preview scope changed");
            try (var directories = Files.walk(job.stage)) {
                for (Path directory : directories.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) RestoreFiles.syncDirectory(directory);
            }
            status(job,"STAGED","All staged data verified; preparing atomic rename");
            RestoreFiles.requireDeadParent(job.p);checkWorldLocks(job.root);
            if(cancelled(job))return;
            if(RestoreJobState.isOriginalRoot(job.dir,job.p,job.root)==RestoreJobState.Original.NO)throw new IOException("The server root was replaced while the backup was being extracted. Nothing was moved.");
            status(job,"MOVING_ORIGINAL","Moving original root to preserved rollback directory");
            moveAttempted=true;RestoreFiles.move(job.root,job.rollback);originalMoved=true;
            status(job,"ORIGINAL_MOVED","Original root safely preserved at "+job.rollback);
            status(job,"INSTALLING","Installing verified stage into original server-root path");
            RestoreFiles.move(job.stage,job.root);installed=true;
            status(job,"VERIFYING_INSTALLED","Checking every installed file before issuing success receipt");
            RestoreArchive.verifyInstalled(job.root,verified);
            Properties receipt=new Properties();
            for(String key:List.of("jobId","backupId","serverRoot","archiveSha256","manifestSha256"))receipt.setProperty(key,RestoreFiles.required(job.p,key));
            receipt.setProperty("completedAt",Instant.now().toString());receipt.setProperty("rollbackDirectory",job.rollback.toString());
            receipt.setProperty("scope","Exact archive tree installed; excluded/unbacked original files remain in rollback");
            receipt.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job.p,"nonce"),RestoreFiles.receiptPayload(receipt)));
            RestoreFiles.write(job.dir.resolve("receipt.properties"),receipt);
            status(job,"SUCCESS","Offline installed data verified. Server remains stopped. Original root retained at "+job.rollback);
            System.out.println(RestoreFiles.message(job.p,"Offline restore verified. No restart was performed. Original data: ","オフライン復元の検証が完了しました。自動再起動は行いません。元データ: ")+job.rollback);
        } catch(Throwable error) {
            // Errors (an exhausted heap while extracting, for example) are journaled like exceptions:
            // a status left at STAGING would block every later server start.
            String reason=RestoreFiles.reason(error);
            // A move can succeed while the subsequent directory fsync fails. Inspect the filesystem
            // before deciding whether rollback is needed, rather than trusting the local flags.
            try{originalMoved=originalMoved || (moveAttempted && movedOriginal(job));}catch(Exception identityError){error.addSuppressed(identityError);}
            installed=installed || (originalMoved && Files.exists(job.root,RestoreFiles.NOFOLLOW));
            if(originalMoved) {
                // A journal failure may be the install error itself. Reporting rollback must
                // never prevent the atomic moves that put the preserved original back.
                try{status(job,"ROLLING_BACK","Install failed; restoring untouched original root");}catch(Exception statusError){error.addSuppressed(statusError);}
                try {
                    if(installed)RestoreFiles.move(job.root,job.failed);
                    RestoreFiles.move(job.rollback,job.root);
                    Files.deleteIfExists(job.dir.resolve("receipt.properties"));
                    RestoreJobState.recordCompletedRecovery(job.dir,job.p,"FAILED_ROLLED_BACK");
                    try{status(job,"FAILED_ROLLED_BACK","Original root restored; failed/staged data retained. "+reason);}catch(Exception statusError){error.addSuppressed(statusError);}
                } catch(Exception rollbackError) {
                    error.addSuppressed(rollbackError);
                    try{status(job,"RECOVERY_REQUIRED","Automatic rollback was incomplete. Do not start server. Preserve every sibling and use --recover while stopped. "+reason);}catch(Exception statusError){error.addSuppressed(statusError);}
                }
            } else {
                // Nothing was moved. A partial extraction is of no use to anyone and may be what filled
                // the disk; remove it first, so that the journal entry below can still be written.
                if(stageCreated) try{RestoreFiles.deleteTree(job.stage);}catch(Exception cleanupError){error.addSuppressed(cleanupError);}
                String foreign=Files.exists(job.rollback,RestoreFiles.NOFOLLOW)?" A directory this job did not create exists at "+job.rollback+" and was left untouched; remove it before reserving again.":"";
                try{status(job,"FAILED_BEFORE_SWAP","Original server-root was not moved by this attempt."+foreign+" "+reason);}catch(Exception statusError){error.addSuppressed(statusError);}
            }
            if(error instanceof IOException io)throw io;throw new IOException("Restore failed: "+reason,error);
        }
    }
    /** Best effort: an extraction that was never installed is of no use and may be large. */
    private static String discardStage(Job job) {
        if(!Files.exists(job.stage,RestoreFiles.NOFOLLOW))return "";
        try{RestoreFiles.directory(job.stage,false);RestoreFiles.deleteTree(job.stage);return " The unused extraction was removed.";}
        catch(IOException | RuntimeException kept){return " The unused extraction at "+job.stage+" could not be removed ("+RestoreFiles.reason(kept)+"); delete it by hand.";}
    }
    private static void recover(Job job,boolean acceptRollback)throws IOException {
        String status=RestoreFiles.status(job.dir);
        if(Files.exists(job.dir.resolve("recovery-receipt.properties"),RestoreFiles.NOFOLLOW)) {
            RestoreJobState.verifiedRecovery(job.dir,job.p);
            System.out.println(RestoreFiles.message(job.p,"Original-root recovery was already completed and authenticated. No changes made.","元データの復旧は既に完了し、証明を確認しました。変更していません。"));return;
        }
        if(status.equals("SUCCESS"))throw new IOException("Successful jobs are not automatically reverted. Original rollback is preserved; use a separate reviewed recovery plan.");
        RestoreFiles.requireDeadParent(job.p);checkWorldLocks(job.root);checkWorldLocks(job.rollback);
        Set<String> preSwap=Set.of("COMMITTED","READY","WAITING_FOR_MANUAL_STOP","PARENT_EXITED","STAGING","STAGED","FAILED_BEFORE_SWAP","CANCELLED","EXPIRED");
        if(Files.exists(job.rollback,RestoreFiles.NOFOLLOW)) {
            RestoreFiles.directory(job.rollback,false);
            // Only the original root itself is ever moved back. A directory that merely has the rollback
            // name could have been created by another local user with access to the parent directory.
            // The operator may overrule that after looking at it: on a filesystem that does not keep inode
            // numbers the original itself can look like another directory.
            if(!acceptRollback && RestoreJobState.isOriginalRoot(job.dir,job.p,job.rollback)==RestoreJobState.Original.NO)
                throw new IOException("The directory at "+job.rollback+" does not have the identity of the original server root that this job recorded. Nothing was changed, and it was left where it is. Look at its contents. If it is your original server data, run the recovery again with --accept-rollback-directory added.");
            status(job,"ROLLING_BACK","Explicit stopped-server recovery restoring preserved original root");
            if(Files.exists(job.root,RestoreFiles.NOFOLLOW)) {
                if(Files.exists(job.failed,RestoreFiles.NOFOLLOW))throw new IOException("Failed-tree preservation target already exists. No data overwritten; manual inspection required.");
                RestoreFiles.move(job.root,job.failed);
            }
            RestoreFiles.move(job.rollback,job.root);
            Files.deleteIfExists(job.dir.resolve("receipt.properties"));
            RestoreJobState.recordCompletedRecovery(job.dir,job.p,"RECOVERED");
            status(job,"RECOVERED","Original root restored. Stage/failed tree preserved. Server remains stopped.");
            System.out.println(RestoreFiles.message(job.p,"Original server-root recovered. No restart performed.","元のサーバーデータを復旧しました。自動再起動は行いません。"));
        } else if(preSwap.contains(status) && Files.isDirectory(job.root,RestoreFiles.NOFOLLOW)) {
            status(job,"FAILED_BEFORE_SWAP","No original-root move was recorded; current root left untouched."+discardStage(job));
            System.out.println(RestoreFiles.message(job.p,"No original-root move recorded. The server data was left untouched.","元データの移動記録はありません。現在のサーバーデータは変更していません。"));
        } else if(RestoreJobState.isOriginalRoot(job.dir,job.p,job.root)==RestoreJobState.Original.YES) {
            // The journal stopped mid-swap or mid-rollback, but the directory in place is provably the
            // original: the helper was interrupted before it moved it, or after it had moved it back.
            Files.deleteIfExists(job.dir.resolve("receipt.properties"));
            RestoreJobState.recordCompletedRecovery(job.dir,job.p,"RECOVERED");
            status(job,"RECOVERED","The original root is in place (verified by its recorded identity). Stage/failed tree preserved. Server remains stopped.");
            System.out.println(RestoreFiles.message(job.p,"The original server-root is in place. No restart performed.","元のサーバーデータが元の場所にあることを確認しました。自動再起動は行いません。"));
        } else throw new IOException("Cannot prove original root location from journal. No data changed. Inspect root, stage, rollback, and failed siblings manually.");
    }
}
