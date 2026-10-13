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
    public record StartupResult(StartupDisposition disposition, RestoreCoordinator coordinator, String message) {}
    private record Pending(Reservation value, Path archive, String manifestSha256) {}
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
    private static final Set<String> SAFE_TERMINAL=Set.of("EXPIRED","FAILED_BEFORE_SWAP");

    /** Acquire the process-lifetime guard before inspecting any mutable startup journal. */
    public static StartupResult start(Path serverRoot,Path backupDirectory,Path stateDirectory,Path helperJar,
                                      String language,Consumer<String> logger) {
        RestoreCoordinator coordinator=null;
        try {
            RestoreRootLease lease=RestoreRootLease.acquire(serverRoot,stateDirectory);
            coordinator=new RestoreCoordinator(lease,backupDirectory,helperJar,language,logger);
            return coordinator.reconcileStartup();
        } catch(IOException | RuntimeException e) {
            // An acquired lease stays strongly retained by its shutdown hook, even
            // when construction or journal validation fails and no instance returns.
            return new StartupResult(StartupDisposition.HARD_CONFLICT,coordinator,
                    "Restore startup conflict: "+e.getMessage());
        }
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
                if(Files.exists(dir.resolve("recovery-receipt.properties"),RestoreFiles.NOFOLLOW)) {
                    // The authenticated receipt is emitted only after the helper
                    // physically returns the preserved original tree to root.
                    // It also handles a failure writing the final human-readable status.
                    RestoreJobState.verifiedRecovery(dir,job);
                } else requirePreSwap(dir,job,current);
                // Only after positively establishing a pre-swap/restored root may failure
                // to persist/acknowledge a cancellation become degraded startup.
                try {
                    RestoreJobState.requestCancellation(dir,job);
                    if(finishStartupCancellation(id,dir,job)) {
                        startupFailure="Stale restore "+id+" was cancelled; the current server data was kept.";
                        return startup(StartupDisposition.READY,startupFailure);
                    }
                } catch(IOException | RuntimeException e) {
                    return startup(StartupDisposition.GUARDED_DEGRADED,
                            "Stale restore "+id+" is blocked by this JVM's root guard, but cancellation is unresolved: "+e.getMessage());
                }
            }
            // Give a live old helper an opportunity to authenticate the durable
            // tombstone. Do not invent process ownership or a NOT_LAUNCHED ack.
            long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            do {
                try(RestoreJobState.Gate gate=RestoreJobState.lock(dir,Duration.ofMillis(100))) {
                    if(gate!=null && finishStartupCancellation(id,dir,job)) {
                        startupFailure="Stale restore "+id+" was cancelled; the current server data was kept.";
                        return startup(StartupDisposition.READY,startupFailure);
                    }
                } catch(IOException | RuntimeException e) {
                    return startup(StartupDisposition.GUARDED_DEGRADED,
                            "Stale restore "+id+" is safely guarded, but cancellation acknowledgment failed: "+e.getMessage());
                }
                try {Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            } while(System.nanoTime()<deadline);
            return startup(StartupDisposition.GUARDED_DEGRADED,
                    "Stale restore "+id+" is safely guarded. Cancellation is pending; backup mutations stay disabled until the journal is resolved.");
        } catch(IOException | RuntimeException e) {
            return startup(StartupDisposition.HARD_CONFLICT,"Restore startup conflict: "+e.getMessage());
        }
    }
    private void requirePreSwap(Path dir,Properties job,String current)throws IOException {
        if(!RestoreJobState.waiting(current) && !Set.of("EXPIRED","FAILED_BEFORE_SWAP","CANCELLED").contains(current))
            throw new IOException("Unresolved offline restore "+RestoreFiles.required(job,"jobId")+" ("+current+"). Inspect/recover this job while stopped.");
        if(current.equals("CANCELLED") && !RestoreJobState.hasCancellation(dir))throw new IOException("Cancelled status has no authenticated cancellation tombstone");
        // A status string alone cannot prove that the original root was not moved.
        // Unexpected siblings/receipts are preserved for stopped-server inspection.
        for(String kind:List.of("rollback","failed")) {
            Path path=boundSibling(job,kind);
            if(Files.exists(path,RestoreFiles.NOFOLLOW))throw new IOException("Ambiguous pre-swap journal: "+kind+" tree exists at "+path);
        }
        Path stage=boundSibling(job,"stage");
        if(Files.exists(stage,RestoreFiles.NOFOLLOW)) {
            if(!current.equals("FAILED_BEFORE_SWAP") && !(current.equals("CANCELLED") && RestoreJobState.acknowledged(dir,job)))
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
        clearActive(id);return true;
    }
    public Reservation reserve(String backupId,Path archive,String expectedSha256,Duration ttl)throws IOException {
        if(backupId==null || !backupId.matches("[A-Za-z0-9._-]{1,160}"))throw new IOException("Invalid backup identifier");
        if(ttl==null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(15))>0)throw new IOException("Restore confirmation expiry must be between 1 second and 15 minutes");
        Path file=archive.toAbsolutePath().normalize();RestoreFiles.regular(file);
        if(!file.startsWith(backups) || file.equals(backups))throw new IOException("Archive is outside the configured backup directory");
        synchronized(this){ensureNoCommitted();}
        RestoreArchive.Verified verified=RestoreArchive.verify(file,expectedSha256);
        Reservation value=new Reservation(RestoreFiles.randomSecret().substring(0,24),backupId,expectedSha256,verified.entries().size(),verified.totalBytes(),Instant.now().plus(ttl));
        synchronized(this){ensureNoCommitted();pending.clear();pending.put(value.token(),new Pending(value,file,verified.manifestSha256()));}return value;
    }
    public synchronized void setLanguage(String value)throws IOException {
        ensureNoCommitted();language=value!=null && value.toLowerCase(Locale.ROOT).startsWith("ja")?"ja":"en";
    }
    public Optional<String> startupFailure(){return Optional.ofNullable(startupFailure);}
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
        if(!RestoreFiles.sha(p.archive).equals(p.value.archiveSha256()))throw new IOException("Backup changed after preview");
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
        try{RestoreFiles.write(activeFile(),active);}catch(IOException e){return failedHandshake(id,null,"Could not publish restore reservation: "+e.getMessage());}
        Process process=null;
        try {
            RestoreFiles.regular(helperJar);
            String executable=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
            Path java=Path.of(System.getProperty("java.home"),"bin",executable);
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
                process=new ProcessBuilder(java.toString(),"-jar",helperJar.toString(),"--job",dir.resolve("job.properties").toString())
                        .directory(state.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("helper.log").toFile())).start();
                processes.put(id,process);RestoreJobState.recordProcess(dir,job,process.toHandle());
            }
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(System.nanoTime()<deadline) {
                if(RestoreFiles.status(dir).equals("WAITING_FOR_MANUAL_STOP") && process.isAlive())return launchResult(id);
                if(!process.isAlive())break;
                try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
            return failedHandshake(id,process,"Helper did not acknowledge readiness");
        }catch(IOException | RuntimeException e){return failedHandshake(id,process,e.getMessage());}
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
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
                RestoreJobState.requestCancellation(dir,job);RestoreJobState.acknowledgeCancellation(dir,job,"CONFIRMED_HELPER_DEATH");clearActive(id);return Optional.empty();
            }
        }
        if(RestoreJobState.waiting(current)) {
            try {
                if(confirmedDead(id,dir,job))return Optional.of(new Status(id,RestoreFiles.required(a,"backupId"),"HELPER_EXITED","The helper exited while waiting. No restore will run; cancel this reservation before stopping and then reserve again.",dir.resolve("job.properties"),Objects.equals(ownedJobId,id)));
            }catch(IOException e){return Optional.of(new Status(id,RestoreFiles.required(a,"backupId"),"HELPER_UNCERTAIN","Helper identity is uncertain. Keep the server running and resolve/cancel this reservation: "+e.getMessage(),dir.resolve("job.properties"),Objects.equals(ownedJobId,id)));}
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
    /** Called only when the platform proves its server shutdown path was entered; no world writes or lock release. */
    public synchronized void noteNormalShutdown()throws IOException {
        if(ownedJobId==null || !Files.exists(activeFile(),RestoreFiles.NOFOLLOW))return;
        Path dir=jobDirectory(ownedJobId);Properties job=RestoreFiles.load(dir.resolve("job.properties"));
        try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
            RestoreJobState.requireActive(state,job);
            if(RestoreJobState.hasCancellation(dir))return;
            if(!RestoreJobState.waiting(RestoreFiles.status(dir)))return;
            RestoreJobState.noteNormalShutdown(dir,job);
        }
    }
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
        Path rollback=Path.of(RestoreFiles.required(receipt,"rollbackDirectory"));if(!rollback.toString().equals(RestoreFiles.required(job,"rollback")))throw new IOException("Receipt rollback path mismatch");RestoreFiles.directory(rollback,false);
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
