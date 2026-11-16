// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Online preparation only. The root sentinel is intentionally retained until actual JVM exit. */
public final class RestoreCoordinator {
    public record Reservation(String token, String backupId, String archiveSha256,
                              int entryCount, long totalBytes, Instant expiresAt) {}
    public record LaunchResult(String jobId, Path jobFile, boolean helperReady, String message) {}
    public record Receipt(String jobId, String backupId, Instant completedAt, Path rollbackDirectory) {}
    public record Status(String jobId, String backupId, String state, String message, Path jobFile, boolean cancellable) {}
    public record CancelResult(String jobId, boolean cancelled, String message) {}
    public enum StartupDisposition { READY, GUARDED_DEGRADED, HARD_CONFLICT }
    /**
     * @param guardHeldElsewhere true when another process (a restore helper at work, or a second server)
     *                           holds this root's guard; this server must not run. A hard conflict without
     *                           it and without a journal is a fault of the configured paths, not a restore in progress.
     */
    public record StartupResult(StartupDisposition disposition, RestoreCoordinator coordinator, String message, boolean guardHeldElsewhere) {
        public StartupResult(StartupDisposition disposition, RestoreCoordinator coordinator, String message) { this(disposition,coordinator,message,false); }
    }
    /** What startup found about an earlier restore job, for the administrator. */
    /**
     * What the previous run left behind, when anything. STALE_RESERVATION_CANCELLED: a reservation that
     * never got as far as an attempt. RESTORE_NOT_APPLIED: the helper started on it but stopped or was
     * stopped before it moved the server data. FAILED_RESTORE_ROLLED_BACK: it had moved the data and put it back.
     */
    public enum StartupFinding { NONE, STALE_RESERVATION_CANCELLED, RESTORE_NOT_APPLIED, FAILED_RESTORE_ROLLED_BACK }
    /** The server's layout cannot take an offline restore; told at reservation, not after the server was stopped. */
    public static final class RestoreUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;
        public enum Reason {
            /** server-root is a mount point (usual on container hosting); a mount point cannot be renamed. */
            ROOT_IS_MOUNT_POINT,
            /** The directory that contains server-root does not accept new entries. */
            PARENT_NOT_WRITABLE,
            /** The backup is extracted next to server-root first; there is not enough room for that. */
            INSUFFICIENT_SPACE
        }
        private final Reason reason;
        private final long neededBytes, availableBytes;
        RestoreUnavailableException(Reason reason,String message,long neededBytes,long availableBytes) {
            super(message);this.reason=reason;this.neededBytes=neededBytes;this.availableBytes=availableBytes;
        }
        public Reason reason(){return reason;}
        public long neededBytes(){return neededBytes;}
        public long availableBytes(){return availableBytes;}
    }
    /** The archive as it was when verified; an unchanged file need not be hashed again a moment later. */
    private record Seen(Object fileKey,long size,java.nio.file.attribute.FileTime modified) {
        static Seen of(Path file)throws IOException {
            var a=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,RestoreFiles.NOFOLLOW);
            return new Seen(a.fileKey(),a.size(),a.lastModifiedTime());
        }
    }
    private record Pending(Reservation value, Path archive, String manifestSha256, Seen seen) {}
    private final Path root, backups, state, helperJar;
    private String language;
    private final Map<String,Pending> pending = new HashMap<>();
    private final Map<String,String> confirmedTokens = new HashMap<>();
    private final Map<String,Process> processes = new HashMap<>();
    private final RestoreRootLease lifetimeLease;
    private final Consumer<String> logger;
    private StartupDisposition startupDisposition=StartupDisposition.HARD_CONFLICT;
    private String startupDiagnostic="Restore startup validation has not completed";
    private final long parentPid;
    private final Instant parentStartedAt;
    private String committedBackupId, startupFailure, ownedJobId;
    private StartupFinding startupFinding=StartupFinding.NONE;
    /** Startup only: whether the job being resolved leaves an extraction that is to be removed. */
    private boolean discardStageWhenResolved;
    /** Test seam only: replaces the free space reported for the directory that contains server-root. */
    volatile java.util.function.LongSupplier usableSpaceProbe;
    /** SHA-256 of the helper bundled with the running plugin; null means no expectation was supplied. */
    private String expectedHelperSha256;
    private static final Set<String> SAFE_TERMINAL=Set.of("EXPIRED","FAILED_BEFORE_SWAP");

    /** Acquire the process-lifetime guard before inspecting any mutable startup journal. */
    public static StartupResult start(Path serverRoot,Path backupDirectory,Path stateDirectory,Path helperJar,
                                      String language,Consumer<String> logger) {
        RestoreCoordinator coordinator=null;
        try {
            RestoreRootLease lease=RestoreRootLease.acquire(serverRoot,stateDirectory);
            coordinator=new RestoreCoordinator(lease,backupDirectory,helperJar,language,logger);
            return coordinator.reconcileStartup();
        } catch(RestoreRootLease.HeldElsewhereException e) {
            return new StartupResult(StartupDisposition.HARD_CONFLICT,coordinator,"Restore startup conflict: "+RestoreFiles.reason(e),true);
        } catch(IOException | RuntimeException e) {
            // An acquired lease stays strongly retained by its shutdown hook, even
            // when construction or journal validation fails and no instance returns.
            return new StartupResult(StartupDisposition.HARD_CONFLICT,coordinator,
                    "Restore startup conflict: "+RestoreFiles.reason(e));
        }
    }
    /**
     * Best-effort look at whether a restore journal exists in a state directory, for deciding how
     * to treat a startup conflict. True also when that cannot be determined.
     */
    public static boolean journalMayExist(Path stateDirectory) {
        try {
            Path state=stateDirectory.toAbsolutePath().normalize();
            if(!Files.exists(state,RestoreFiles.NOFOLLOW))return false;
            if(!Files.isDirectory(state,RestoreFiles.NOFOLLOW) || !Files.isReadable(state))return true;
            return Files.exists(state.resolve("active-restore.properties"),RestoreFiles.NOFOLLOW);
        } catch(RuntimeException unknown) {return true;}
    }
    public RestoreCoordinator(Path serverRoot, Path backupDirectory, Path stateDirectory, Path helperJar)throws IOException {
        this(serverRoot,backupDirectory,stateDirectory,helperJar,"en");
    }
    public RestoreCoordinator(Path serverRoot, Path backupDirectory, Path stateDirectory, Path helperJar,String language)throws IOException {
        this(RestoreRootLease.acquire(serverRoot,stateDirectory),backupDirectory,helperJar,language,message -> {});
        StartupResult result=reconcileStartup();
        if(result.disposition()==StartupDisposition.HARD_CONFLICT)throw new IOException(result.message());
        if(result.disposition()==StartupDisposition.READY) {
            RestoreFiles.directory(backups,true);RestoreFiles.directory(state.resolve("restore-jobs"),true);
        }
    }
    private RestoreCoordinator(RestoreRootLease lease,Path backupDirectory,Path helperJar,String language,
                               Consumer<String> logger)throws IOException {
        lifetimeLease=lease;root=lease.root;state=lease.state;
        this.logger=logger==null?message -> {}:logger;
        Path backupPath=backupDirectory.toAbsolutePath().normalize();
        RestoreFiles.external(root,backupPath,"Backup directory");
        // Ordinary backup storage setup belongs to BackupEngine after startup
        // safety classification. A full/unwritable backup disk is not a swap conflict.
        backups=backupPath;
        this.helperJar=helperJar.toAbsolutePath().normalize();
        this.language=language!=null && language.toLowerCase(Locale.ROOT).startsWith("ja")?"ja":"en";
        if(this.helperJar.startsWith(root))throw new IOException("The offline helper must be outside server-root");
        parentPid=ProcessHandle.current().pid();
        parentStartedAt=ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("Cannot determine server JVM start instant"));
    }
    private StartupResult startup(StartupDisposition disposition,String message) {
        startupDisposition=disposition;startupDiagnostic=message;
        return new StartupResult(disposition,this,message);
    }
    private StartupResult reconcileStartup() {
        try {
            RestoreFiles.directory(root,false);
            if(!Files.exists(activeFile(),RestoreFiles.NOFOLLOW))return startup(StartupDisposition.READY,"");
            Properties active=RestoreFiles.load(activeFile());String id=RestoreFiles.required(active,"jobId");
            committedBackupId=RestoreFiles.required(active,"backupId");
            Path dir=jobDirectory(id);Properties job=RestoreFiles.load(dir.resolve("job.properties"));
            validateBoundJob(job,id);RestoreJobState.requireActive(state,job);
            // The helper takes job.lock then tries the root guard nonblockingly.
            // We already hold the root lease, and use a bounded job-lock wait, so
            // the two roles cannot deadlock while an old helper is winding down.
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
                job=RestoreFiles.load(dir.resolve("job.properties"));
                validateBoundJob(job,id);RestoreJobState.requireActive(state,job);
                String current=RestoreFiles.status(dir); // Missing state is an error, never COMMITTED.
                if(current.equals("SUCCESS")) {
                    verifiedReceipt(id,dir,job);
                    return startup(StartupDisposition.READY,"");
                }
                boolean rolledBack=Files.exists(dir.resolve("recovery-receipt.properties"),RestoreFiles.NOFOLLOW);
                if(rolledBack) {
                    // The authenticated receipt is emitted only after the helper
                    // physically returns the preserved original tree to root.
                    // It also handles a failure writing the final human-readable status.
                    RestoreJobState.verifiedRecovery(dir,job);
                } else requirePreSwap(dir,job,current);
                // Only after positively establishing a pre-swap/restored root may failure
                // to persist/acknowledge a cancellation become degraded startup.
                boolean notApplied=!rolledBack && Set.of("PARENT_EXITED","STAGING","STAGED","FAILED_BEFORE_SWAP").contains(current);
                startupFinding=rolledBack?StartupFinding.FAILED_RESTORE_ROLLED_BACK:notApplied?StartupFinding.RESTORE_NOT_APPLIED:StartupFinding.STALE_RESERVATION_CANCELLED;
                startupFailure=rolledBack?"Restore "+id+" failed after the swap had begun; the helper put the original server data back. The failed data is kept beside server-root."
                        :notApplied?"Restore "+id+" was not applied; it stopped before the server data was moved ("+current+": "+statusMessage(dir)+"). The current server data was kept."
                        :"Stale restore "+id+" was cancelled; the current server data was kept.";
                discardStageWhenResolved=!rolledBack;
                try {
                    RestoreJobState.requestCancellation(dir,job);
                    if(finishStartupCancellation(id,dir,job))return startup(StartupDisposition.READY,startupFailure);
                } catch(IOException | RuntimeException e) {
                    return startup(StartupDisposition.GUARDED_DEGRADED,
                            "Stale restore "+id+" is blocked by this JVM's root guard, but cancellation is unresolved: "+RestoreFiles.reason(e));
                }
            }
            // Give a live old helper an opportunity to authenticate the durable
            // tombstone. Do not invent process ownership or a NOT_LAUNCHED ack.
            // After a plugin reload the helper is still polling (every 200 ms) for this JVM's exit.
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            do {
                try(RestoreJobState.Gate gate=RestoreJobState.lock(dir,Duration.ofMillis(100))) {
                    if(gate!=null && finishStartupCancellation(id,dir,job))return startup(StartupDisposition.READY,startupFailure);
                } catch(IOException | RuntimeException e) {
                    return startup(StartupDisposition.GUARDED_DEGRADED,
                            "Stale restore "+id+" is safely guarded, but cancellation acknowledgment failed: "+RestoreFiles.reason(e));
                }
                try {Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            } while(System.nanoTime()<deadline);
            return startup(StartupDisposition.GUARDED_DEGRADED,
                    "Stale restore "+id+" is safely guarded. Cancellation is pending; backup mutations stay disabled until the journal is resolved.");
        } catch(IOException | RuntimeException e) {
            return startup(StartupDisposition.HARD_CONFLICT,"Restore startup conflict: "+RestoreFiles.reason(e));
        }
    }
    private static String statusMessage(Path dir) {
        try{return RestoreFiles.load(dir.resolve("status.properties")).getProperty("message","");}
        catch(IOException | RuntimeException unreadable){return "";}
    }
    private void requirePreSwap(Path dir,Properties job,String current)throws IOException {
        // The helper journals MOVING_ORIGINAL durably before it renames anything, and it works under the
        // root guard, which this JVM holds now. A journal that ends at the extraction therefore means an
        // extraction that was cut short, with the original root untouched: the helper was killed together
        // with the server's terminal, service or container. That resolves by itself once the helper is
        // known to be gone, instead of keeping the server from starting until somebody runs a recovery.
        boolean interrupted=Set.of("STAGING","STAGED").contains(current);
        if(interrupted) {
            if(!RestoreJobState.confirmedDead(dir,job))
                throw new IOException("Offline restore "+RestoreFiles.required(job,"jobId")+" stopped while extracting ("+current+"), and its helper may still be running. Inspect/recover this job while stopped.");
            if(RestoreJobState.isOriginalRoot(dir,job,root)==RestoreJobState.Original.NO)
                throw new IOException("Offline restore "+RestoreFiles.required(job,"jobId")+" stopped while extracting ("+current+"), and server-root is not the directory it recorded. Inspect/recover this job while stopped.");
        } else if(!RestoreJobState.waiting(current) && !Set.of("EXPIRED","FAILED_BEFORE_SWAP","CANCELLED").contains(current))
            throw new IOException("Unresolved offline restore "+RestoreFiles.required(job,"jobId")+" ("+current+"). Inspect/recover this job while stopped.");
        if(current.equals("CANCELLED") && !RestoreJobState.hasCancellation(dir))throw new IOException("Cancelled status has no authenticated cancellation tombstone");
        // A status string alone cannot prove that the original root was not moved.
        // Unexpected siblings/receipts are preserved for stopped-server inspection.
        for(String kind:List.of("rollback","failed")) {
            Path path=boundSibling(job,kind);
            if(!Files.exists(path,RestoreFiles.NOFOLLOW))continue;
            // The helper recorded which directory is the original root before it would have moved
            // anything. With that directory still in place, whatever sits at this path was not put
            // there by a swap of this job, and anybody able to create it must not thereby be able to
            // keep the server from starting.
            if(RestoreJobState.isOriginalRoot(dir,job,root)==RestoreJobState.Original.YES) {
                logger.accept("[restore] A directory that restore job "+RestoreFiles.required(job,"jobId")+" did not create exists at "+path+". It was left untouched; the server root is the recorded original.");
                continue;
            }
            throw new IOException("Ambiguous pre-swap journal: "+kind+" tree exists at "+path);
        }
        Path stage=boundSibling(job,"stage");
        if(Files.exists(stage,RestoreFiles.NOFOLLOW)) {
            if(!interrupted && !current.equals("FAILED_BEFORE_SWAP") && !(current.equals("CANCELLED") && RestoreJobState.acknowledged(dir,job)))
                throw new IOException("Unexpected staged tree for waiting restore: "+stage);
            RestoreFiles.directory(stage,false);
        }
        if(Files.exists(dir.resolve("receipt.properties"),RestoreFiles.NOFOLLOW))throw new IOException("Unverified restore receipt conflicts with pre-swap journal");
    }
    private Path boundSibling(Properties job,String kind)throws IOException {
        Path path=Path.of(RestoreFiles.required(job,kind));
        Path expected=root.resolveSibling(root.getFileName()+".vaultbackup-"+kind+"-"+RestoreFiles.required(job,"jobId"));
        if(!path.equals(expected) || Files.isSymbolicLink(path))throw new IOException("Invalid bound "+kind+" path");
        return path;
    }
    /** Caller holds job.lock. Old jobs are never claimed as this JVM's children. */
    private boolean finishStartupCancellation(String id,Path dir,Properties job)throws IOException {
        if(!RestoreJobState.acknowledged(dir,job)) {
            if(!RestoreJobState.confirmedDead(dir,job))return false;
            try {RestoreJobState.acknowledgeCancellation(dir,job,"CONFIRMED_HELPER_DEATH");}
            catch(IOException e) {if(!RestoreJobState.acknowledged(dir,job))throw e;}
        }
        // From here on the job can never be applied. What it extracted is of no use and may be what filled the disk.
        if(discardStageWhenResolved)discardStage(job);
        clearActive(id);return true;
    }
    /** Best effort. Only ever the job-bound sibling directory that the helper extracted into. */
    private void discardStage(Properties job) {
        try {
            Path stage=boundSibling(job,"stage");
            if(!Files.exists(stage,RestoreFiles.NOFOLLOW))return;
            RestoreFiles.directory(stage,false);RestoreFiles.deleteTree(stage);
            logger.accept("[restore] Removed the unused extraction of restore job "+job.getProperty("jobId")+": "+stage);
        } catch(IOException | RuntimeException kept) {
            logger.accept("[restore] The unused extraction of restore job "+job.getProperty("jobId")+" could not be removed ("+RestoreFiles.reason(kept)+"). It can be deleted by hand.");
        }
    }
    public Reservation reserve(String backupId,Path archive,String expectedSha256,Duration ttl)throws IOException {
        if(backupId==null || !backupId.matches("[A-Za-z0-9._-]{1,160}"))throw new IOException("Invalid backup identifier");
        if(ttl==null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(15))>0)throw new IOException("Restore confirmation expiry must be between 1 second and 15 minutes");
        Path file=archive.toAbsolutePath().normalize();RestoreFiles.regular(file);
        if(!file.startsWith(backups) || file.equals(backups))throw new IOException("Archive is outside the configured backup directory");
        synchronized(this){ensureNoCommitted();}
        Seen seen=Seen.of(file);
        RestoreArchive.Verified verified=RestoreArchive.verify(file,expectedSha256);
        requireRestorableLayout(verified.totalBytes());
        Reservation value=new Reservation(RestoreFiles.randomSecret().substring(0,24),backupId,expectedSha256,verified.entries().size(),verified.totalBytes(),Instant.now().plus(ttl));
        synchronized(this){ensureNoCommitted();pending.clear();pending.put(value.token(),new Pending(value,file,verified.manifestSha256(),seen));}return value;
    }
    /**
     * The helper extracts the backup into a directory beside server-root and then renames both.
     * Where that cannot work, the administrator learns it now, while the server is still running,
     * instead of from a refused restore after the stop.
     */
    private void requireRestorableLayout(long totalBytes)throws IOException {
        Path parent=root.getParent();
        if(!Files.getFileStore(root).equals(Files.getFileStore(parent)))
            throw new RestoreUnavailableException(RestoreUnavailableException.Reason.ROOT_IS_MOUNT_POINT,
                    "server-root is a mount point and cannot be replaced by an offline restore: "+root,0,0);
        if(!Files.isWritable(parent))
            throw new RestoreUnavailableException(RestoreUnavailableException.Reason.PARENT_NOT_WRITABLE,
                    "The directory containing server-root is not writable, so a restore could not be staged or swapped: "+parent,0,0);
        long needed=totalBytes>Long.MAX_VALUE-RestoreFiles.SPACE_MARGIN_BYTES?Long.MAX_VALUE:totalBytes+RestoreFiles.SPACE_MARGIN_BYTES;
        java.util.function.LongSupplier probe=usableSpaceProbe;
        long available=probe!=null?probe.getAsLong():Files.getFileStore(parent).getUsableSpace();
        if(available<needed)
            throw new RestoreUnavailableException(RestoreUnavailableException.Reason.INSUFFICIENT_SPACE,
                    "Not enough free space beside server-root to extract the backup: "+needed+" bytes needed, "+available+" available",needed,available);
    }
    /**
     * The helper runs with the server's OS account and replaces server-root, so it must be
     * exactly the bundled helper. confirm() re-hashes the installed file immediately before launch.
     */
    public synchronized void requireHelperSha256(String sha256)throws IOException {
        if(sha256==null || !sha256.matches("[0-9a-f]{64}"))throw new IOException("Invalid expected helper SHA-256");
        expectedHelperSha256=sha256;
    }
    public synchronized void setLanguage(String value)throws IOException {
        ensureNoCommitted();language=value!=null && value.toLowerCase(Locale.ROOT).startsWith("ja")?"ja":"en";
    }
    public Optional<String> startupFailure(){return Optional.ofNullable(startupFailure);}
    public StartupFinding startupFinding(){return startupFinding;}
    public synchronized boolean cancel(String token){return pending.remove(token)!=null;}
    public synchronized Set<String> protectedBackupIds(){
        Set<String> ids=new HashSet<>();for(Pending p:pending.values())if(p.value.expiresAt().isAfter(Instant.now()))ids.add(p.value.backupId());
        if(committedBackupId!=null)ids.add(committedBackupId);return Set.copyOf(ids);
    }
    private void ensureNoCommitted()throws IOException {
        if(startupDisposition!=StartupDisposition.READY)throw new IOException("Restore coordinator is not ready: "+startupDisposition);
        if(committedBackupId!=null || Files.exists(activeFile(),RestoreFiles.NOFOLLOW))throw new IOException("An offline restore reservation already exists; cancel or resolve it first");
    }
    /** Confirmation reserves work; it never requests server shutdown or a restart. */
    public synchronized LaunchResult confirm(String token)throws IOException {
        if(confirmedTokens.containsKey(token))return launchResult(confirmedTokens.get(token));
        ensureNoCommitted();Pending p=pending.remove(token);
        if(p==null || !p.value.expiresAt().isAfter(Instant.now()))throw new IOException("Restore confirmation is missing, used, or expired");
        // The helper verifies every byte again before it applies anything. Here only a file that is
        // no longer the one just verified needs hashing; that also keeps this monitor, which a
        // server shutdown waits for, from being held for the time it takes to read a large archive.
        if(!p.seen.equals(Seen.of(p.archive)) && !RestoreFiles.sha(p.archive).equals(p.value.archiveSha256()))throw new IOException("Backup changed after preview");
        RestoreFiles.directory(state.resolve("restore-jobs"),true);
        String id=UUID.randomUUID().toString();Path dir=jobDirectory(id);RestoreFiles.createPrivateDirectory(dir);Properties job=new Properties();
        job.setProperty("formatVersion","2");job.setProperty("jobId",id);job.setProperty("backupId",p.value.backupId());job.setProperty("language",language);
        job.setProperty("shutdownPolicy","MANUAL_NORMAL_STOP");
        job.setProperty("serverRoot",root.toString());job.setProperty("backupDirectory",backups.toString());job.setProperty("stateDirectory",state.toString());
        job.setProperty("archive",p.archive.toString());job.setProperty("archiveSha256",p.value.archiveSha256());job.setProperty("manifestSha256",p.manifestSha256);
        job.setProperty("entryCount",Integer.toString(p.value.entryCount()));job.setProperty("totalBytes",Long.toString(p.value.totalBytes()));
        job.setProperty("parentPid",Long.toString(parentPid));job.setProperty("parentStartedAt",parentStartedAt.toString());job.setProperty("nonce",RestoreFiles.randomSecret());job.setProperty("createdAt",Instant.now().toString());
        for(String kind:List.of("stage","rollback","failed"))job.setProperty(kind,root.resolveSibling(root.getFileName()+".vaultbackup-"+kind+"-"+id).toString());
        RestoreFiles.write(dir.resolve("job.properties"),job);writeStatus(dir,"COMMITTED","Starting helper for a manual-stop reservation");
        Properties active=new Properties();active.setProperty("jobId",id);active.setProperty("backupId",p.value.backupId());
        // Pin before an atomic publication whose subsequent directory fsync may still fail.
        committedBackupId=p.value.backupId();ownedJobId=id;confirmedTokens.put(token,id);pending.clear();
        try{RestoreFiles.write(activeFile(),active);}catch(IOException e){return failedHandshake(id,null,"Could not publish restore reservation: "+RestoreFiles.reason(e));}
        Process process=null;
        try {
            RestoreFiles.regular(helperJar);
            if(expectedHelperSha256!=null && !RestoreFiles.constantEquals(RestoreFiles.sha(helperJar),expectedHelperSha256))
                throw new IOException("Installed restore helper does not match the helper bundled with this plugin. It was not started. Restart the server so the plugin reinstalls it, then reserve again");
            String executable=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
            Path java=Path.of(System.getProperty("java.home"),"bin",executable);
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
                ProcessBuilder builder=new ProcessBuilder(java.toString(),"-jar",helperJar.toString(),"--job",dir.resolve("job.properties").toString())
                        .directory(state.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("helper.log").toFile()));
                // The helper creates the archive's names in the file-name encoding it inherits from this
                // server, which is the one the server will look the files up with afterwards (a server
                // running with EUC-JP gets EUC-JP names back). Only a server without any encoding, the
                // C/POSIX locale, could not name a non-ASCII file at all; its helper is given a UTF-8
                // locale. Where that locale does not exist, nothing changes.
                if(asciiOnlyFileNames() && !System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))
                    builder.environment().put("LC_ALL","C.UTF-8");
                process=builder.start();
                processes.put(id,process);RestoreJobState.recordProcess(dir,job,process.toHandle());
            }
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(System.nanoTime()<deadline) {
                if(RestoreFiles.status(dir).equals("WAITING_FOR_MANUAL_STOP") && process.isAlive())return launchResult(id);
                if(!process.isAlive())break;
                try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
            return failedHandshake(id,process,"Helper did not acknowledge readiness");
        }catch(IOException | RuntimeException e){return failedHandshake(id,process,RestoreFiles.reason(e));}
    }
    private static boolean asciiOnlyFileNames() {
        try{return java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding","")).equals(java.nio.charset.StandardCharsets.US_ASCII);}
        catch(RuntimeException unknownEncoding){return false;}
    }
    private LaunchResult launchResult(String id)throws IOException {
        Path dir=jobDirectory(id);String current=RestoreFiles.status(dir);
        boolean ready=current.equals("WAITING_FOR_MANUAL_STOP") && !RestoreJobState.hasCancellation(dir);
        if(ready){Process process=processes.get(id);ready=process!=null && process.isAlive();}
        String message=ready?"Reservation ready. Keep playing; manually stop the server when ready. The helper waits without a time limit and never restarts the server."
                :"Existing reservation "+id+" is "+current+". No additional helper was started.";
        return new LaunchResult(id,dir.resolve("job.properties"),ready,message);
    }
    private LaunchResult failedHandshake(String id,Process process,String reason) {
        Path file;
        try{file=jobDirectory(id).resolve("job.properties");}catch(IOException impossible){throw new IllegalStateException(impossible);}
        try {
            CancelResult result=cancelCommitted(id);
            if(result.cancelled())return new LaunchResult(id,file,false,reason+". Reservation was durably cancelled; the server remains running. Inspect "+file.getParent().resolve("helper.log"));
        }catch(IOException | RuntimeException ignored){ /* Keep uncertain job pinned and attempt confirmed termination below. */ }
        if(process!=null) {
            process.destroyForcibly();boolean dead=false;
            try{dead=process.waitFor(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(dead) {
                try {CancelResult result=cancelCommitted(id);if(result.cancelled())return new LaunchResult(id,file,false,reason+". Helper was terminated and reservation durably cancelled; the server remains running.");}
                catch(IOException | RuntimeException ignored){ }
                return new LaunchResult(id,file,false,reason+". Helper was confirmed terminated, but its journal remains unresolved. Keep the server running and inspect "+file);
            }
        }
        return new LaunchResult(id,file,false,reason+". Cancellation is NOT acknowledged. Do not stop this JVM until the helper and journal are resolved: "+file);
    }
    public synchronized Optional<Status> status()throws IOException {
        if(!Files.exists(activeFile(),RestoreFiles.NOFOLLOW)) {
            if(committedBackupId!=null && ownedJobId!=null)return Optional.of(new Status(ownedJobId,committedBackupId,"CANCEL_PENDING","Reservation publication/cleanup is unresolved; cancel before stopping.",jobDirectory(ownedJobId).resolve("job.properties"),true));
            return Optional.empty();
        }
        Properties a=RestoreFiles.load(activeFile());String id=RestoreFiles.required(a,"jobId");Path dir=jobDirectory(id);Properties job=RestoreFiles.load(dir.resolve("job.properties"));validateBoundJob(job,id);
        if(startupDisposition!=StartupDisposition.READY)return Optional.of(new Status(id,RestoreFiles.required(a,"backupId"),startupDisposition.name(),startupDiagnostic,dir.resolve("job.properties"),false));
        String current=RestoreFiles.status(dir);
        if(RestoreJobState.hasCancellation(dir)) {
            try(RestoreJobState.Gate gate=RestoreJobState.lock(dir,Duration.ofMillis(200))) {
                if(gate!=null && finishCancellation(id,dir,job))return Optional.empty();
            }
            return Optional.of(new Status(id,RestoreFiles.required(a,"backupId"),"CANCEL_PENDING","Cancellation requested; waiting for verified acknowledgment. Keep the server running.",dir.resolve("job.properties"),true));
        }
        if(SAFE_TERMINAL.contains(current) && confirmedDead(id,dir,job)) {
            String why=RestoreFiles.load(dir.resolve("status.properties")).getProperty("message","");
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
                RestoreJobState.requestCancellation(dir,job);RestoreJobState.acknowledgeCancellation(dir,job,"CONFIRMED_HELPER_DEATH");clearActive(id);
            }
            // Otherwise the reservation would simply disappear from the administrator's view.
            logger.accept("[restore] "+current+": restore job "+id+" ended without changing anything and its reservation was cleared. "+why);
            return Optional.empty();
        }
        if(RestoreJobState.waiting(current)) {
            try {
                if(confirmedDead(id,dir,job))return Optional.of(new Status(id,RestoreFiles.required(a,"backupId"),"HELPER_EXITED","The helper exited while waiting. No restore will run; cancel this reservation before stopping and then reserve again.",dir.resolve("job.properties"),Objects.equals(ownedJobId,id)));
            }catch(IOException e){return Optional.of(new Status(id,RestoreFiles.required(a,"backupId"),"HELPER_UNCERTAIN","Helper identity is uncertain. Keep the server running and resolve/cancel this reservation: "+RestoreFiles.reason(e),dir.resolve("job.properties"),Objects.equals(ownedJobId,id)));}
        }
        Properties status=RestoreFiles.load(dir.resolve("status.properties"));
        return Optional.of(new Status(id,RestoreFiles.required(a,"backupId"),current,status.getProperty("message",""),dir.resolve("job.properties"),Objects.equals(ownedJobId,id) && RestoreJobState.waiting(current)));
    }
    public synchronized CancelResult cancelCommitted()throws IOException {
        if(!Files.exists(activeFile(),RestoreFiles.NOFOLLOW))return committedBackupId!=null && ownedJobId!=null?cancelCommitted(ownedJobId):new CancelResult(ownedJobId==null?"":ownedJobId,true,"No committed restore reservation remains.");
        return cancelCommitted(RestoreFiles.required(RestoreFiles.load(activeFile()),"jobId"));
    }
    /** Success is linearized under job.lock and requires a permanent tombstone plus acknowledgment/death. */
    public synchronized CancelResult cancelCommitted(String id)throws IOException {
        if(!Objects.equals(ownedJobId,id))throw new IOException("Cannot cancel a reservation not committed by this coordinator/JVM");
        Path dir=jobDirectory(id);Properties job=RestoreFiles.load(dir.resolve("job.properties"));validateBoundJob(job,id);
        try(RestoreJobState.Gate gate=RestoreJobState.lock(dir,Duration.ofSeconds(2))) {
            if(gate==null)return new CancelResult(id,false,"Application is already locked/in progress; cancellation was not acknowledged.");
            if(RestoreJobState.acknowledged(dir,job)){clearActive(id);return new CancelResult(id,true,"Reservation already cancelled; this job cannot restore.");}
            if(Files.exists(activeFile(),RestoreFiles.NOFOLLOW))RestoreJobState.requireActive(state,job);
            else if(processes.containsKey(id))throw new IOException("Active journal is missing for a launched helper; inspect before cancelling");
            String current;
            try{current=RestoreFiles.status(dir);}catch(IOException | RuntimeException e){current="UNKNOWN";}
            if(!RestoreJobState.waiting(current) && !SAFE_TERMINAL.contains(current) && !current.equals("UNKNOWN"))return new CancelResult(id,false,"Job is "+current+"; it cannot be cancelled after application starts.");
            RestoreJobState.requestCancellation(dir,job);
            // Even if writing the human-readable status fails, the tombstone still disarms the helper.
            try{writeStatus(dir,"CANCEL_PENDING","Cancellation requested; waiting for helper acknowledgment");}catch(IOException ignored){ }
            if(finishCancellation(id,dir,job))return new CancelResult(id,true,"Reservation durably cancelled; stopping later will not restore it.");
        }
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        do {
            try(RestoreJobState.Gate gate=RestoreJobState.lock(dir,Duration.ofMillis(100))) {
                if(gate!=null && finishCancellation(id,dir,job))return new CancelResult(id,true,"Reservation durably cancelled; stopping later will not restore it.");
            }
            try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }while(System.nanoTime()<deadline);
        return new CancelResult(id,false,"Cancellation is pending, not acknowledged. Keep the server running; the reservation and backup remain protected.");
    }
    private boolean finishCancellation(String id,Path dir,Properties job)throws IOException {
        if(!RestoreJobState.acknowledged(dir,job)) {
            String source;
            if(!processes.containsKey(id) && Objects.equals(ownedJobId,id))source="NOT_LAUNCHED";
            else if(confirmedDead(id,dir,job))source="CONFIRMED_HELPER_DEATH";
            else return false;
            try{RestoreJobState.acknowledgeCancellation(dir,job,source);}
            catch(IOException e){if(!RestoreJobState.acknowledged(dir,job))throw e;}
        }
        clearActive(id);return true;
    }
    private boolean confirmedDead(String id,Path dir,Properties job)throws IOException {
        Process process=processes.get(id);return process!=null?!process.isAlive():RestoreJobState.confirmedDead(dir,job);
    }
    /** Compatibility alias; no shutdown dispatch is performed anywhere in this coordinator. */
    public synchronized void abortLaunch(String id)throws IOException {
        CancelResult result=cancelCommitted(id);if(!result.cancelled())throw new IOException(result.message());
    }
    /** What the server platform could establish when the plugin was disabled. */
    public enum ShutdownEvidence {
        /** The server API reported that its shutdown path was entered. */
        SERVER_STOPPING,
        /** No shutdown indicator exists on this server; the helper additionally requires a prompt JVM exit. */
        PLUGIN_DISABLED
    }
    /** Compatibility form for callers that have proven the server shutdown path was entered. */
    public synchronized void noteNormalShutdown()throws IOException {
        noteNormalShutdown(ShutdownEvidence.SERVER_STOPPING);
    }
    /** Records authenticated stop intent for this JVM's own waiting reservation; no world writes or lock release. */
    public synchronized void noteNormalShutdown(ShutdownEvidence evidence)throws IOException {
        Objects.requireNonNull(evidence,"evidence");
        if(ownedJobId==null || !Files.exists(activeFile(),RestoreFiles.NOFOLLOW))return;
        Path dir=jobDirectory(ownedJobId);Properties job=RestoreFiles.load(dir.resolve("job.properties"));
        try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
            RestoreJobState.requireActive(state,job);
            if(RestoreJobState.hasCancellation(dir))return;
            if(!RestoreJobState.waiting(RestoreFiles.status(dir)))return;
            RestoreJobState.noteNormalShutdown(dir,job,evidence==ShutdownEvidence.SERVER_STOPPING
                    ?RestoreJobState.EVIDENCE_SERVER_STOPPING:RestoreJobState.EVIDENCE_PLUGIN_DISABLED);
        }
    }
    /** True when this coordinator reuses a root guard acquired by an earlier plugin instance of this JVM (plugin reload). */
    public boolean inheritedRootGuard(){return lifetimeLease.inherited;}
    public synchronized Optional<Receipt> consumeVerifiedReceipt()throws IOException {
        if(startupDisposition!=StartupDisposition.READY)throw new IOException(startupDiagnostic);
        if(!Files.exists(activeFile(),RestoreFiles.NOFOLLOW))return Optional.empty();
        Properties a=RestoreFiles.load(activeFile());String id=RestoreFiles.required(a,"jobId");Path dir=jobDirectory(id);
        if(!RestoreFiles.status(dir).equals("SUCCESS"))return Optional.empty();
        Properties job=RestoreFiles.load(dir.resolve("job.properties"));Receipt result=verifiedReceipt(id,dir,job);
        Properties acknowledged=new Properties();acknowledged.setProperty("consumedAt",Instant.now().toString());RestoreFiles.write(dir.resolve("receipt-consumed.properties"),acknowledged);
        clearActive(id);return Optional.of(result);
    }
    private Receipt verifiedReceipt(String id,Path dir,Properties job)throws IOException {
        validateBoundJob(job,id);RestoreJobState.requireActive(state,job);if(RestoreJobState.hasCancellation(dir))throw new IOException("A cancelled job cannot have a successful restore receipt");
        Properties receipt=RestoreFiles.load(dir.resolve("receipt.properties"));
        for(String key:List.of("jobId","backupId","serverRoot","archiveSha256","manifestSha256"))if(!RestoreFiles.required(job,key).equals(RestoreFiles.required(receipt,key)))throw new IOException("Receipt does not match committed job: "+key);
        if(!root.toString().equals(RestoreFiles.required(receipt,"serverRoot")))throw new IOException("Receipt is for another server");
        if(!RestoreFiles.constantEquals(RestoreFiles.required(receipt,"authentication"),RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),RestoreFiles.receiptPayload(receipt))))throw new IOException("Receipt authentication failed");
        Path rollback=Path.of(RestoreFiles.required(receipt,"rollbackDirectory"));if(!rollback.toString().equals(RestoreFiles.required(job,"rollback")))throw new IOException("Receipt rollback path mismatch");
        // The preserved original is the administrator's to delete once the restore is confirmed. Its
        // absence takes nothing away from the authenticated receipt; only a symlink there is refused.
        if(Files.exists(rollback,RestoreFiles.NOFOLLOW))RestoreFiles.directory(rollback,false);
        try{return new Receipt(id,RestoreFiles.required(receipt,"backupId"),Instant.parse(RestoreFiles.required(receipt,"completedAt")),rollback);}catch(RuntimeException e){throw new IOException("Invalid receipt timestamp",e);}
    }
    private void validateBoundJob(Properties job,String id)throws IOException {
        if(!id.equals(RestoreFiles.required(job,"jobId")) || !root.toString().equals(RestoreFiles.required(job,"serverRoot")) || !state.toString().equals(RestoreFiles.required(job,"stateDirectory")))throw new IOException("Restore job belongs to another root/state directory");
        if(!Set.of("1","2").contains(RestoreFiles.required(job,"formatVersion")))throw new IOException("Unknown restore job format");
        if(!backups.equals(Path.of(RestoreFiles.required(job,"backupDirectory"))))throw new IOException("Restore job belongs to another backup directory");
        if(!RestoreFiles.required(job,"backupId").matches("[A-Za-z0-9._-]{1,160}"))throw new IOException("Invalid backup identifier");
        for(String key:List.of("nonce","archiveSha256","manifestSha256"))
            if(!RestoreFiles.required(job,key).matches("[0-9a-f]{64}"))throw new IOException("Invalid restore authentication/hash field: "+key);
        Path archive=Path.of(RestoreFiles.required(job,"archive"));
        if(!archive.isAbsolute() || !archive.equals(archive.normalize()) || !archive.startsWith(backups) || archive.equals(backups))throw new IOException("Archive is outside the bound backup directory");
        for(String kind:List.of("stage","rollback","failed"))boundSibling(job,kind);
        try {
            if(Long.parseLong(RestoreFiles.required(job,"parentPid"))<=0)throw new IOException("Invalid recorded parent PID");
            Instant.parse(RestoreFiles.required(job,"parentStartedAt"));
            if(Integer.parseInt(RestoreFiles.required(job,"entryCount"))<0 || Long.parseLong(RestoreFiles.required(job,"totalBytes"))<0)throw new IOException("Invalid restore scope");
        } catch(RuntimeException e) {throw new IOException("Invalid restore job identity/scope",e);}
    }
    private void clearActive(String id)throws IOException {
        if(Files.exists(activeFile(),RestoreFiles.NOFOLLOW)) {
            Properties active=RestoreFiles.load(activeFile());if(!id.equals(RestoreFiles.required(active,"jobId")))throw new IOException("Active restore changed; refusing cleanup");
            Files.delete(activeFile());RestoreFiles.syncDirectory(state);
        }
        committedBackupId=null;
    }
    private void writeStatus(Path dir,String status,String message)throws IOException {
        RestoreFiles.status(dir,status,message);
        logger.accept("[restore] "+status+": "+RestoreFiles.load(dir.resolve("status.properties")).getProperty("message",""));
    }
    private Path activeFile(){return state.resolve("active-restore.properties");}
    private Path jobDirectory(String id)throws IOException {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("Invalid restore job identifier");
        return state.resolve("restore-jobs").resolve(id);
    }
}
