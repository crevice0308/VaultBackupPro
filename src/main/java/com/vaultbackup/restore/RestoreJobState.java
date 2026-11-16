// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** The job gate serializes cancellation with application; it is NEVER held while waiting for JVM exit. */
final class RestoreJobState {
    private RestoreJobState() {}
    /** The channel belongs to the JVM and stays open (see RestoreFiles.lockChannel); only the lock is released. */
    record Gate(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { lock.release(); }
    }
    static Gate lock(Path dir, Duration timeout) throws IOException {
        RestoreFiles.directory(dir, false);
        FileChannel channel=RestoreFiles.lockChannel(dir.resolve("job.lock"));
        long deadline=System.nanoTime()+timeout.toNanos();
        do {
            try { FileLock lock=channel.tryLock(); if(lock!=null)return new Gate(channel,lock); }
            catch(OverlappingFileLockException ignored) { /* held by another thread of this JVM */ }
            if(System.nanoTime()>=deadline)return null;
            try {Thread.sleep(20);} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("Interrupted while waiting for restore job gate",e);}
        } while(true);
    }
    static Gate requireLock(Path dir) throws IOException {
        Gate gate=lock(dir,Duration.ofSeconds(5));
        if(gate==null)throw new IOException("Restore application is in progress; job state is locked");
        return gate;
    }
    static boolean hasCancellation(Path dir) {return Files.exists(dir.resolve("cancel.properties"),RestoreFiles.NOFOLLOW);}
    static Properties cancellation(Path dir,Properties job)throws IOException {
        Properties p=RestoreFiles.load(dir.resolve("cancel.properties"));
        if(!RestoreFiles.required(job,"jobId").equals(RestoreFiles.required(p,"jobId")))throw new IOException("Cancellation belongs to another job");
        String payload=String.join("\n",RestoreFiles.required(p,"jobId"),RestoreFiles.required(p,"cancelId"),RestoreFiles.required(p,"cancelledAt"));
        if(!RestoreFiles.constantEquals(RestoreFiles.required(p,"authentication"),RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),payload)))throw new IOException("Cancellation authentication is uncertain; job remains blocked");
        return p;
    }
    /** Caller holds job gate. A durable tombstone is never removed or overwritten. */
    static void requestCancellation(Path dir,Properties job)throws IOException {
        if(hasCancellation(dir)) {cancellation(dir,job);RestoreFiles.syncDirectory(dir);return;}
        Properties p=new Properties();p.setProperty("jobId",RestoreFiles.required(job,"jobId"));
        p.setProperty("cancelId",RestoreFiles.randomSecret());p.setProperty("cancelledAt",Instant.now().toString());
        p.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),String.join("\n",p.getProperty("jobId"),p.getProperty("cancelId"),p.getProperty("cancelledAt"))));
        RestoreFiles.write(dir.resolve("cancel.properties"),p);
    }
    /** Caller holds the same gate used by application, and must never apply after this call. */
    static void acknowledgeCancellation(Path dir,Properties job,String source)throws IOException {
        Properties cancel=cancellation(dir,job),ack=new Properties();
        ack.setProperty("jobId",RestoreFiles.required(job,"jobId"));ack.setProperty("cancelId",RestoreFiles.required(cancel,"cancelId"));
        ack.setProperty("source",source);ack.setProperty("acknowledgedAt",Instant.now().toString());
        ack.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),ackPayload(ack)));
        RestoreFiles.write(dir.resolve("cancel-ack.properties"),ack);
        RestoreFiles.status(dir,"CANCELLED","Cancellation acknowledged. This job can never restore, even after the server stops.");
    }
    static boolean acknowledged(Path dir,Properties job)throws IOException {
        if(!hasCancellation(dir))return false;
        Properties cancel=cancellation(dir,job);Path file=dir.resolve("cancel-ack.properties");
        if(!Files.exists(file,RestoreFiles.NOFOLLOW))return false;
        Properties ack=RestoreFiles.load(file);
        if(!RestoreFiles.required(job,"jobId").equals(RestoreFiles.required(ack,"jobId"))
                || !RestoreFiles.required(cancel,"cancelId").equals(RestoreFiles.required(ack,"cancelId")))throw new IOException("Cancellation acknowledgment does not match this request");
        if(!Set.of("HELPER","CONFIRMED_HELPER_DEATH","NOT_LAUNCHED").contains(RestoreFiles.required(ack,"source")))throw new IOException("Invalid cancellation acknowledgment source");
        if(!RestoreFiles.constantEquals(RestoreFiles.required(ack,"authentication"),RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),ackPayload(ack))))throw new IOException("Cancellation acknowledgment authentication failed");
        // A previous atomic rename may have succeeded before directory fsync
        // failed. Re-establish durability before an acknowledgment authorizes
        // clearing the active marker and unpinning its backup.
        RestoreFiles.syncDirectory(dir);
        return true;
    }
    private static String ackPayload(Properties p)throws IOException {
        return String.join("\n",RestoreFiles.required(p,"jobId"),RestoreFiles.required(p,"cancelId"),RestoreFiles.required(p,"source"),RestoreFiles.required(p,"acknowledgedAt"));
    }
    static void recordProcess(Path dir,Properties job,ProcessHandle process)throws IOException {
        Properties p=new Properties();p.setProperty("jobId",RestoreFiles.required(job,"jobId"));p.setProperty("pid",Long.toString(process.pid()));
        p.setProperty("startedAt",process.info().startInstant().orElseThrow(()->new IOException("Cannot establish helper process identity")).toString());
        p.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),processPayload(p)));
        RestoreFiles.write(dir.resolve("helper-process.properties"),p);
    }
    static boolean confirmedDead(Path dir,Properties job)throws IOException {
        Path file=dir.resolve("helper-process.properties");if(!Files.exists(file,RestoreFiles.NOFOLLOW))return false;
        Properties p=RestoreFiles.load(file);
        if(!RestoreFiles.required(job,"jobId").equals(RestoreFiles.required(p,"jobId"))
                || !RestoreFiles.constantEquals(RestoreFiles.required(p,"authentication"),RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),processPayload(p))))throw new IOException("Helper process identity is unauthenticated");
        RestoreFiles.Liveness liveness;
        try {
            liveness=RestoreFiles.liveness(Long.parseLong(RestoreFiles.required(p,"pid")),Instant.parse(RestoreFiles.required(p,"startedAt")));
        }catch(RuntimeException e){throw new IOException("Invalid helper process identity",e);}
        // A reused PID (for example after a host reboot) is a different process: the helper is gone.
        if(liveness==RestoreFiles.Liveness.UNCERTAIN)throw new IOException("Helper process cannot be identified; cancellation is not acknowledged");
        return liveness==RestoreFiles.Liveness.GONE;
    }
    /**
     * Written by the helper before it moves anything: which directory is the original server root.
     * A directory that merely exists at the rollback path is not proof that the original was moved
     * there; another local user who can write the parent directory could have created it.
     */
    static void recordRootIdentity(Path dir,Properties job,Path root)throws IOException {
        Properties p=new Properties();p.setProperty("jobId",RestoreFiles.required(job,"jobId"));
        p.setProperty("root",RestoreFiles.fileIdentity(root));
        p.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),identityPayload(p)));
        RestoreFiles.write(dir.resolve("root-identity.properties"),p);
    }
    /** What may be concluded about a path from the recorded identity of the original root. */
    enum Original { YES, NO, UNKNOWN }
    /**
     * UNKNOWN when no identity was recorded (a job that never reached the swap, or one written before
     * 1.3.0) or the filesystem has no file identities. Callers then fall back to the journal alone.
     */
    static Original isOriginalRoot(Path dir,Properties job,Path candidate)throws IOException {
        Path file=dir.resolve("root-identity.properties");
        if(!Files.exists(file,RestoreFiles.NOFOLLOW))return Original.UNKNOWN;
        Properties p=RestoreFiles.load(file);
        if(!RestoreFiles.required(job,"jobId").equals(RestoreFiles.required(p,"jobId"))
                || !RestoreFiles.constantEquals(RestoreFiles.required(p,"authentication"),RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),identityPayload(p))))
            throw new IOException("Recorded root identity is unauthenticated");
        String recorded=p.getProperty("root","");
        if(recorded.isEmpty())return Original.UNKNOWN;
        if(!Files.isDirectory(candidate,RestoreFiles.NOFOLLOW) || Files.isSymbolicLink(candidate))return Original.NO;
        String actual=RestoreFiles.fileIdentity(candidate);
        if(actual.isEmpty())return Original.UNKNOWN;
        return actual.equals(recorded)?Original.YES:Original.NO;
    }
    private static String identityPayload(Properties p)throws IOException {
        return String.join("\n",RestoreFiles.required(p,"jobId"),p.getProperty("root",""));
    }
    private static String processPayload(Properties p)throws IOException {
        return String.join("\n",RestoreFiles.required(p,"jobId"),RestoreFiles.required(p,"pid"),RestoreFiles.required(p,"startedAt"));
    }
    /** Called only after the helper atomically returned the preserved original tree to root. */
    static void recordCompletedRecovery(Path dir,Properties job,String outcome)throws IOException {
        if(!Set.of("FAILED_ROLLED_BACK","RECOVERED").contains(outcome))throw new IOException("Invalid recovery outcome");
        requireRecoveredTree(job);
        if(Files.exists(dir.resolve("receipt.properties"),RestoreFiles.NOFOLLOW))throw new IOException("Successful restore receipt conflicts with completed recovery");
        Properties receipt=new Properties();receipt.setProperty("formatVersion","1");
        for(String key:List.of("jobId","backupId","serverRoot","archiveSha256","manifestSha256","stage","rollback","failed"))
            receipt.setProperty(key,RestoreFiles.required(job,key));
        receipt.setProperty("outcome",outcome);receipt.setProperty("completedAt",Instant.now().toString());
        receipt.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),RestoreFiles.recoveryPayload(receipt)));
        RestoreFiles.write(dir.resolve("recovery-receipt.properties"),receipt);
    }
    static Properties verifiedRecovery(Path dir,Properties job)throws IOException {
        Properties receipt=RestoreFiles.load(dir.resolve("recovery-receipt.properties"));
        if(!RestoreFiles.required(receipt,"formatVersion").equals("1") || !Set.of("FAILED_ROLLED_BACK","RECOVERED").contains(RestoreFiles.required(receipt,"outcome")))throw new IOException("Invalid completed-recovery receipt format/outcome");
        for(String key:List.of("jobId","backupId","serverRoot","archiveSha256","manifestSha256","stage","rollback","failed"))
            if(!RestoreFiles.required(job,key).equals(RestoreFiles.required(receipt,key)))throw new IOException("Recovery receipt does not match job: "+key);
        if(!RestoreFiles.constantEquals(RestoreFiles.required(receipt,"authentication"),RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),RestoreFiles.recoveryPayload(receipt))))throw new IOException("Completed-recovery receipt authentication failed");
        try {Instant.parse(RestoreFiles.required(receipt,"completedAt"));}catch(RuntimeException e){throw new IOException("Invalid completed-recovery timestamp",e);}
        requireRecoveredTree(job);
        if(Files.exists(dir.resolve("receipt.properties"),RestoreFiles.NOFOLLOW))throw new IOException("Successful restore receipt conflicts with completed recovery");
        return receipt;
    }
    private static void requireRecoveredTree(Properties job)throws IOException {
        Path root=RestoreFiles.directory(Path.of(RestoreFiles.required(job,"serverRoot")),false);
        String id=RestoreFiles.required(job,"jobId");
        for(String kind:List.of("stage","rollback","failed")) {
            Path path=Path.of(RestoreFiles.required(job,kind));
            if(!path.equals(root.resolveSibling(root.getFileName()+".vaultbackup-"+kind+"-"+id)))throw new IOException("Invalid recovery sibling binding: "+kind);
            if(Files.exists(path,RestoreFiles.NOFOLLOW)) {
                if(kind.equals("rollback"))throw new IOException("Original rollback tree remains; recovery is not complete");
                RestoreFiles.directory(path,false);
            }
        }
    }
    /** The server's own API reported that its shutdown path was entered (Paper: Server#isStopping). */
    static final String EVIDENCE_SERVER_STOPPING="SERVER_STOPPING";
    /**
     * The plugin was disabled on a server that offers no shutdown indicator (Spigot). A reload or a
     * plugin manager disables plugins too, so this counts as a stop only if the JVM exits promptly.
     */
    static final String EVIDENCE_PLUGIN_DISABLED="PLUGIN_DISABLED";
    /** Longest accepted time between a PLUGIN_DISABLED marker and the observed JVM exit. */
    static final Duration DISABLED_EXIT_GRACE=Duration.ofMinutes(10);
    /** Tolerated wall-clock disagreement for a marker that appears to come from the future. */
    private static final Duration CLOCK_TOLERANCE=Duration.ofMinutes(2);

    static void noteNormalShutdown(Path dir,Properties job,String evidence)throws IOException {
        if(!Set.of(EVIDENCE_SERVER_STOPPING,EVIDENCE_PLUGIN_DISABLED).contains(evidence))throw new IOException("Invalid shutdown evidence");
        Properties p=new Properties();
        for(String key:List.of("jobId","parentPid","parentStartedAt"))p.setProperty(key,RestoreFiles.required(job,key));
        p.setProperty("requestedAt",Instant.now().toString());
        p.setProperty("evidence",evidence);
        p.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),shutdownPayload(p)));
        RestoreFiles.write(dir.resolve("normal-stop.properties"),p);
    }
    /** Called by the helper only after it has observed the recorded server JVM's exit. */
    static void requireNormalShutdown(Path dir,Properties job)throws IOException {
        requireNormalShutdown(dir,job,Instant.now());
    }
    static void requireNormalShutdown(Path dir,Properties job,Instant observedExit)throws IOException {
        Properties p=RestoreFiles.load(dir.resolve("normal-stop.properties"));
        for(String key:List.of("jobId","parentPid","parentStartedAt"))
            if(!RestoreFiles.required(job,key).equals(RestoreFiles.required(p,key)))throw new IOException("Normal-stop intent does not match recorded server JVM");
        if(!RestoreFiles.constantEquals(RestoreFiles.required(p,"authentication"),RestoreFiles.hmac(RestoreFiles.required(job,"nonce"),shutdownPayload(p))))throw new IOException("Normal-stop intent authentication failed");
        // Markers written before 1.3.0 carry no evidence field; they were only ever written on the server shutdown path.
        String evidence=p.getProperty("evidence",EVIDENCE_SERVER_STOPPING);
        if(evidence.equals(EVIDENCE_SERVER_STOPPING))return;
        if(!evidence.equals(EVIDENCE_PLUGIN_DISABLED))throw new IOException("Unknown normal-stop evidence; no restore is permitted");
        Instant requested;
        try {requested=Instant.parse(RestoreFiles.required(p,"requestedAt"));}
        catch(RuntimeException e){throw new IOException("Invalid normal-stop timestamp",e);}
        Duration elapsed=Duration.between(requested,observedExit);
        if(elapsed.compareTo(DISABLED_EXIT_GRACE)>0)throw new IOException("The plugin was disabled "+elapsed.toMinutes()+" minutes before the server JVM exited, so this exit is not treated as a normal server stop (plugin reload/unload, or a shutdown longer than "+DISABLED_EXIT_GRACE.toMinutes()+" minutes). Nothing was changed. Reserve again after starting the server, or run the helper manually while the server is stopped");
        if(elapsed.compareTo(CLOCK_TOLERANCE.negated())<0)throw new IOException("Normal-stop intent is dated after the observed JVM exit; the system clock is unreliable and no restore is permitted");
    }
    private static String shutdownPayload(Properties p)throws IOException {
        String base=String.join("\n",RestoreFiles.required(p,"jobId"),RestoreFiles.required(p,"parentPid"),RestoreFiles.required(p,"parentStartedAt"),RestoreFiles.required(p,"requestedAt"));
        // The evidence is authenticated whenever present; the pre-1.3.0 payload had no such field.
        String evidence=p.getProperty("evidence");
        return evidence==null?base:base+"\n"+evidence;
    }
    static boolean waiting(String status) {return Set.of("COMMITTED","READY","WAITING_FOR_MANUAL_STOP","PARENT_EXITED","CANCEL_PENDING").contains(status);}
    static void requireActive(Path state,Properties job)throws IOException {
        Properties active=RestoreFiles.load(state.resolve("active-restore.properties"));
        if(!RestoreFiles.required(job,"jobId").equals(RestoreFiles.required(active,"jobId"))
                || !RestoreFiles.required(job,"backupId").equals(RestoreFiles.required(active,"backupId")))throw new IOException("Job is not the active authorized restore");
    }
}
