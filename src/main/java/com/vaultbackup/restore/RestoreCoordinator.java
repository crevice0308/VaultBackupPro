// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Online preparation only. The root sentinel is intentionally retained until actual JVM exit. */
public final class RestoreCoordinator {
    public record Reservation(String token, String backupId, String archiveSha256,
                              int entryCount, long totalBytes, Instant expiresAt) {}
    public record LaunchResult(String jobId, Path jobFile, boolean helperReady, String message) {}
    public record Receipt(String jobId, String backupId, Instant completedAt, Path rollbackDirectory) {}
    public record Status(String jobId, String backupId, String state, String message, Path jobFile, boolean cancellable) {}
    public record CancelResult(String jobId, boolean cancelled, String message) {}
    private record Pending(Reservation value, Path archive, String manifestSha256) {}
    private final Path root, backups, state, helperJar;
    private String language;
    private final Map<String,Pending> pending = new HashMap<>();
    private final Map<String,String> confirmedTokens = new HashMap<>();
    private final Map<String,Process> processes = new HashMap<>();
    private final FileChannel lifetimeChannel;
    private final FileLock lifetimeLock;
    private final long parentPid;
    private final Instant parentStartedAt;
    private String committedBackupId, startupFailure, ownedJobId;
    private static final Set<String> SAFE_TERMINAL=Set.of("EXPIRED","FAILED_BEFORE_SWAP","FAILED_ROLLED_BACK","RECOVERED");

