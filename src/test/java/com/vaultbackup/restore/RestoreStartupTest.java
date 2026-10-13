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
        if(args[0].equals("--sleep")) {Thread.sleep(60000);return;}
        if(args[0].equals("--cooperate")) {
            Path dir=Path.of(args[1]);Properties job=RestoreFiles.load(dir.resolve("job.properties"));
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
        loggerRouting(base.resolve("logger"));reloadCollision(base.resolve("reload"));
        recoveryReceipts(base.resolve("recovery-receipts"));stagedAcknowledgmentReplay(base.resolve("staged-ack"));
        ordinaryStorageFailure(base.resolve("storage"));unauthenticatedHelper(base.resolve("helper-auth"));
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
    private static void missingIdentity(Path base)throws Exception {
        Fixture f=fixture(base,"COMMITTED");var result=start(f);
        check(result.disposition()==RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED,"missing helper identity is never presumed unlaunched or dead");
        check(!Files.exists(f.dir.resolve("cancel-ack.properties")) && Files.exists(f.state.resolve("active-restore.properties")),"unknown helper retains journal without false acknowledgment");guardHeld(f);
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
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Process server=spawn("--hold-degraded",base.toString(),helper.toString());
        try {
            await(base.resolve("degraded-ready"));guardHeld(f);
            Files.createFile(base.resolve("exit-request"));await(base.resolve("shutdown-hook"));
            check(server.isAlive(),"shutdown hook is still running");guardHeld(f);
            check(server.waitFor(10,TimeUnit.SECONDS) && server.exitValue()==0,"guard-holding JVM fully exits");
            try(FileChannel c=FileChannel.open(f.state.resolve(RestoreFiles.guardName(f.root)),StandardOpenOption.WRITE);FileLock lock=c.tryLock()) {
                check(lock!=null,"root guard becomes available only after real JVM exit");
            }
            unchanged(f);
        } finally {kill(server);}
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
        var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.READY,"retained stage and durable cancellation survive interruption before active cleanup");
        check(cancelId.equals(RestoreFiles.load(f.dir.resolve("cancel.properties")).getProperty("cancelId")),"startup does not replace existing permanent tombstone");
        check(!Files.exists(f.state.resolve("active-restore.properties")) && Files.isDirectory(Path.of(f.job.getProperty("stage"))),"acknowledged cleanup preserves staged evidence");
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
        Fixture f=fixture(base,"WAITING_FOR_MANUAL_STOP");Process p=sleeper(f);kill(p);
        Properties process=RestoreFiles.load(f.dir.resolve("helper-process.properties"));process.setProperty("authentication","0".repeat(64));RestoreFiles.write(f.dir.resolve("helper-process.properties"),process);
        var result=start(f);check(result.disposition()==RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED,"forged helper identity cannot authorize cancellation acknowledgment");
        check(!Files.exists(f.dir.resolve("cancel-ack.properties")) && Files.exists(f.state.resolve("active-restore.properties")),"forged helper identity keeps journal and never asserts exact death");guardHeld(f);
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
    private static void reloadCollision(Path base)throws Exception {
        Fixture f=fixture(base,"COMMITTED");Files.delete(f.state.resolve("active-restore.properties"));
        var first=start(f);check(first.disposition()==RestoreCoordinator.StartupDisposition.READY,"initial root lease acquired");
        var second=start(f);check(second.disposition()==RestoreCoordinator.StartupDisposition.HARD_CONFLICT,"second same-JVM acquisition refuses reload");
        first=null;second=null;System.gc();guardHeld(f);
        check(Files.readString(f.root.resolve("original")).equals("ORIGINAL"),"reload collision never changes root");
    }
    private static Process sleeper(Fixture f)throws Exception {
        Process p=spawn("--sleep");RestoreJobState.recordProcess(f.dir,f.job,p.toHandle());return p;
    }
    private static void kill(Process p)throws Exception {if(p.isAlive()) {p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}}
    private static Process spawn(String...args)throws IOException {
        List<String> command=new ArrayList<>(List.of(java(),"-cp",System.getProperty("java.class.path"),RestoreStartupTest.class.getName()));command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }
    private static Process helper(Fixture f)throws IOException {
        return new ProcessBuilder(java(),"-jar",helper.toString(),"--job",f.dir.resolve("job.properties").toString(),"--manual","--i-confirm-server-stopped").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }
    private static String java(){return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString();}
    private static void await(Path file)throws Exception {long deadline=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(!Files.exists(file)) {if(System.nanoTime()>deadline)throw new AssertionError("Timed out awaiting "+file);Thread.sleep(20);}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    private interface Throwing {void run()throws Exception;}
    private static void refuses(Throwing action,String message)throws Exception {try {action.run();}catch(IOException e){checks++;return;}throw new AssertionError(message);}
    private static void archive(Path path)throws IOException {
        byte[] bytes="BACKUP".getBytes(StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(RestoreFiles.digest().digest(bytes));
        String manifest="VAULTBACKUP-MANIFEST\t1\nF\t"+bytes.length+"\t"+hash+"\t"+Base64.getUrlEncoder().withoutPadding().encodeToString("restored".getBytes(StandardCharsets.UTF_8))+"\n";
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("restored"));zip.write(bytes);zip.closeEntry();
            zip.putNextEntry(new ZipEntry(RestoreArchive.MANIFEST));zip.write(manifest.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
    }
}
