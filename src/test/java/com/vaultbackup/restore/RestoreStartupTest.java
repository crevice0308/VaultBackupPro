// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Startup reconciliation regressions, using only disposable files and JVMs. */
public final class RestoreStartupTest {
    private static int checks;
    private static Path helper;
    private record Fixture(Path base,Path root,Path backups,Path state,Path dir,Properties job) {}

    public static void main(String[] args)throws Exception {
        if(args[0].equals("--guard-probe")) {
            try(FileChannel c=FileChannel.open(Path.of(args[1]),StandardOpenOption.WRITE);FileLock lock=c.tryLock()) {
                if(lock==null)System.exit(7);
            }
            return;
        }
        if(args[0].equals("--sleep")) {
            // With a job directory: a helper that is alive and does nothing. It holds helper.lock like every real helper.
            if(args.length>1) {
                Path dir=Path.of(args[1]);
                try(FileChannel c=RestoreFiles.openLockFile(dir.resolve("helper.lock"));FileLock lock=c.lock()) {
                    Files.createFile(dir.resolve("helper-held"));Thread.sleep(60000);
                }
                return;
            }
            Thread.sleep(60000);return;
        }
        if(args[0].equals("--hold-guard")) {
            try(FileChannel c=FileChannel.open(Path.of(args[1]),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=c.lock()) {
                Files.createFile(Path.of(args[2]));Thread.sleep(60000);
            }
            return;
        }
        if(args[0].equals("--cooperate")) {
            Path dir=Path.of(args[1]);Properties job=RestoreFiles.load(dir.resolve("job.properties"));
            FileChannel helperLock=RestoreFiles.openLockFile(dir.resolve("helper.lock"));helperLock.lock(); // held until this process exits
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {RestoreJobState.recordProcess(dir,job,ProcessHandle.current());}
            Files.createFile(dir.resolve("cooperator-ready"));
            while(true) {
                try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)) {
                    if(RestoreJobState.hasCancellation(dir)) {RestoreJobState.acknowledgeCancellation(dir,job,"HELPER");return;}
                }
                Thread.sleep(20);
            }
        }
        if(args[0].equals("--hold-degraded")) {
            Path base=Path.of(args[1]);
            var result=RestoreCoordinator.start(base.resolve("server"),base.resolve("backups"),base.resolve("state"),Path.of(args[2]),"en",message -> {});
            if(result.disposition()!=RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED)throw new AssertionError(result);
            result=null;System.gc(); // Simulate disabled plugin with all instance references dropped.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {Files.createFile(base.resolve("shutdown-hook"));Thread.sleep(1500);}catch(Exception e){throw new RuntimeException(e);}
            }));
            Files.createFile(base.resolve("degraded-ready"));
            while(!Files.exists(base.resolve("exit-request")))Thread.sleep(20);
            return;
        }
        run(Path.of(args[0]));
    }
    static void run(Path jar)throws Exception {
        helper=jar;Path base=Files.createTempDirectory("vaultbackup-startup-test-");
        deadWaiting(base.resolve("dead"));liveWaiting(base.resolve("live"));
        pendingWaiting(base.resolve("pending"));missingIdentity(base.resolve("identity"));
        malformedJournals(base.resolve("malformed"));partialSwaps(base.resolve("partial"));
        journalFailures(base.resolve("writes"));retainedLease(base.resolve("lease"));
        loggerRouting(base.resolve("logger"));reloadInheritsGuard(base.resolve("reload"));
        recoveryReceipts(base.resolve("recovery-receipts"));stagedAcknowledgmentReplay(base.resolve("staged-ack"));
        ordinaryStorageFailure(base.resolve("storage"));unauthenticatedHelper(base.resolve("helper-auth"));
        stopEvidence(base.resolve("stop-evidence"));reusedProcessIds(base.resolve("pid-reuse"));
        rollbackIdentity(base.resolve("rollback-identity"));successWithoutRollback(base.resolve("success-receipt"));
        reservationPreflight(base.resolve("preflight"));failedStagingCleansUp(base.resolve("staging-cleanup"));
        fileNameEncoding(base.resolve("file-names"));
        interruptedExtraction(base.resolve("interrupted"));identityAcrossReboots(base.resolve("device-numbers"));
        markerTimeAndForm(base.resolve("marker-form"));recoveryWithoutBackupStorage(base.resolve("no-storage"));
        serverRecognisedAsParent(base.resolve("clock-step"));
        System.out.println("RestoreStartupTest: "+checks+" checks passed; disposable fixtures at "+base);
    }
    private static Fixture fixture(Path base,String status)throws Exception {
        Files.createDirectories(base);Path root=Files.createDirectory(base.resolve("server"));
        Files.writeString(root.resolve("original"),"ORIGINAL");
        Path backups=Files.createDirectory(base.resolve("backups")),state=Files.createDirectory(base.resolve("state"));
        Path archive=backups.resolve("snapshot.zip");archive(archive);
        String id=UUID.randomUUID().toString();Path dir=state.resolve("restore-jobs").resolve(id);Files.createDirectories(dir);
        Properties job=new Properties();job.setProperty("formatVersion","2");job.setProperty("shutdownPolicy","MANUAL_NORMAL_STOP");
        job.setProperty("jobId",id);job.setProperty("backupId","snapshot");job.setProperty("serverRoot",root.toString());
        job.setProperty("backupDirectory",backups.toString());job.setProperty("stateDirectory",state.toString());
        job.setProperty("archive",archive.toString());job.setProperty("archiveSha256",RestoreFiles.sha(archive));
        var verified=RestoreArchive.verify(archive,RestoreFiles.sha(archive));job.setProperty("manifestSha256",verified.manifestSha256());
        job.setProperty("entryCount",Integer.toString(verified.entries().size()));job.setProperty("totalBytes",Long.toString(verified.totalBytes()));
        job.setProperty("parentPid",Long.toString(ProcessHandle.current().pid()));job.setProperty("parentStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        job.setProperty("nonce",RestoreFiles.randomSecret());
        for(String kind:List.of("stage","rollback","failed"))job.setProperty(kind,root.resolveSibling("server.vaultbackup-"+kind+"-"+id).toString());
        RestoreFiles.write(dir.resolve("job.properties"),job);
        Properties active=new Properties();active.setProperty("jobId",id);active.setProperty("backupId","snapshot");RestoreFiles.write(state.resolve("active-restore.properties"),active);
        RestoreFiles.status(dir,status,"startup fixture");return new Fixture(base,root,backups,state,dir,job);
    }
    private static RestoreCoordinator.StartupResult start(Fixture f) {
        return RestoreCoordinator.start(f.root,f.backups,f.state,helper,"en",message -> {});
    }
    private static void deadWaiting(Path base)throws Exception {
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Process p=sleeper(f);kill(p);
        var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"dead stale waiting job permits ready startup");
        check(RestoreJobState.acknowledged(f.dir,f.job),"dead helper cancellation acknowledgment is authenticated");
        check(RestoreFiles.load(f.dir.resolve("cancel-ack.properties")).getProperty("source").equals("CONFIRMED_HELPER_DEATH"),"old helper is never claimed NOT_LAUNCHED");
        check(!Files.exists(f.state.resolve("active-restore.properties")) && result.coordinator().protectedBackupIds().isEmpty(),"only acknowledged cancellation clears active marker and backup pin");
        unchanged(f);guardHeld(f);
        Process retry=helper(f);check(retry.waitFor(10,TimeUnit.SECONDS) && retry.exitValue()==3,"cancelled stale job cannot be resumed manually");
    }
    private static void liveWaiting(Path base)throws Exception {
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Process p=spawn("--cooperate",f.dir.toString());
        try {
            await(f.dir.resolve("cooperator-ready"));var result=start(f);
            check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"cooperative live stale helper acknowledges startup cancellation");
            check(p.waitFor(10,TimeUnit.SECONDS) && p.exitValue()==0,"old helper exits without restore");
            check(RestoreFiles.load(f.dir.resolve("cancel-ack.properties")).getProperty("source").equals("HELPER"),"live stale helper supplies authenticated acknowledgment");
            check(!Files.exists(f.state.resolve("active-restore.properties")),"live acknowledged cancellation clears active marker");unchanged(f);guardHeld(f);
        } finally {kill(p);}
    }
    private static void pendingWaiting(Path base)throws Exception {
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Process p=sleeper(f);
        try {
            var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED,"unresponsive live helper does not force stale-journal shutdown");
            check(Files.exists(f.dir.resolve("cancel.properties")) && !Files.exists(f.dir.resolve("cancel-ack.properties")),"request alone never fabricates cancellation acknowledgment");
            check(Files.exists(f.state.resolve("active-restore.properties")) && result.coordinator().protectedBackupIds().contains("snapshot"),"pending stale helper keeps active marker and archive pin");
            refuses(result.coordinator()::cancelCommitted,"new coordinator does not pretend it launched old helper");
            refuses(()->result.coordinator().reserve("snapshot",f.backups.resolve("snapshot.zip"),RestoreFiles.sha(f.backups.resolve("snapshot.zip")),Duration.ofMinutes(1)),"degraded coordinator refuses new reservations");
            check(result.coordinator().status().orElseThrow().state().equals("GUARDED_DEGRADED"),"degraded status cannot silently clear journal");unchanged(f);guardHeld(f);
        } finally {kill(p);}
    }
    /**
     * The server died between publishing a reservation and recording its helper. Nothing identifies a
     * helper process then, and this used to disable the plugin on every start. Whether a helper is
     * alive is not presumed either way: every helper holds helper.lock, so a free lock is the witness.
     */
    private static void missingIdentity(Path base)throws Exception {
        Fixture f=fixture(base,"COMMITTED");var result=start(f);
        check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"a reservation whose helper was never recorded resolves when no helper holds its lock");
        check(RestoreFiles.load(f.dir.resolve("cancel-ack.properties")).getProperty("source").equals("CONFIRMED_HELPER_DEATH") && !Files.exists(f.state.resolve("active-restore.properties")),
                "it is cancelled durably, never claimed to be unlaunched");
        unchanged(f);guardHeld(f);
        Process late=helper(f);check(late.waitFor(10,TimeUnit.SECONDS) && late.exitValue()==3,"a helper that appears afterwards finds the cancellation and does nothing");
        // A helper that is alive but has not recorded itself yet holds the lock: nothing is presumed about it.
        Fixture starting=fixture(base.resolve("starting"),"COMMITTED");
        Process unrecorded=spawn("--sleep",starting.dir.toString());
        try {
            await(starting.dir.resolve("helper-held"));var held=start(starting);
            check(held.disposition()==RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED,"a live helper without a recorded identity is never presumed dead");
            check(!Files.exists(starting.dir.resolve("cancel-ack.properties")) && Files.exists(starting.state.resolve("active-restore.properties")),"its journal is retained without a false acknowledgment");guardHeld(starting);
        } finally {kill(unrecorded);}
    }
    private static void malformedJournals(Path base)throws Exception {
        Fixture missing=fixture(base.resolve("missing-status"),"COMMITTED");Files.delete(missing.dir.resolve("status.properties"));hard(start(missing),missing,"missing status is hard conflict, never default COMMITTED");
        Fixture malformed=fixture(base.resolve("bad-status"),"WAITING_FOR_MANUAL_STOP");Files.writeString(malformed.dir.resolve("status.properties"),"status="+(char)92+"uZZZZ\n");hard(start(malformed),malformed,"malformed status is hard conflict");
        Fixture job=fixture(base.resolve("bad-job"),"WAITING_FOR_MANUAL_STOP");job.job.setProperty("nonce","invalid");RestoreFiles.write(job.dir.resolve("job.properties"),job.job);hard(start(job),job,"invalid job secret is hard conflict before tombstone");
        Fixture active=fixture(base.resolve("bad-active"),"WAITING_FOR_MANUAL_STOP");Files.writeString(active.state.resolve("active-restore.properties"),"jobId=wrong\nbackupId=snapshot\n");hard(start(active),active,"malformed active job ID is preserved");
        Fixture success=fixture(base.resolve("fake-success"),"SUCCESS");hard(start(success),success,"success without authenticated receipt is hard conflict");
    }
    private static void partialSwaps(Path base)throws Exception {
        Fixture absent=fixture(base.resolve("root-absent"),"ORIGINAL_MOVED");Files.move(absent.root,Path.of(absent.job.getProperty("rollback")));hard(start(absent),absent,"missing root is classified after guard acquisition");
        check(!Files.exists(absent.root),"missing root is never recreated by startup");
        Fixture forged=fixture(base.resolve("forged-waiting"),"WAITING_FOR_MANUAL_STOP");Files.createDirectory(Path.of(forged.job.getProperty("rollback")));hard(start(forged),forged,"waiting string cannot conceal a rollback tree");
        Fixture staged=fixture(base.resolve("unexpected-stage"),"WAITING_FOR_MANUAL_STOP");Files.createDirectory(Path.of(staged.job.getProperty("stage")));hard(start(staged),staged,"unexpected stage makes waiting journal ambiguous");
        for(String status:List.of("MOVING_ORIGINAL","ORIGINAL_MOVED","INSTALLING","VERIFYING_INSTALLED","RECOVERY_REQUIRED","FAILED_ROLLED_BACK","RECOVERED")) {
            Fixture f=fixture(base.resolve(status),status);hard(start(f),f,"post-swap state "+status+" preserves recovery authorization");
        }
        Fixture locked=fixture(base.resolve("held-guard"),"WAITING_FOR_MANUAL_STOP");
        try(FileChannel c=FileChannel.open(locked.state.resolve(RestoreFiles.guardName(locked.root)),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=c.lock()) {
            var result=start(locked);check(result.disposition()==RestoreCoordinator.StartupDisposition.HARD_CONFLICT,"actively held root guard remains hard conflict");
            check(!Files.exists(locked.dir.resolve("cancel.properties")),"root guard conflict never edits old job");
        }
    }
    private static void journalFailures(Path base)throws Exception {
        Fixture tombstone=fixture(base.resolve("tombstone"),"WAITING_FOR_MANUAL_STOP");Files.createDirectory(tombstone.dir.resolve("cancel.properties"));
        var failed=start(tombstone);check(failed.disposition()==RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED,"pre-swap cancellation-write failure leaves guarded server running");
        check(Files.exists(tombstone.state.resolve("active-restore.properties")) && failed.coordinator().protectedBackupIds().contains("snapshot"),"failed tombstone keeps journal and backup pin");guardHeld(tombstone);unchanged(tombstone);
        Fixture acknowledgment=fixture(base.resolve("acknowledgment"),"WAITING_FOR_MANUAL_STOP");Process dead=sleeper(acknowledgment);kill(dead);Files.createDirectory(acknowledgment.dir.resolve("cancel-ack.properties"));
        var ack=start(acknowledgment);check(ack.disposition()==RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED,"unwritable acknowledgment cannot falsely finish cleanup");
        check(Files.exists(acknowledgment.state.resolve("active-restore.properties")),"failed acknowledgment retains active journal");guardHeld(acknowledgment);
    }
    private static void retainedLease(Path base)throws Exception {
        // Degraded: a helper that is alive (it holds its lock) but does not answer the cancellation.
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Process silent=sleeper(f);
        Process server=spawn("--hold-degraded",base.toString(),helper.toString());
        try {
            await(base.resolve("degraded-ready"));guardHeld(f);
            Files.createFile(base.resolve("exit-request"));await(base.resolve("shutdown-hook"));
            check(server.isAlive(),"shutdown hook is still running");guardHeld(f);
            check(server.waitFor(10,TimeUnit.SECONDS) && server.exitValue()==0,"guard-holding JVM fully exits");
            try(FileChannel c=FileChannel.open(f.state.resolve(RestoreFiles.guardName(f.root)),StandardOpenOption.WRITE);FileLock lock=c.tryLock()) {
                check(lock!=null,"root guard becomes available only after real JVM exit");
            }
            unchanged(f);
        } finally {kill(server);kill(silent);}
    }
    private static void loggerRouting(Path base)throws Exception {
        Fixture f=fixture(base,"COMMITTED");Files.delete(f.state.resolve("active-restore.properties"));
        List<String> logs=new ArrayList<>();ByteArrayOutputStream bytes=new ByteArrayOutputStream();PrintStream previous=System.out;
        try(PrintStream capture=new PrintStream(bytes)) {
            System.setOut(capture);
            var result=RestoreCoordinator.start(f.root,f.backups,f.state,helper,"en",logs::add);
            var c=result.coordinator();var r=c.reserve("snapshot",f.backups.resolve("snapshot.zip"),RestoreFiles.sha(f.backups.resolve("snapshot.zip")),Duration.ofMinutes(1));
            var launch=c.confirm(r.token());check(launch.helperReady(),"logger fixture helper is ready");
            check(c.cancelCommitted().cancelled(),"logger fixture cancels cleanly");
        } finally {System.setOut(previous);}
        check(bytes.size()==0,"coordinator and cancellation journaling never print to System.out");
        check(logs.stream().anyMatch(s->s.contains("COMMITTED")),"coordinator status uses injected server logger");
        unchanged(f);
    }
    private static void recoveryReceipts(Path base)throws Exception {
        Fixture complete=completedRecovery(base.resolve("complete"));
        var ready=start(complete);check(ready.disposition()==RestoreCoordinator.StartupDisposition.READY,"authenticated completed recovery starts normally");
        check(RestoreJobState.acknowledged(complete.dir,complete.job) && !Files.exists(complete.state.resolve("active-restore.properties")),"completed recovery is permanently disarmed before cleanup");
        check(ready.coordinator().consumeVerifiedReceipt().isEmpty(),"recovered original root is not advertised as successful backup restore");
        unchanged(complete);guardHeld(complete);
        for(String field:List.of("authentication","jobId","serverRoot","stage","outcome","completedAt")) {
            Fixture f=completedRecovery(base.resolve("tamper-"+field));Properties receipt=RestoreFiles.load(f.dir.resolve("recovery-receipt.properties"));
            receipt.setProperty(field,field.equals("authentication")?"0".repeat(64):receipt.getProperty(field)+"changed");RestoreFiles.write(f.dir.resolve("recovery-receipt.properties"),receipt);
            hard(start(f),f,"tampered recovery receipt field "+field+" cannot permit startup");
        }
        Fixture other=completedRecovery(base.resolve("other-job"));
        RestoreFiles.write(other.dir.resolve("recovery-receipt.properties"),RestoreFiles.load(complete.dir.resolve("recovery-receipt.properties")));
        hard(start(other),other,"recovery receipt from another job cannot be replayed");
        Fixture contradictory=completedRecovery(base.resolve("rollback-returned"));Files.createDirectory(Path.of(contradictory.job.getProperty("rollback")));
        hard(start(contradictory),contradictory,"valid recovery receipt cannot override contradictory rollback tree");
        Fixture legacy=fixture(base.resolve("legacy"),"RECOVERED");hard(start(legacy),legacy,"legacy terminal status without proof remains hard");
        Fixture phaseFailure=completedRecovery(base.resolve("final-status-failed"));RestoreFiles.status(phaseFailure.dir,"ROLLING_BACK","final terminal status could not be written");
        check(start(phaseFailure).disposition()==RestoreCoordinator.StartupDisposition.READY,"durable authenticated completion survives failed final status write");
    }
    private static Fixture completedRecovery(Path base)throws Exception {
        Fixture f=fixture(base,"ORIGINAL_MOVED");Path rollback=Path.of(f.job.getProperty("rollback"));
        Files.move(f.root,rollback);Files.createDirectory(Path.of(f.job.getProperty("failed")));Files.move(rollback,f.root);
        RestoreJobState.recordCompletedRecovery(f.dir,f.job,"RECOVERED");RestoreFiles.status(f.dir,"RECOVERED","fixture physical original-root restoration");
        Process p=sleeper(f);kill(p);return f;
    }
    private static void stagedAcknowledgmentReplay(Path base)throws Exception {
        Fixture f=fixture(base,"FAILED_BEFORE_SWAP");Files.createDirectory(Path.of(f.job.getProperty("stage")));
        try(RestoreJobState.Gate gate=RestoreJobState.requireLock(f.dir)) {
            RestoreJobState.requestCancellation(f.dir,f.job);RestoreJobState.acknowledgeCancellation(f.dir,f.job,"HELPER");
        }
        String cancelId=RestoreFiles.load(f.dir.resolve("cancel.properties")).getProperty("cancelId");
        var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"a durable cancellation survives an interruption before the active marker was cleared");
        check(cancelId.equals(RestoreFiles.load(f.dir.resolve("cancel.properties")).getProperty("cancelId")),"startup does not replace existing permanent tombstone");
        // Since 1.3.0: a job that can never be applied leaves no extraction behind (it may be what filled the disk).
        check(!Files.exists(f.state.resolve("active-restore.properties")) && !Files.exists(Path.of(f.job.getProperty("stage"))),"the unused extraction of an acknowledged cancellation is removed with the reservation");
    }
    private static void ordinaryStorageFailure(Path base)throws Exception {
        Fixture f=fixture(base.resolve("backup-file"),"COMMITTED");Files.delete(f.state.resolve("active-restore.properties"));
        Files.delete(f.backups.resolve("snapshot.zip"));Files.delete(f.backups);Files.writeString(f.backups,"ordinary backup configuration error");
        var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"backup-directory setup failure with no active job is not a restore startup conflict");guardHeld(f);unchanged(f);
        Path fresh=base.resolve("fresh");Files.createDirectories(fresh);Path root=Files.createDirectory(fresh.resolve("server")),state=Files.createDirectory(fresh.resolve("state"));
        Files.writeString(state.resolve("restore-jobs"),"ordinary job-directory setup error");
        var ordinary=RestoreCoordinator.start(root,fresh.resolve("backups"),state,helper,"en",message -> {});
        check(ordinary.disposition()==RestoreCoordinator.StartupDisposition.READY,"unused restore-jobs setup failure cannot trigger root conflict");
        check(!Files.exists(fresh.resolve("backups")),"startup classification does not create ordinary backup storage");
        Fixture stale=fixture(base.resolve("stale-missing-backups"),"WAITING_FOR_MANUAL_STOP");Process dead=sleeper(stale);kill(dead);
        Files.delete(stale.backups.resolve("snapshot.zip"));Files.delete(stale.backups);
        check(start(stale).disposition()==RestoreCoordinator.StartupDisposition.READY,"safe stale cancellation does not require a missing archive directory");
        check(RestoreJobState.acknowledged(stale.dir,stale.job),"missing backup storage never bypasses cancellation acknowledgment");guardHeld(stale);
    }
    private static void unauthenticatedHelper(Path base)throws Exception {
        // Whether a helper is alive is witnessed by its lock, which nobody can write into a file. A record
        // of its process that fails authentication therefore neither proves a live helper dead ...
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Process p=sleeper(f);
        try {
            Properties process=RestoreFiles.load(f.dir.resolve("helper-process.properties"));process.setProperty("authentication","0".repeat(64));
            process.setProperty("pid","1");RestoreFiles.write(f.dir.resolve("helper-process.properties"),process);
            var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED,"a forged helper record cannot make a live helper count as dead");
            check(!Files.exists(f.dir.resolve("cancel-ack.properties")) && Files.exists(f.state.resolve("active-restore.properties")),"the journal is kept and nothing is acknowledged for it");guardHeld(f);
        } finally {kill(p);}
        // ... nor keeps the plugin disabled for a helper that is gone.
        Fixture gone=fixture(base.resolve("gone"),"WAITING_FOR_MANUAL_STOP");Process dead=sleeper(gone);kill(dead);
        Properties record=RestoreFiles.load(gone.dir.resolve("helper-process.properties"));record.setProperty("authentication","0".repeat(64));RestoreFiles.write(gone.dir.resolve("helper-process.properties"),record);
        var resolved=start(gone);
        check(resolved.disposition()==RestoreCoordinator.StartupDisposition.READY && !Files.exists(gone.state.resolve("active-restore.properties")),"a damaged helper record does not block the cancellation of a reservation whose helper is gone");
        unchanged(gone);guardHeld(gone);
    }
    private static void hard(RestoreCoordinator.StartupResult result,Fixture f,String message)throws Exception {
        check(result.disposition()==RestoreCoordinator.StartupDisposition.HARD_CONFLICT,message);
        check(Files.exists(f.state.resolve("active-restore.properties")) && !Files.exists(f.dir.resolve("cancel.properties")),"hard conflict preserves journal without post-swap tombstone");guardHeld(f);
    }
    private static void unchanged(Fixture f)throws Exception {
        check(Files.readString(f.root.resolve("original")).equals("ORIGINAL"),"startup leaves original data untouched");
        check(!Files.exists(f.dir.resolve("receipt.properties")),"startup never invents success receipt");
    }
    private static void guardHeld(Fixture f)throws Exception {
        // A same-JVM probe must not open/close another channel to this file:
        // POSIX systems may release every process lock when ANY channel closes.
        Process probe=spawn("--guard-probe",f.state.resolve(RestoreFiles.guardName(f.root)).toString());
        check(probe.waitFor(10,TimeUnit.SECONDS) && probe.exitValue()==7,"independent JVM confirms root lease remains held");
    }
    /**
     * /reload gives the JVM a second plugin instance. It used to be refused as a competing server,
     * which stopped the whole server. It now reuses the guard the JVM already holds, which is never released.
     */
    private static void reloadInheritsGuard(Path base)throws Exception {
        Fixture f=fixture(base,"COMMITTED");Files.delete(f.state.resolve("active-restore.properties"));
        var first=start(f);check(first.disposition()==RestoreCoordinator.StartupDisposition.READY && !first.coordinator().inheritedRootGuard(),"initial root lease acquired by the first instance");
        var second=start(f);check(second.disposition()==RestoreCoordinator.StartupDisposition.READY,"a second instance in the same JVM starts after a plugin reload");
        check(second.coordinator().inheritedRootGuard() && !second.guardHeldElsewhere(),"the second instance knows it reuses the JVM's guard");
        first=null;second=null;System.gc();guardHeld(f);
        check(Files.readString(f.root.resolve("original")).equals("ORIGINAL"),"a reload never changes root");
        var third=start(f);check(third.disposition()==RestoreCoordinator.StartupDisposition.READY && third.coordinator().inheritedRootGuard(),"every later instance inherits as well");guardHeld(f);
        // Another process holding the guard is still a conflict, and is told apart from a fault of the paths.
        Fixture other=fixture(base.resolve("other-process"),"COMMITTED");Files.delete(other.state.resolve("active-restore.properties"));
        Process holder=spawn("--hold-guard",other.state.resolve(RestoreFiles.guardName(other.root)).toString(),other.base.resolve("held").toString());
        try {
            await(other.base.resolve("held"));var refused=start(other);
            check(refused.disposition()==RestoreCoordinator.StartupDisposition.HARD_CONFLICT && refused.guardHeldElsewhere(),"a guard held by another process is reported as such");
        } finally {kill(holder);}
        Fixture symlinked=fixture(base.resolve("symlinked-root"),"COMMITTED");Files.delete(symlinked.state.resolve("active-restore.properties"));
        Path link=symlinked.base.resolve("link");Files.createSymbolicLink(link,symlinked.root);
        var fault=RestoreCoordinator.start(link.resolve("nested"),symlinked.backups,symlinked.state,helper,"en",message -> {});
        check(fault.disposition()==RestoreCoordinator.StartupDisposition.HARD_CONFLICT && !fault.guardHeldElsewhere(),"a path fault is a conflict without a foreign guard holder");
        check(!RestoreCoordinator.journalMayExist(symlinked.state) && RestoreCoordinator.journalMayExist(fixture(base.resolve("journal"),"COMMITTED").state)
                && !RestoreCoordinator.journalMayExist(base.resolve("no-such-state")),"whether a restore journal exists can be told without a coordinator");
        // A state directory that a second server uses as well: its journal names the other server's root,
        // and is no reason to stop this one.
        Fixture theirs=fixture(base.resolve("their-journal"),"WAITING_FOR_MANUAL_STOP");Path ours=Files.createDirectory(theirs.base.resolve("our-server"));
        check(RestoreCoordinator.journalMayConcern(theirs.state,theirs.root) && !RestoreCoordinator.journalMayConcern(theirs.state,ours),"a journal concerns the root it names and no other existing directory");
        check(RestoreCoordinator.journalMayConcern(theirs.state,theirs.base.resolve("not-there")) && !RestoreCoordinator.journalMayConcern(base.resolve("no-such-state"),ours),"what cannot be compared is assumed to concern the root; no journal concerns nobody");
        Files.writeString(theirs.state.resolve("active-restore.properties"),"jobId=wrong\nbackupId=snapshot\n");
        check(RestoreCoordinator.journalMayConcern(theirs.state,ours),"an unreadable journal is assumed to concern the root");
    }
    private static void stopEvidence(Path base)throws Exception {
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Path marker=f.dir.resolve("normal-stop.properties");
        RestoreJobState.noteNormalShutdown(f.dir,f.job,RestoreJobState.EVIDENCE_SERVER_STOPPING);
        RestoreJobState.requireNormalShutdown(f.dir,f.job,Instant.now().plus(Duration.ofDays(30)));checks++; // the server said it was stopping: no time limit
        RestoreJobState.noteNormalShutdown(f.dir,f.job,RestoreJobState.EVIDENCE_PLUGIN_DISABLED);
        Instant written=Instant.parse(RestoreFiles.load(marker).getProperty("requestedAt"));
        check(RestoreFiles.load(marker).getProperty("evidence").equals("PLUGIN_DISABLED"),"the marker says what was actually known");
        RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(20));checks++; // disabled, and the JVM exited right afterwards: a stop
        RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plus(RestoreJobState.DISABLED_EXIT_GRACE));checks++;
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plus(RestoreJobState.DISABLED_EXIT_GRACE).plusSeconds(1)),"a plugin disabled long before the JVM exit is not a server stop");
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plus(Duration.ofHours(5))),"a reload hours before a crash never authorises a restore");
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.minus(Duration.ofMinutes(10))),"a marker dated after the exit is refused");
        // The evidence is authenticated: a weaker marker cannot be relabelled as a stronger one.
        Properties forged=RestoreFiles.load(marker);forged.setProperty("evidence","SERVER_STOPPING");RestoreFiles.write(marker,forged);
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(1)),"relabelled evidence fails authentication");
        Properties stripped=RestoreFiles.load(marker);stripped.remove("evidence");RestoreFiles.write(marker,stripped);
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(1)),"removed evidence fails authentication");
        Properties unknown=RestoreFiles.load(marker);unknown.setProperty("evidence","SOMETHING_ELSE");
        unknown.setProperty("authentication",RestoreFiles.hmac(f.job.getProperty("nonce"),String.join("\n",unknown.getProperty("jobId"),unknown.getProperty("parentPid"),unknown.getProperty("parentStartedAt"),unknown.getProperty("requestedAt"),"SOMETHING_ELSE")));
        RestoreFiles.write(marker,unknown);refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(1)),"unknown evidence is refused even when authentic");
        // A marker written by 1.2.x has no evidence field and was only ever written on the server shutdown path.
        Properties legacy=new Properties();for(String key:List.of("jobId","parentPid","parentStartedAt"))legacy.setProperty(key,f.job.getProperty(key));
        legacy.setProperty("requestedAt",written.toString());
        legacy.setProperty("authentication",RestoreFiles.hmac(f.job.getProperty("nonce"),String.join("\n",legacy.getProperty("jobId"),legacy.getProperty("parentPid"),legacy.getProperty("parentStartedAt"),legacy.getProperty("requestedAt"))));
        RestoreFiles.write(marker,legacy);RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plus(Duration.ofDays(1)));checks++;
        refuses(()->RestoreJobState.noteNormalShutdown(f.dir,f.job,"ANYTHING"),"only the two defined kinds of evidence can be written");
    }
    /** After a host reboot the recorded PIDs belong to unrelated processes. That used to disable the plugin for good. */
    private static void reusedProcessIds(Path base)throws Exception {
        ProcessHandle self=ProcessHandle.current();Instant started=self.info().startInstant().orElseThrow();
        check(RestoreFiles.liveness(self.pid(),started)==RestoreFiles.Liveness.ALIVE,"a live process with its own start instant is alive");
        check(RestoreFiles.liveness(self.pid(),started.plusSeconds(2))==RestoreFiles.Liveness.ALIVE && RestoreFiles.liveness(self.pid(),started.minusSeconds(3))==RestoreFiles.Liveness.ALIVE,
                "start instants computed by two JVMs may differ slightly for the same process");
        check(RestoreFiles.liveness(self.pid(),started.minus(Duration.ofHours(6)))==RestoreFiles.Liveness.GONE,"the same PID started at another time is another process: the recorded one is gone");
        Process gone=spawn("--sleep");long pid=gone.pid();Instant goneStart=gone.info().startInstant().orElseThrow();kill(gone);
        check(RestoreFiles.liveness(pid,goneStart)==RestoreFiles.Liveness.GONE,"an exited process is gone");
        // The helper's recorded PID now belongs to this unrelated, running JVM.
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");
        Properties process=new Properties();process.setProperty("jobId",f.job.getProperty("jobId"));process.setProperty("pid",Long.toString(self.pid()));
        process.setProperty("startedAt",started.minus(Duration.ofDays(1)).toString());
        process.setProperty("authentication",RestoreFiles.hmac(f.job.getProperty("nonce"),String.join("\n",process.getProperty("jobId"),process.getProperty("pid"),process.getProperty("startedAt"))));
        RestoreFiles.write(f.dir.resolve("helper-process.properties"),process);
        check(RestoreJobState.confirmedDead(f.dir,f.job),"a helper whose PID was reused is confirmed dead");
        var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"a reused helper PID no longer leaves the plugin degraded on every start");
        check(result.coordinator().startupFinding()==RestoreCoordinator.StartupFinding.STALE_RESERVATION_CANCELLED && !Files.exists(f.state.resolve("active-restore.properties")),"the stale reservation is cancelled and reported as such");
        unchanged(f);guardHeld(f);
        // The recorded server PID reused by an unrelated process does not block an explicit recovery either.
        Fixture reused=fixture(base.resolve("parent"),"ROLLING_BACK");
        reused.job.setProperty("parentStartedAt",started.minus(Duration.ofDays(1)).toString());RestoreFiles.write(reused.dir.resolve("job.properties"),reused.job);
        RestoreJobState.recordRootIdentity(reused.dir,reused.job,reused.root);
        Process recover=helper(reused,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==0,"recovery proceeds although the old server PID is in use by another process");
    }
    private static Fixture stopped(Path base,String status)throws Exception {
        Fixture f=fixture(base,status);Process gone=spawn("--sleep");
        f.job.setProperty("parentPid",Long.toString(gone.pid()));f.job.setProperty("parentStartedAt",gone.info().startInstant().orElseThrow().toString());kill(gone);
        RestoreFiles.write(f.dir.resolve("job.properties"),f.job);return f;
    }
    /** A directory that merely exists at the rollback path is not the original server root. */
    private static void rollbackIdentity(Path base)throws Exception {
        Path sample=fixture(base.resolve("probe"),"COMMITTED").root;
        boolean identities=!RestoreFiles.fileIdentity(sample).isEmpty();
        // The real original, moved by an interrupted swap, is put back.
        Fixture moved=stopped(base.resolve("moved"),"RECOVERY_REQUIRED");
        RestoreJobState.recordRootIdentity(moved.dir,moved.job,moved.root);Path rollback=Path.of(moved.job.getProperty("rollback"));
        check(RestoreJobState.isOriginalRoot(moved.dir,moved.job,moved.root)==(identities?RestoreJobState.Original.YES:RestoreJobState.Original.UNKNOWN),"the recorded root is recognised");
        Files.move(moved.root,rollback);
        Process recover=helper(moved,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==0 && Files.readString(moved.root.resolve("original")).equals("ORIGINAL"),"the original root is recovered from the rollback path");
        var afterRecovery=start(moved);check(afterRecovery.disposition()==RestoreCoordinator.StartupDisposition.READY
                && afterRecovery.coordinator().startupFinding()==RestoreCoordinator.StartupFinding.FAILED_RESTORE_ROLLED_BACK,"startup reports a rolled-back restore as that, not as a cancelled reservation");
        if(!identities)return; // The remaining cases need file identities (not available on every filesystem).
        // A planted directory is never moved into the server root.
        Fixture planted=stopped(base.resolve("planted"),"RECOVERY_REQUIRED");
        RestoreJobState.recordRootIdentity(planted.dir,planted.job,planted.root);Path fake=Path.of(planted.job.getProperty("rollback"));
        Files.createDirectory(fake);Files.writeString(fake.resolve("original"),"PLANTED");
        check(RestoreJobState.isOriginalRoot(planted.dir,planted.job,fake)==RestoreJobState.Original.NO,"a directory created at the rollback path is not the original");
        recover=helper(planted,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==2,"recovery refuses a rollback directory that is not the original root");
        check(Files.readString(planted.root.resolve("original")).equals("ORIGINAL") && Files.readString(fake.resolve("original")).equals("PLANTED")
                && !Files.exists(Path.of(planted.job.getProperty("failed"))),"the real root stays in place and nothing is moved");
        // Once it is removed, the interrupted job resolves: the directory in place is provably the original.
        Files.delete(fake.resolve("original"));Files.delete(fake);
        recover=helper(planted,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==0 && RestoreFiles.status(planted.dir).equals("RECOVERED"),"a job interrupted mid-swap resolves when the original is provably in place");
        check(start(planted).disposition()==RestoreCoordinator.StartupDisposition.READY,"and the server starts again");
        // Interrupted after the original was moved back, before that was journaled: used to be unresolvable.
        for(String status:List.of("ROLLING_BACK","MOVING_ORIGINAL","INSTALLING")) {
            // Starting takes this JVM's root guard for good, so the refusal and the recovery use separate roots.
            Fixture refusedStart=stopped(base.resolve("refused-"+status),status);RestoreJobState.recordRootIdentity(refusedStart.dir,refusedStart.job,refusedStart.root);
            hard(start(refusedStart),refusedStart,"a journal stopped at "+status+" still needs an explicit recovery");
            Fixture midway=stopped(base.resolve("midway-"+status),status);RestoreJobState.recordRootIdentity(midway.dir,midway.job,midway.root);
            recover=helper(midway,Map.of(),"--recover","--i-confirm-server-stopped");
            check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==0 && RestoreFiles.status(midway.dir).equals("RECOVERED"),"recovery resolves "+status+" with the original in place");
            check(start(midway).disposition()==RestoreCoordinator.StartupDisposition.READY && Files.readString(midway.root.resolve("original")).equals("ORIGINAL"),"startup is ready after resolving "+status);
        }
        // Without a recorded identity nothing can be proven, exactly as before.
        Fixture unproven=stopped(base.resolve("unproven"),"ROLLING_BACK");
        recover=helper(unproven,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==2,"a mid-swap journal without a recorded identity is not resolved by guessing");
        // A forged identity record proves nothing.
        Fixture forged=stopped(base.resolve("forged"),"ROLLING_BACK");RestoreJobState.recordRootIdentity(forged.dir,forged.job,forged.root);
        Properties identity=RestoreFiles.load(forged.dir.resolve("root-identity.properties"));identity.setProperty("authentication","0".repeat(64));RestoreFiles.write(forged.dir.resolve("root-identity.properties"),identity);
        recover=helper(forged,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==2,"an unauthenticated identity record is refused");
        // A foreign directory at the rollback path can no longer keep a server with an untouched root from starting.
        Fixture blocked=fixture(base.resolve("blocked"),"FAILED_BEFORE_SWAP");Process dead=sleeper(blocked);kill(dead);
        RestoreJobState.recordRootIdentity(blocked.dir,blocked.job,blocked.root);Files.createDirectory(Path.of(blocked.job.getProperty("rollback")));
        check(start(blocked).disposition()==RestoreCoordinator.StartupDisposition.READY && Files.isDirectory(Path.of(blocked.job.getProperty("rollback"))),
                "startup is not blocked by a foreign directory when the root is the recorded original, and leaves it alone");
        Fixture ambiguous=fixture(base.resolve("ambiguous"),"FAILED_BEFORE_SWAP");dead=sleeper(ambiguous);kill(dead);
        Files.createDirectory(Path.of(ambiguous.job.getProperty("rollback")));
        hard(start(ambiguous),ambiguous,"without a recorded identity a rollback tree keeps the journal ambiguous");
    }
    /** The administrator may delete the preserved original once the restore is confirmed. */
    private static void successWithoutRollback(Path base)throws Exception {
        for(String variant:List.of("present","deleted","symlink")) {
            Fixture f=fixture(base.resolve(variant),"SUCCESS");Path rollback=Path.of(f.job.getProperty("rollback"));
            Properties receipt=new Properties();for(String key:List.of("jobId","backupId","serverRoot","archiveSha256","manifestSha256"))receipt.setProperty(key,f.job.getProperty(key));
            receipt.setProperty("completedAt",Instant.now().toString());receipt.setProperty("rollbackDirectory",rollback.toString());
            receipt.setProperty("authentication",RestoreFiles.hmac(f.job.getProperty("nonce"),RestoreFiles.receiptPayload(receipt)));
            RestoreFiles.write(f.dir.resolve("receipt.properties"),receipt);
            if(variant.equals("present"))Files.createDirectory(rollback);
            if(variant.equals("symlink"))Files.createSymbolicLink(rollback,f.backups);
            var result=start(f);
            if(variant.equals("symlink")) {check(result.disposition()==RestoreCoordinator.StartupDisposition.HARD_CONFLICT,"a symlink at the rollback path is still refused");continue;}
            check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"a verified success starts with the rollback directory "+variant);
            var consumed=result.coordinator().consumeVerifiedReceipt();
            check(consumed.isPresent() && consumed.get().rollbackDirectory().equals(rollback) && !Files.exists(f.state.resolve("active-restore.properties")),"the receipt is reported once with the rollback directory "+variant);
            check(result.coordinator().startupFinding()==RestoreCoordinator.StartupFinding.NONE,"a success is not a startup finding");
        }
    }
    /** What cannot work is said when the restore is reserved, not after the server has been stopped for it. */
    private static void reservationPreflight(Path base)throws Exception {
        Fixture f=fixture(base,"COMMITTED");Files.delete(f.state.resolve("active-restore.properties"));
        var coordinator=start(f).coordinator();Path archive=f.backups.resolve("snapshot.zip");String sha=RestoreFiles.sha(archive);
        coordinator.usableSpaceProbe=() -> 1024L;
        try {coordinator.reserve("snapshot",archive,sha,Duration.ofMinutes(1));throw new AssertionError("reservation accepted without room to extract");}
        catch(RestoreCoordinator.RestoreUnavailableException refused) {
            check(refused.reason()==RestoreCoordinator.RestoreUnavailableException.Reason.INSUFFICIENT_SPACE && refused.availableBytes()==1024L && refused.neededBytes()>1024L,"missing space is refused at reservation with both numbers");
        }
        check(coordinator.protectedBackupIds().isEmpty(),"a refused reservation pins nothing");
        coordinator.usableSpaceProbe=null;
        var reservation=coordinator.reserve("snapshot",archive,sha,Duration.ofMinutes(1));check(coordinator.cancel(reservation.token()),"the same reservation is accepted when there is room");
        if(Files.getFileStore(f.base).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
            var original=Files.getPosixFilePermissions(f.base);
            Files.setPosixFilePermissions(f.base,java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            try {
                if(!Files.isWritable(f.base)) { // Not when the harness runs as a user that ignores permission bits.
                    try {coordinator.reserve("snapshot",archive,sha,Duration.ofMinutes(1));throw new AssertionError("reservation accepted beside an unwritable parent");}
                    catch(RestoreCoordinator.RestoreUnavailableException refused) {check(refused.reason()==RestoreCoordinator.RestoreUnavailableException.Reason.PARENT_NOT_WRITABLE,"an unwritable parent directory is refused at reservation");}
                }
            } finally {Files.setPosixFilePermissions(f.base,original);}
        }
    }
    /** A restore that fails before the swap leaves nothing behind that blocks the next start or keeps the disk full. */
    private static void failedStagingCleansUp(Path base)throws Exception {
        Fixture f=stopped(base.resolve("scope"),"WAITING_FOR_MANUAL_STOP");Path stage=Path.of(f.job.getProperty("stage"));
        f.job.setProperty("manifestSha256","0".repeat(64));RestoreFiles.write(f.dir.resolve("job.properties"),f.job);
        Process failing=helper(f,Map.of(),"--manual","--i-confirm-server-stopped");
        check(failing.waitFor(20,TimeUnit.SECONDS) && failing.exitValue()==2 && RestoreFiles.status(f.dir).equals("FAILED_BEFORE_SWAP"),"a restore whose archive no longer matches fails before the swap");
        check(!Files.exists(stage) && Files.readString(f.root.resolve("original")).equals("ORIGINAL"),"the partial extraction is removed and the root is untouched");
        check(start(f).disposition()==RestoreCoordinator.StartupDisposition.READY,"the next start is ready");
        Fixture full=stopped(base.resolve("space"),"WAITING_FOR_MANUAL_STOP");
        full.job.setProperty("totalBytes",Long.toString(Long.MAX_VALUE/2));RestoreFiles.write(full.dir.resolve("job.properties"),full.job);
        failing=helper(full,Map.of(),"--manual","--i-confirm-server-stopped");
        check(failing.waitFor(20,TimeUnit.SECONDS) && failing.exitValue()==2 && RestoreFiles.status(full.dir).equals("FAILED_BEFORE_SWAP"),"missing room is found before anything is extracted");
        check(!Files.exists(Path.of(full.job.getProperty("stage"))) && RestoreFiles.load(full.dir.resolve("status.properties")).getProperty("message").contains("free space"),"and says so in the journal");
    }
    /** Archive names are UTF-8; the helper must be able to create them whatever locale the server was started with. */
    private static void fileNameEncoding(Path base)throws Exception {
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("linux"))return;
        String name="world/日本語.dat"; // 日本語.dat
        for(String locale:List.of("C","C.UTF-8")) {
            Fixture f=stopped(base.resolve(locale.equals("C")?"posix":"utf8"),"WAITING_FOR_MANUAL_STOP");
            Path archive=f.backups.resolve("names.zip");archive(archive,name);
            var verified=RestoreArchive.verify(archive,RestoreFiles.sha(archive));
            f.job.setProperty("archive",archive.toString());f.job.setProperty("archiveSha256",RestoreFiles.sha(archive));f.job.setProperty("manifestSha256",verified.manifestSha256());
            f.job.setProperty("entryCount",Integer.toString(verified.entries().size()));f.job.setProperty("totalBytes",Long.toString(verified.totalBytes()));
            RestoreFiles.write(f.dir.resolve("job.properties"),f.job);
            Process run=helper(f,Map.of("LC_ALL",locale,"LANG",locale),"--manual","--i-confirm-server-stopped");
            check(run.waitFor(30,TimeUnit.SECONDS),"helper finished under LC_ALL="+locale);
            // The name is checked as bytes by a shell: this JVM may itself be unable to represent it.
            Process exists=new ProcessBuilder("sh","-c","test -f \"$1/world/$(printf '\\346\\227\\245\\346\\234\\254\\350\\252\\236').dat\"","sh",f.root.toString()).start();
            boolean restored=exists.waitFor()==0;
            if(locale.equals("C")) {
                check(run.exitValue()==2 && !restored && RestoreFiles.status(f.dir).equals("FAILED_BEFORE_SWAP") && Files.readString(f.root.resolve("original")).equals("ORIGINAL")
                        && !Files.exists(Path.of(f.job.getProperty("stage"))),
                        "a helper without a UTF-8 locale refuses names it cannot create and leaves the root untouched");
                check(RestoreFiles.load(f.dir.resolve("status.properties")).getProperty("message").contains("UTF-8"),"and names the cause");
            } else check(run.exitValue()==0 && restored && RestoreFiles.status(f.dir).equals("SUCCESS"),"a helper with a UTF-8 locale restores the file under its real name");
        }
    }
    /**
     * A helper that was killed while it extracted (with the server's terminal, service or container)
     * left a journal at STAGING. That used to stop the server on every start until a recovery was run
     * by hand, although the server data had not been touched.
     */
    private static void interruptedExtraction(Path base)throws Exception {
        for(String status:List.of("STAGING","STAGED")) {
            for(boolean identity:new boolean[]{true,false}) {
                Fixture f=stopped(base.resolve(status+"-"+identity),status);Path stage=Path.of(f.job.getProperty("stage"));
                if(identity)RestoreJobState.recordRootIdentity(f.dir,f.job,f.root);
                Files.createDirectories(stage.resolve("world"));Files.writeString(stage.resolve("world/partial.dat"),"PARTIAL");
                var result=start(f);
                check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"a journal that ends at "+status+" resolves by itself once the helper is gone (identity recorded: "+identity+"): "+result.message());
                check(result.coordinator().startupFinding()==RestoreCoordinator.StartupFinding.RESTORE_NOT_APPLIED && result.coordinator().startupFailure().orElse("").contains(status),"and is reported as a restore that was not applied");
                check(!Files.exists(stage) && !Files.exists(f.state.resolve("active-restore.properties")) && result.coordinator().protectedBackupIds().isEmpty(),"the partial extraction is removed and the reservation is gone");
                unchanged(f);guardHeld(f);
                Process retry=helper(f);check(retry.waitFor(10,TimeUnit.SECONDS) && retry.exitValue()==3,"the interrupted job can never be resumed");
            }
        }
        // While a helper may still be running, nothing is concluded.
        Fixture alive=fixture(base.resolve("alive"),"STAGING");Process running=sleeper(alive);
        try {hard(start(alive),alive,"a journal at STAGING with a live helper is not resolved by the server");}
        finally {kill(running);}
        // The directory in place is not the one the helper recorded: not resolved either.
        Fixture replaced=stopped(base.resolve("replaced"),"STAGING");RestoreJobState.recordRootIdentity(replaced.dir,replaced.job,replaced.root);
        Path aside=replaced.base.resolve("original-moved-aside");Files.move(replaced.root,aside);Files.createDirectory(replaced.root);Files.writeString(replaced.root.resolve("original"),"ORIGINAL");
        boolean identities=!RestoreFiles.fileIdentity(replaced.root).isEmpty();
        if(identities)hard(start(replaced),replaced,"a journal at STAGING is not resolved when the server root is another directory than recorded");
        // A journal past the extraction is never resolved this way.
        Fixture moving=stopped(base.resolve("moving"),"MOVING_ORIGINAL");
        hard(start(moving),moving,"a journal at MOVING_ORIGINAL still needs an explicit recovery");
        // A failed attempt is reported as such too, and leaves no extraction behind.
        Fixture failed=stopped(base.resolve("failed"),"FAILED_BEFORE_SWAP");Path leftover=Path.of(failed.job.getProperty("stage"));
        Files.createDirectory(leftover);Files.writeString(leftover.resolve("x"),"x");
        var afterFailure=start(failed);
        check(afterFailure.disposition()==RestoreCoordinator.StartupDisposition.READY && afterFailure.coordinator().startupFinding()==RestoreCoordinator.StartupFinding.RESTORE_NOT_APPLIED && !Files.exists(leftover),
                "a restore that failed before the swap is reported as not applied, and its extraction is removed");
        // A reservation that never got as far as an attempt keeps its own wording.
        Fixture waiting=stopped(base.resolve("waiting"),"WAITING_FOR_MANUAL_STOP");
        check(start(waiting).coordinator().startupFinding()==RestoreCoordinator.StartupFinding.STALE_RESERVATION_CANCELLED,"a reservation that was never attempted is a cancelled reservation");
    }
    /**
     * A device number belongs to a boot, not to a filesystem: LVM, btrfs, ZFS and NFS volumes may get
     * another one after a restart. The identity of the original root must survive that, or a server
     * that lost power in the middle of a swap could not be recovered.
     */
    private static void identityAcrossReboots(Path base)throws Exception {
        check(Boolean.TRUE.equals(RestoreFiles.sameFile("(dev=803,ino=42)","(dev=803,ino=42)")),"the same device and inode is the same file");
        check(Boolean.FALSE.equals(RestoreFiles.sameFile("(dev=803,ino=42)","(dev=803,ino=43)")),"another inode on the same device is another file");
        check(RestoreFiles.sameFile("(dev=803,ino=42)","(dev=fd01,ino=42)")==null,"the same inode under another device number proves nothing");
        check(RestoreFiles.sameFile("(dev=803,ino=42)","(dev=fd01,ino=43)")==null,"neither does another inode under another device number");
        check(RestoreFiles.sameFile("","(dev=803,ino=42)")==null && RestoreFiles.sameFile("(dev=803,ino=42)","")==null && RestoreFiles.sameFile("something","else")==null,"missing or unknown identities prove nothing");
        Fixture probe=fixture(base.resolve("probe"),"COMMITTED");
        java.util.regex.Matcher key=java.util.regex.Pattern.compile("\\(dev=([0-9a-f]+),ino=([0-9]+)\\)").matcher(RestoreFiles.fileIdentity(probe.root));
        if(!key.matches())return; // This platform has other identities, or none.
        // The interrupted swap, as found after a restart that renumbered the device.
        Fixture renumbered=stopped(base.resolve("renumbered"),"VERIFYING_INSTALLED");Path rollback=Path.of(renumbered.job.getProperty("rollback"));
        Files.move(renumbered.root,rollback);Files.createDirectory(renumbered.root);Files.writeString(renumbered.root.resolve("restored"),"UNVERIFIED");
        java.util.regex.Matcher moved=java.util.regex.Pattern.compile("\\(dev=([0-9a-f]+),ino=([0-9]+)\\)").matcher(RestoreFiles.fileIdentity(rollback));check(moved.matches(),"fixture identity");
        identity(renumbered,"(dev="+Long.toHexString(Long.parseLong(moved.group(1),16)+7)+",ino="+moved.group(2)+")");
        check(RestoreJobState.isOriginalRoot(renumbered.dir,renumbered.job,rollback)==RestoreJobState.Original.UNKNOWN,"a renumbered device leaves the identity undecided");
        Process recover=helper(renumbered,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==0 && Files.readString(renumbered.root.resolve("original")).equals("ORIGINAL"),"the original root is recovered after a restart that renumbered its device");
        // Another inode on the same device: refused, without advice to delete anything, and with a way on for the operator.
        Fixture other=stopped(base.resolve("other-inode"),"VERIFYING_INSTALLED");Path otherRollback=Path.of(other.job.getProperty("rollback"));
        Files.move(other.root,otherRollback);Files.createDirectory(other.root);Files.writeString(other.root.resolve("restored"),"UNVERIFIED");
        moved.reset(RestoreFiles.fileIdentity(otherRollback));check(moved.matches(),"fixture identity");
        identity(other,"(dev="+moved.group(1)+",ino="+(Long.parseLong(moved.group(2))+1)+")");
        java.io.ByteArrayOutputStream said=new java.io.ByteArrayOutputStream();
        Process refused=new ProcessBuilder(java(),"-jar",helper.toString(),"--job",other.dir.resolve("job.properties").toString(),"--recover","--i-confirm-server-stopped").redirectErrorStream(true).start();
        refused.getInputStream().transferTo(said);
        String output=said.toString(StandardCharsets.UTF_8);
        check(refused.waitFor(20,TimeUnit.SECONDS) && refused.exitValue()==2 && Files.readString(otherRollback.resolve("original")).equals("ORIGINAL") && Files.readString(other.root.resolve("restored")).equals("UNVERIFIED"),
                "a rollback directory with another identity is not moved");
        check(output.contains("--accept-rollback-directory") && !output.toLowerCase(Locale.ROOT).contains("remove"),"the refusal names the way on and never suggests removing the directory: "+output);
        Process misuse=helper(other,Map.of(),"--manual","--i-confirm-server-stopped","--accept-rollback-directory");
        check(misuse.waitFor(20,TimeUnit.SECONDS) && misuse.exitValue()==2,"the override is only accepted for a recovery");
        Process accepted=helper(other,Map.of(),"--recover","--i-confirm-server-stopped","--accept-rollback-directory");
        check(accepted.waitFor(20,TimeUnit.SECONDS) && accepted.exitValue()==0 && Files.readString(other.root.resolve("original")).equals("ORIGINAL")
                && Files.readString(Path.of(other.job.getProperty("failed")).resolve("restored")).equals("UNVERIFIED"),"the operator can overrule the identity after looking at the directory");
    }
    private static void identity(Fixture f,String root)throws Exception {
        Properties p=new Properties();p.setProperty("jobId",f.job.getProperty("jobId"));p.setProperty("root",root);
        p.setProperty("authentication",RestoreFiles.hmac(f.job.getProperty("nonce"),String.join("\n",p.getProperty("jobId"),root)));
        RestoreFiles.write(f.dir.resolve("root-identity.properties"),p);
    }
    private static void markerTimeAndForm(Path base)throws Exception {
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Path marker=f.dir.resolve("normal-stop.properties");
        RestoreJobState.noteNormalShutdown(f.dir,f.job,RestoreJobState.EVIDENCE_PLUGIN_DISABLED);
        Instant written=Instant.parse(RestoreFiles.load(marker).getProperty("requestedAt"));
        // The system clock was set back while the server kept running without the plugin: by the clock the
        // marker looks a minute old, by the helper's own measurement it is hours old.
        RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(60),Duration.ofSeconds(60));checks++;
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(60),Duration.ofHours(4)),"a marker the helper has been looking at for hours is old, whatever the system clock says");
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(60),RestoreJobState.DISABLED_EXIT_GRACE.plusSeconds(1)),"the helper's own measurement has the same limit");
        RestoreJobState.requireNormalShutdown(f.dir,f.job,written.plusSeconds(60),RestoreJobState.DISABLED_EXIT_GRACE);checks++;
        RestoreJobState.noteNormalShutdown(f.dir,f.job,RestoreJobState.EVIDENCE_SERVER_STOPPING);
        RestoreJobState.requireNormalShutdown(f.dir,f.job,Instant.now(),Duration.ofDays(3));checks++; // a server that said it was stopping has no limit
        // One marker must not be readable as another. The fields are joined by line breaks when they are
        // authenticated, so a "time" with a line break in it would carry the evidence of a weaker marker.
        RestoreJobState.noteNormalShutdown(f.dir,f.job,RestoreJobState.EVIDENCE_PLUGIN_DISABLED);
        Properties weak=RestoreFiles.load(marker),disguised=new Properties();
        for(String key:List.of("jobId","parentPid","parentStartedAt","authentication"))disguised.setProperty(key,weak.getProperty(key));
        disguised.setProperty("requestedAt",weak.getProperty("requestedAt")+"\nPLUGIN_DISABLED");
        RestoreFiles.write(marker,disguised);
        refuses(()->RestoreJobState.requireNormalShutdown(f.dir,f.job,Instant.now().plus(Duration.ofDays(1))),"a marker whose time is not a time is refused, although its authentication code matches");
    }
    /** Putting the original data back needs no backup. The storage may be an unmounted disk at that moment. */
    private static void recoveryWithoutBackupStorage(Path base)throws Exception {
        Fixture f=stopped(base,"RECOVERY_REQUIRED");RestoreJobState.recordRootIdentity(f.dir,f.job,f.root);
        Files.move(f.root,Path.of(f.job.getProperty("rollback")));Files.move(f.backups,f.base.resolve("unmounted"));
        Process recover=helper(f,Map.of(),"--recover","--i-confirm-server-stopped");
        check(recover.waitFor(20,TimeUnit.SECONDS) && recover.exitValue()==0 && Files.readString(f.root.resolve("original")).equals("ORIGINAL"),"recovery works while the backup storage is absent");
        Fixture apply=stopped(base.resolve("apply"),"WAITING_FOR_MANUAL_STOP");Files.move(apply.backups,apply.base.resolve("unmounted"));
        Process manual=helper(apply);
        check(manual.waitFor(20,TimeUnit.SECONDS) && manual.exitValue()==2 && Files.readString(apply.root.resolve("original")).equals("ORIGINAL"),"applying a restore still requires the storage");
    }
    /**
     * The start time of a process is computed from the system clock. When the clock is set after the
     * server started (a host without a battery-backed clock that reaches its time server late), the
     * helper computes another start time than the server recorded for itself, and used to conclude
     * that the server was gone. A helper started by the server knows its parent without any clock.
     */
    private static void serverRecognisedAsParent(Path base)throws Exception {
        Fixture f=fixture(base,"COMMITTED");
        f.job.setProperty("parentStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().minus(Duration.ofHours(3)).toString());
        RestoreFiles.write(f.dir.resolve("job.properties"),f.job);
        Process automatic=helper(f,Map.of());
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!RestoreFiles.status(f.dir).equals("WAITING_FOR_MANUAL_STOP")) {
                if(!automatic.isAlive() || System.nanoTime()>deadline)throw new AssertionError("a helper started by the server did not recognise it as its parent after a clock change; status "+RestoreFiles.status(f.dir));
                Thread.sleep(20);
            }
            checks++;
            Thread.sleep(600);check(automatic.isAlive() && RestoreFiles.status(f.dir).equals("WAITING_FOR_MANUAL_STOP"),"and keeps waiting while that parent runs");
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(f.dir)) {RestoreJobState.requestCancellation(f.dir,f.job);}
            check(automatic.waitFor(10,TimeUnit.SECONDS) && automatic.exitValue()==3 && RestoreJobState.acknowledged(f.dir,f.job),"it still honours a cancellation");
        } finally {kill(automatic);}
        unchanged(f);
    }
    private static Process sleeper(Fixture f)throws Exception {
        Process p=spawn("--sleep",f.dir.toString());await(f.dir.resolve("helper-held"));RestoreJobState.recordProcess(f.dir,f.job,p.toHandle());return p;
    }
    private static void kill(Process p)throws Exception {if(p.isAlive()) {p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}}
    private static Process spawn(String...args)throws IOException {
        List<String> command=new ArrayList<>(List.of(java(),"-cp",System.getProperty("java.class.path"),RestoreStartupTest.class.getName()));command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }
    private static Process helper(Fixture f)throws IOException {
        return helper(f,Map.of(),"--manual","--i-confirm-server-stopped");
    }
    private static Process helper(Fixture f,Map<String,String> environment,String...flags)throws IOException {
        List<String> command=new ArrayList<>(List.of(java(),"-jar",helper.toString(),"--job",f.dir.resolve("job.properties").toString()));command.addAll(List.of(flags));
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT);
        builder.environment().putAll(environment);return builder.start();
    }
    private static String java(){return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();}
    private static void await(Path file)throws Exception {long deadline=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(!Files.exists(file)) {if(System.nanoTime()>deadline)throw new AssertionError("Timed out awaiting "+file);Thread.sleep(20);}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    private interface Throwing {void run()throws Exception;}
    private static void refuses(Throwing action,String message)throws Exception {try {action.run();}catch(IOException e){checks++;return;}throw new AssertionError(message);}
    private static void archive(Path path)throws IOException {archive(path,"restored");}
    private static void archive(Path path,String name)throws IOException {
        byte[] bytes="BACKUP".getBytes(StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(RestoreFiles.digest().digest(bytes));
        String manifest="VAULTBACKUP-MANIFEST\t1\nF\t"+bytes.length+"\t"+hash+"\t"+Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8))+"\n";
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path),StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
            zip.putNextEntry(new ZipEntry(RestoreArchive.MANIFEST));zip.write(manifest.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
    }
}