    public RestoreCoordinator(Path serverRoot, Path backupDirectory, Path stateDirectory, Path helperJar) throws IOException {
        this(serverRoot,backupDirectory,stateDirectory,helperJar,"en");
    }
    public RestoreCoordinator(Path serverRoot, Path backupDirectory, Path stateDirectory, Path helperJar, String language) throws IOException {
        root=RestoreFiles.directory(serverRoot,false);
        if(root.getParent()==null)throw new IOException("A filesystem root cannot be restored");
        backups=RestoreFiles.directory(backupDirectory,true);state=RestoreFiles.directory(stateDirectory,true);
        RestoreFiles.external(root,backups,"Backup directory");RestoreFiles.external(root,state,"State directory");
        this.helperJar=helperJar.toAbsolutePath().normalize();this.language=language!=null && language.toLowerCase(Locale.ROOT).startsWith("ja")?"ja":"en";
        if(this.helperJar.startsWith(root))throw new IOException("The offline helper must be outside server-root");
        parentPid=ProcessHandle.current().pid();parentStartedAt=ProcessHandle.current().info().startInstant().orElseThrow(()->new IOException("Cannot determine server JVM start instant"));
        RestoreFiles.directory(state.resolve("restore-jobs"),true);
        FileChannel channel=FileChannel.open(state.resolve(RestoreFiles.guardName(root)),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException e){channel.close();throw new IOException("Server root already has a lifetime restore guard; JVM restart required after plugin reload",e);}
        if(lock==null){channel.close();throw new IOException("Offline restore or another server holds the root guard. Do not start this server.");}
        try {
            Path active=activeFile();
            if(Files.exists(active,RestoreFiles.NOFOLLOW)) {
                Properties a=RestoreFiles.load(active);String id=RestoreFiles.required(a,"jobId");Path dir=jobDirectory(id);
                Properties job=RestoreFiles.load(dir.resolve("job.properties"));validateBoundJob(job,id);RestoreJobState.requireActive(state,job);
                committedBackupId=RestoreFiles.required(a,"backupId");
                try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
                    String current=RestoreFiles.status(dir);
                    if(RestoreJobState.hasCancellation(dir)) {
                        if(!RestoreJobState.acknowledged(dir,job)) {
                            if(!RestoreJobState.confirmedDead(dir,job))throw new IOException("Cancellation is unacknowledged; keep server stopped and inspect job "+id);
                            RestoreJobState.acknowledgeCancellation(dir,job,"CONFIRMED_HELPER_DEATH");
                        }
                        startupFailure="Restore "+id+" was cancelled; no restore was applied.";clearActive(id);
                    } else if(current.equals("SUCCESS")) {
                        verifiedReceipt(id,dir,job); // A status string alone must never permit startup.
                    } else if(SAFE_TERMINAL.contains(current) && RestoreJobState.confirmedDead(dir,job)) {
                        startupFailure="Restore "+id+" ended with "+current+": "+RestoreFiles.load(dir.resolve("status.properties")).getProperty("message","");
                        RestoreJobState.requestCancellation(dir,job);RestoreJobState.acknowledgeCancellation(dir,job,"CONFIRMED_HELPER_DEATH");clearActive(id);
                    } else throw new IOException("Unresolved offline restore "+id+" ("+current+"). Stop the server and inspect/recover this job before starting.");
                }
            }
        }catch(IOException | RuntimeException e){lock.release();channel.close();throw e;}
        lifetimeChannel=channel;lifetimeLock=lock;
        Runtime.getRuntime().addShutdownHook(new Thread(()->{if(lifetimeChannel.isOpen() && lifetimeLock.isValid()){ /* Retained until OS process teardown. */ }},"VaultBackupPro-lifetime-guard"));
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
        if(committedBackupId!=null || Files.exists(activeFile(),RestoreFiles.NOFOLLOW))throw new IOException("An offline restore reservation already exists; cancel or resolve it first");
    }
    /** Confirmation reserves work; it never requests server shutdown or a restart. */
    public synchronized LaunchResult confirm(String token)throws IOException {
        if(confirmedTokens.containsKey(token))return launchResult(confirmedTokens.get(token));
        ensureNoCommitted();Pending p=pending.remove(token);
        if(p==null || !p.value.expiresAt().isAfter(Instant.now()))throw new IOException("Restore confirmation is missing, used, or expired");
        if(!RestoreFiles.sha(p.archive).equals(p.value.archiveSha256()))throw new IOException("Backup changed after preview");
        String id=UUID.randomUUID().toString();Path dir=jobDirectory(id);RestoreFiles.createPrivateDirectory(dir);Properties job=new Properties();
        job.setProperty("formatVersion","2");job.setProperty("jobId",id);job.setProperty("backupId",p.value.backupId());job.setProperty("language",language);
        job.setProperty("shutdownPolicy","MANUAL_NORMAL_STOP");
        job.setProperty("serverRoot",root.toString());job.setProperty("backupDirectory",backups.toString());job.setProperty("stateDirectory",state.toString());
        job.setProperty("archive",p.archive.toString());job.setProperty("archiveSha256",p.value.archiveSha256());job.setProperty("manifestSha256",p.manifestSha256);
        job.setProperty("entryCount",Integer.toString(p.value.entryCount()));job.setProperty("totalBytes",Long.toString(p.value.totalBytes()));
        job.setProperty("parentPid",Long.toString(parentPid));job.setProperty("parentStartedAt",parentStartedAt.toString());job.setProperty("nonce",RestoreFiles.randomSecret());job.setProperty("createdAt",Instant.now().toString());
        for(String kind:List.of("stage","rollback","failed"))job.setProperty(kind,root.resolveSibling(root.getFileName()+".vaultbackup-"+kind+"-"+id).toString());
        RestoreFiles.write(dir.resolve("job.properties"),job);RestoreFiles.status(dir,"COMMITTED","Starting helper for a manual-stop reservation");
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
        }catch(IOException e){return failedHandshake(id,process,e.getMessage());}
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
        }catch(IOException ignored){ /* Keep uncertain job pinned and attempt confirmed termination below. */ }
        if(process!=null) {
            process.destroyForcibly();boolean dead=false;
            try{dead=process.waitFor(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(dead) {
                try {CancelResult result=cancelCommitted(id);if(result.cancelled())return new LaunchResult(id,file,false,reason+". Helper was terminated and reservation durably cancelled; the server remains running.");}
                catch(IOException ignored){ }
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
            try{current=RestoreFiles.status(dir);}catch(IOException e){current="UNKNOWN";}
            if(!RestoreJobState.waiting(current) && !SAFE_TERMINAL.contains(current) && !current.equals("UNKNOWN"))return new CancelResult(id,false,"Job is "+current+"; it cannot be cancelled after application starts.");
            RestoreJobState.requestCancellation(dir,job);
            // Even if writing the human-readable status fails, the tombstone still disarms the helper.
            try{RestoreFiles.status(dir,"CANCEL_PENDING","Cancellation requested; waiting for helper acknowledgment");}catch(IOException ignored){ }
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
    }
    private void clearActive(String id)throws IOException {
        if(Files.exists(activeFile(),RestoreFiles.NOFOLLOW)) {
            Properties active=RestoreFiles.load(activeFile());if(!id.equals(RestoreFiles.required(active,"jobId")))throw new IOException("Active restore changed; refusing cleanup");
            Files.delete(activeFile());RestoreFiles.syncDirectory(state);
        }
        committedBackupId=null;
    }
    private Path activeFile(){return state.resolve("active-restore.properties");}
    private Path jobDirectory(String id)throws IOException {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("Invalid restore job identifier");
        return state.resolve("restore-jobs").resolve(id);
    }
}
