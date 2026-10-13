// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Standalone disposable-fixture safety tests; no Bukkit or real server is used. */
public final class RestoreSafetyTest {
    private static int checks;
    private static Path helperJar;
    public static void main(String[] args)throws Exception {
        if(args.length>0 && args[0].equals("--parent")){parent(Path.of(args[1]),Path.of(args[2]));return;}
        if(args.length>0 && args[0].equals("--gate")){holdGate(Path.of(args[1]));return;}
        if(args.length>0 && args[0].equals("--sleeper")){Thread.sleep(60000);return;}
        if(args.length!=1)throw new IllegalArgumentException("Pass standalone helper JAR path");
        helperJar=Path.of(args[0]).toAbsolutePath();
        Path temp=Files.createTempDirectory("vaultbackup-restore-test-");
        try {
            archiveTests(temp.resolve("archives"));
            preconditionTests(temp.resolve("conditions"));
            handshakeFailureTest(temp.resolve("handshake"));
            cancelThenStopTest(temp.resolve("cancel-stop"));
            cancellationGateTest(temp.resolve("cancel-gate"));
            cancellationAmbiguityTest(temp.resolve("cancel-ambiguity"));
            pendingAcknowledgmentTest(temp.resolve("pending-ack"));
            confirmationRaceTest(temp.resolve("confirm-cancel-race"));
            deadHelperTest(temp.resolve("dead-helper"));
            killedParentTest(temp.resolve("killed-parent"));
            integration(temp.resolve("success"),false);
            integration(temp.resolve("rollback"),true);
            rollbackJournalFailureTest(temp.resolve("rollback-journal"),"ORIGINAL_MOVED",false,false);
            rollbackJournalFailureTest(temp.resolve("installed-journal"),"VERIFYING_INSTALLED",false,false);
            rollbackJournalFailureTest(temp.resolve("blocked-rollback-journal"),"ORIGINAL_MOVED",true,false);
            rollbackJournalFailureTest(temp.resolve("malformed-journal"),"ORIGINAL_MOVED",false,true);
            rollbackJournalFailureTest(temp.resolve("blocked-malformed-journal"),"ORIGINAL_MOVED",true,true);
            recoveryTest(temp.resolve("recovery"));
            System.out.println("RestoreSafetyTest: "+checks+" checks passed; disposable fixtures at "+temp);
        }catch(Throwable e){System.err.println("Test fixtures retained at "+temp);throw e;}
    }
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    private interface Throwing {void run()throws Exception;}
    private static void refuses(Throwing action,String message)throws Exception {
        try{action.run();}catch(IOException e){checks++;return;}throw new AssertionError(message);
    }
    private static void archiveTests(Path dir)throws Exception {
        Files.createDirectories(dir);Path archive=dir.resolve("good.zip");
        zip(archive,Map.of("world/level.dat","BACKUP","server.properties","online-mode=true"),List.of("empty"));
        var verified=RestoreArchive.verify(archive,RestoreFiles.sha(archive));check(verified.entries().size()==3,"valid manifest count");
        Path stage=Files.createDirectory(dir.resolve("stage"));RestoreArchive.extract(archive,RestoreFiles.sha(archive),stage);
        check(Files.readString(stage.resolve("world/level.dat")).equals("BACKUP"),"secure extraction");
        refuses(()->RestoreArchive.verify(archive,"0".repeat(64)),"corrupt archive digest must fail");
        Files.writeString(stage.resolve("world/level.dat"),"CHANGED");refuses(()->RestoreArchive.verifyInstalled(stage,verified),"changed installed data must fail verification");
        Path traversal=dir.resolve("traversal.zip");zip(traversal,Map.of("../outside","bad"),List.of());
        refuses(()->RestoreArchive.verify(traversal,RestoreFiles.sha(traversal)),"traversal must fail");
        Path collision=dir.resolve("collision.zip");zip(collision,Map.of("A","a","a","b"),List.of());
        refuses(()->RestoreArchive.verify(collision,RestoreFiles.sha(collision)),"case collision must fail");
        Path implicit=dir.resolve("implicit.zip");zip(implicit,Map.of("Dir/a","a","dir/b","b"),List.of());
        refuses(()->RestoreArchive.verify(implicit,RestoreFiles.sha(implicit)),"implicit case-alias ancestors must fail");
        Path conflict=dir.resolve("conflict.zip");zip(conflict,Map.of("A","a","a/b","b"),List.of());
        refuses(()->RestoreArchive.verify(conflict,RestoreFiles.sha(conflict)),"implicit ancestor file conflict must fail");
        Path unicode=dir.resolve("unicode.zip");zip(unicode,Map.of("é/a","a","e\u0301/b","b"),List.of());
        refuses(()->RestoreArchive.verify(unicode,RestoreFiles.sha(unicode)),"implicit Unicode aliases must fail");
        Path special=dir.resolve("special.zip");zip(special,Map.of("CON.txt","bad"),List.of());
        refuses(()->RestoreArchive.verify(special,RestoreFiles.sha(special)),"Windows devices must fail");
        Path missing=dir.resolve("missing.zip");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(missing))){out.putNextEntry(new ZipEntry("x"));out.write(1);out.closeEntry();}
        refuses(()->RestoreArchive.verify(missing,RestoreFiles.sha(missing)),"manifest required");
    }
    private static void preconditionTests(Path fixture)throws Exception {
        Files.createDirectories(fixture);Path root=Files.createDirectory(fixture.resolve("server"));Path backups=Files.createDirectory(fixture.resolve("backups"));Path state=Files.createDirectory(fixture.resolve("state"));
        refuses(()->new RestoreCoordinator(root,root.resolve("backups"),state,helperJar),"nested backups must disable restore");
        refuses(()->new RestoreCoordinator(root,backups,root.resolve("state"),helperJar),"nested state must disable restore");
        try{Path link=Files.createSymbolicLink(fixture.resolve("linked-server"),root);refuses(()->new RestoreCoordinator(link,backups,state,helperJar),"symlink root must fail");}catch(UnsupportedOperationException ignored){}
        Path privacyRoot=Files.createDirectory(fixture.resolve("privacy-server"));Path newState=fixture.resolve("private-state"),newBackups=fixture.resolve("private-backups");
        new RestoreCoordinator(privacyRoot,newBackups,newState,helperJar);
        try {
            var privateMode=java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
            check(Files.getPosixFilePermissions(newState).equals(privateMode),"new state is owner-only");
            check(Files.getPosixFilePermissions(newBackups).equals(privateMode),"new backup directory is owner-only");
            check(Files.getPosixFilePermissions(newState.resolve("restore-jobs")).equals(privateMode),"new job container is owner-only");
        }catch(UnsupportedOperationException ignored){}
        Path archive=backups.resolve("test.zip");zip(archive,Map.of("file","backup"),List.of());
        Properties job=manualJob(root,backups,state,archive,ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow());
        Path jobFile=Path.of(job.getProperty("jobFile"));
        refuses(()->RestoreMain.run(new String[]{"--job",jobFile.toString(),"--manual","--i-confirm-server-stopped"}),"manual confirmation cannot override live JVM");
        Properties persisted=RestoreFiles.load(jobFile);persisted.setProperty("parentStartedAt",Instant.now().minusSeconds(86400).toString());RestoreFiles.write(jobFile,persisted);
        refuses(()->RestoreMain.run(new String[]{"--job",jobFile.toString(),"--manual","--i-confirm-server-stopped"}),"PID reuse/start mismatch must fail closed");
        refuses(()->RestoreMain.run(new String[]{"--job",jobFile.toString(),"--manual"}),"manual helper requires explicit stopped confirmation");
    }
    /** Helper fixture deliberately makes the post-spawn status unreadable. No server files touched. */
    public static final class BrokenHandshakeHelper {
        public static void main(String[] args)throws Exception {
            Path dir=Path.of(args[1]).getParent();Files.delete(dir.resolve("status.properties"));Files.createDirectory(dir.resolve("status.properties"));
            long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!Files.exists(dir.resolve("cancel.properties")) && System.nanoTime()<until)Thread.sleep(10);
            if(Files.exists(dir.resolve("cancel.properties")))Files.createFile(dir.resolve("disarmed-test-helper"));
        }
    }
    private static void handshakeFailureTest(Path fixture)throws Exception {
        Files.createDirectories(fixture);Path root=Files.createDirectory(fixture.resolve("server"));Files.writeString(root.resolve("original"),"untouched");Path backups=Files.createDirectory(fixture.resolve("backups"));Path state=Files.createDirectory(fixture.resolve("state"));Path archive=backups.resolve("snapshot.zip");zip(archive,Map.of("new","new"),List.of());
        Path fakeJar=fixture.resolve("broken-handshake-helper.jar");java.util.jar.Manifest manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class",BrokenHandshakeHelper.class.getName());
        String resource=BrokenHandshakeHelper.class.getName().replace('.','/')+".class";
        try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(fakeJar),manifest);var input=RestoreSafetyTest.class.getClassLoader().getResourceAsStream(resource)) {
            out.putNextEntry(new java.util.jar.JarEntry(resource));Objects.requireNonNull(input).transferTo(out);out.closeEntry();
        }
        RestoreCoordinator coordinator=new RestoreCoordinator(root,backups,state,fakeJar);var reservation=coordinator.reserve("snapshot",archive,RestoreFiles.sha(archive),Duration.ofMinutes(2));var result=coordinator.confirm(reservation.token());
        check(!result.helperReady(),"failed post-spawn handshake never authorizes shutdown");check(result.message().contains("durably cancelled"),"post-spawn failure reports durable disarming rather than false never-started claim");
        check(Files.isRegularFile(result.jobFile().getParent().resolve("cancel.properties")),"post-spawn error writes durable cancellation marker");
        await(()->Files.exists(result.jobFile().getParent().resolve("disarmed-test-helper")),Duration.ofSeconds(5),"test helper disarming",null,fixture);
        check(Files.readString(root.resolve("original")).equals("untouched"),"uncertain handshake leaves server untouched");
    }
    private static void integration(Path fixture,boolean forceReceiptFailure)throws Exception {
        Files.createDirectories(fixture);Path root=Files.createDirectory(fixture.resolve("server"));Files.createDirectories(root.resolve("world"));Files.writeString(root.resolve("world/level.dat"),"ORIGINAL-CURRENT");Files.writeString(root.resolve("not-in-backup.txt"),"PRESERVE ORIGINAL");
        Path backups=Files.createDirectory(fixture.resolve("backups"));Files.createDirectory(fixture.resolve("state"));zip(backups.resolve("snapshot.zip"),Map.of("world/level.dat","BACKED-UP","server.properties","motd=restored"),List.of("empty"));
        Process parent=spawn("--parent",fixture.toString(),helperJar.toString());
        try {
            Path ready=fixture.resolve("ready.properties");await(()->Files.exists(ready),Duration.ofSeconds(20),"parent readiness",parent,fixture);
            Properties report=RestoreFiles.load(ready);check(report.getProperty("helperReady").equals("true"),"helper must launch before shutdown");
            Path jobFile=Path.of(report.getProperty("jobFile"));Path jobDir=jobFile.getParent();Properties job=RestoreFiles.load(jobFile);
            try {check(Files.getPosixFilePermissions(jobDir).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),"job directory is owner-only");} catch(UnsupportedOperationException ignored) {}
            check(report.getProperty("duplicateJob").equals("true"),"repeated confirmation is idempotent and launches no second job");
            check(!job.containsKey("automaticDeadline"),"manual-stop reservation has no automatic expiry");
            check(job.getProperty("formatVersion").equals("2"),"manual-stop protocol version is persisted");
            check(statusEquals(jobDir,"WAITING_FOR_MANUAL_STOP"),"status is waiting for manual stop");
            Thread.sleep(400);check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL-CURRENT"),"live JVM data unchanged");check(!Files.exists(Path.of(job.getProperty("stage"))),"no staging before actual JVM exit");
            try(FileChannel guard=FileChannel.open(fixture.resolve("state").resolve(RestoreFiles.guardName(root)),StandardOpenOption.WRITE)) {FileLock lock=guard.tryLock();check(lock==null,"parent lifetime guard is held");if(lock!=null)lock.release();}
            if(forceReceiptFailure)Files.createDirectory(jobDir.resolve("receipt.properties"));
            Files.createFile(fixture.resolve("stop.request"));
            await(()->Files.exists(fixture.resolve("disable-observed")),Duration.ofSeconds(5),"simulated onDisable",parent,fixture);
            check(parent.isAlive(),"simulated onDisable is not JVM exit");check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL-CURRENT"),"no world writes during onDisable");
            check(parent.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"parent exits");
            String expected=forceReceiptFailure?"FAILED_ROLLED_BACK":"SUCCESS";
            await(()->statusEquals(jobDir,expected),Duration.ofSeconds(20),expected,null,fixture);
            Path rollback=Path.of(job.getProperty("rollback"));
            if(forceReceiptFailure) {
                check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL-CURRENT"),"failed receipt/install restores original root");
                check(Files.readString(root.resolve("not-in-backup.txt")).equals("PRESERVE ORIGINAL"),"rollback preserves excluded original file");
                check(Files.readString(Path.of(job.getProperty("failed")).resolve("world/level.dat")).equals("BACKED-UP"),"failed installation retained");
                check(!Files.exists(jobDir.resolve("receipt.properties")),"failure has no success receipt");
                awaitConfirmedDead(jobDir,job,Duration.ofSeconds(5),"failed helper exit",fixture);
                RestoreCoordinator coordinator=new RestoreCoordinator(root,backups,fixture.resolve("state"),helperJar);check(coordinator.startupFailure().isPresent(),"failure available for red startup notification");
            } else {
                check(Files.readString(root.resolve("world/level.dat")).equals("BACKED-UP"),"backup data installed only after exit");
                check(Files.readString(rollback.resolve("world/level.dat")).equals("ORIGINAL-CURRENT"),"entire original root preserved");
                check(Files.exists(rollback.resolve("not-in-backup.txt")) && !Files.exists(root.resolve("not-in-backup.txt")),"excluded files retained in rollback rather than silently merged");
                check(Files.isDirectory(root.resolve("empty")),"empty archive directories restored");
                try {check(Files.getPosixFilePermissions(root).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),"POSIX restored root is owner-only to protect secrets");} catch(UnsupportedOperationException ignored) {}
                Path receiptFile=jobDir.resolve("receipt.properties");Properties receipt=RestoreFiles.load(receiptFile);Properties altered=new Properties();altered.putAll(receipt);altered.setProperty("completedAt",Instant.EPOCH.toString());RestoreFiles.write(receiptFile,altered);
                refuses(()->new RestoreCoordinator(root,backups,fixture.resolve("state"),helperJar),"unauthenticated SUCCESS cannot permit startup");
                RestoreFiles.write(receiptFile,receipt);
                RestoreCoordinator coordinator=new RestoreCoordinator(root,backups,fixture.resolve("state"),helperJar);
                RestoreFiles.write(receiptFile,altered);refuses(coordinator::consumeVerifiedReceipt,"tampered receipt must not report success");RestoreFiles.write(receiptFile,receipt);
                Files.writeString(root.resolve("world/level.dat"),"NORMAL NEXT-START WRITE");
                check(coordinator.consumeVerifiedReceipt().isPresent(),"authentic offline verification receipt consumed despite later runtime changes");
                check(coordinator.consumeVerifiedReceipt().isEmpty(),"receipt notification consumed once");
            }
            check(Files.exists(backups.resolve("snapshot.zip")),"backup archive preserved");
        }finally{if(parent.isAlive()){parent.destroyForcibly();parent.waitFor();}}
    }
    /** Synchronous fault injection through status output; production code has no test hooks. */
    private static void rollbackJournalFailureTest(Path f,String injectionStatus,boolean blockRollback,boolean malformedJob)throws Exception {
        Path root=fixture(f),archive=f.resolve("backups/snapshot.zip"),state=f.resolve("state");
        Files.writeString(root.resolve("not-in-backup.txt"),"PRESERVE ORIGINAL");String archiveHash=RestoreFiles.sha(archive);
        Process parent=spawn("--sleeper");long pid=parent.pid();Instant start=parent.info().startInstant().orElseThrow();parent.destroyForcibly();parent.waitFor();
        Properties report=manualJob(root,f.resolve("backups"),state,archive,pid,start);Path file=Path.of(report.getProperty("jobFile")),dir=file.getParent(),status=dir.resolve("status.properties");Properties job=RestoreFiles.load(file);
        Path stage=Path.of(job.getProperty("stage")),rollback=Path.of(job.getProperty("rollback")),failed=Path.of(job.getProperty("failed"));
        PrintStream output=System.out;List<String> messages=new ArrayList<>();boolean[] injected={false};IOException failure=null;
        System.setOut(new PrintStream(output) {
            @Override public void println(String message) {
                super.println(message);messages.add(message);
                if(!injected[0] && message.contains("] "+injectionStatus+":")) {
                    try {
                        // The status write has completed at this point, after the first move.
                        if(malformedJob)Files.writeString(file,"bad="+(char)92+"uZZZZ\n");
                        else {Files.delete(status);Files.createDirectory(status);}
                        if(blockRollback) {
                            Files.createDirectory(root);Files.writeString(root.resolve("interfering.txt"),"PRESERVE INTERFERING ROOT");
                            Files.createDirectory(failed);Files.writeString(failed.resolve("existing.txt"),"PRESERVE EXISTING FAILED TREE");
                        }
                        injected[0]=true;
                    }catch(IOException e){throw new UncheckedIOException(e);}
                }
            }
        });
        try {RestoreMain.run(new String[]{"--job",file.toString(),"--manual","--i-confirm-server-stopped"});}
        catch(IOException e){failure=e;}
        finally{System.setOut(output);}
        check(injected[0],"journal fault was injected after the original root moved");
        Throwable original=malformedJob && failure!=null?failure.getCause():failure;
        check(malformedJob?original instanceof IllegalArgumentException:original instanceof FileSystemException fs && status.toString().equals(fs.getOtherFile()),"primary journal failure is retained");
        check(Arrays.stream(original.getSuppressed()).filter(e->malformedJob?e instanceof IllegalArgumentException:e instanceof FileSystemException fs && status.toString().equals(fs.getOtherFile())).count()>=2,"rollback journal failures are retained as suppressed errors");
        check(messages.stream().noneMatch(m->m.contains("] SUCCESS:")) && !Files.exists(dir.resolve("receipt.properties")),"journal failure never reports success or leaves a success receipt");
        check(malformedJob?Files.readString(file).equals("bad="+(char)92+"uZZZZ\n") && !statusEquals(dir,"SUCCESS"):Files.isDirectory(status),"damaged journal is not silently replaced or reported successful");
        check(RestoreFiles.sha(archive).equals(archiveHash),"journal failure preserves the backup archive");
        if(blockRollback) {
            check(Arrays.stream(original.getSuppressed()).anyMatch(e->e instanceof IOException && ("Refusing to replace existing path: "+failed).equals(e.getMessage())),"actual rollback move failure is retained alongside journal failures");
            check(Files.readString(rollback.resolve("world/level.dat")).equals("ORIGINAL") && Files.readString(rollback.resolve("not-in-backup.txt")).equals("PRESERVE ORIGINAL"),"blocked rollback preserves the complete original tree");
            check(Files.readString(root.resolve("interfering.txt")).equals("PRESERVE INTERFERING ROOT") && Files.readString(failed.resolve("existing.txt")).equals("PRESERVE EXISTING FAILED TREE"),"rollback never overwrites existing root or failed tree");
            check(Files.readString(stage.resolve("world/level.dat")).equals("BACKUP"),"blocked rollback retains verified stage");
        } else {
            check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL") && Files.readString(root.resolve("not-in-backup.txt")).equals("PRESERVE ORIGINAL"),"journal failure cannot prevent restoration of the original root");
            check(!Files.exists(rollback),"original rollback tree is atomically returned to its root");
            Path retained=injectionStatus.equals("ORIGINAL_MOVED")?stage:failed;
            check(Files.readString(retained.resolve("world/level.dat")).equals("BACKUP"),"journal failure retains the staged or failed installation");
            if(injectionStatus.equals("ORIGINAL_MOVED"))check(!Files.exists(failed),"pre-install rollback does not create a failed tree");
            else check(!Files.exists(stage),"installed tree is preserved only in its failed location");
        }
    }
    private static void recoveryTest(Path fixture)throws Exception {
        Files.createDirectories(fixture);Path root=Files.createDirectory(fixture.resolve("server"));Files.writeString(root.resolve("original"),"original");Path backups=Files.createDirectory(fixture.resolve("backups"));Path state=Files.createDirectory(fixture.resolve("state"));Path archive=backups.resolve("snapshot.zip");zip(archive,Map.of("new","new"),List.of());
        Process dead=spawn("--sleeper");long pid=dead.pid();Instant start=dead.info().startInstant().orElseThrow();dead.destroyForcibly();dead.waitFor();
        Properties job=manualJob(root,backups,state,archive,pid,start);Path jobFile=Path.of(job.getProperty("jobFile"));Properties stored=RestoreFiles.load(jobFile);
        try(FileChannel c=FileChannel.open(state.resolve(RestoreFiles.guardName(root)),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=c.lock()) {
            refuses(()->RestoreMain.run(new String[]{"--job",jobFile.toString(),"--manual","--i-confirm-server-stopped"}),"held guard blocks stopped-parent mutation");
        }
        Path rollback=Path.of(stored.getProperty("rollback"));Files.move(root,rollback);Path stage=Files.createDirectory(Path.of(stored.getProperty("stage")));Files.writeString(stage.resolve("new"),"new");
        RestoreFiles.status(jobFile.getParent(),"ORIGINAL_MOVED","simulated crash after first atomic rename");
        refuses(()->RestoreMain.run(new String[]{"--job",jobFile.toString(),"--manual","--i-confirm-server-stopped"}),"uncertain journal cannot resume install");
        check(RestoreMain.run(new String[]{"--job",jobFile.toString(),"--recover","--i-confirm-server-stopped"})==0,"explicit stopped recovery succeeds");
        check(Files.readString(root.resolve("original")).equals("original"),"crash recovery restores original");check(Files.exists(stage.resolve("new")),"crash recovery preserves stage");check(!Files.exists(jobFile.getParent().resolve("receipt.properties")),"recovery does not claim restore success");
    }
    private static Properties manualJob(Path root,Path backups,Path state,Path archive,long pid,Instant start)throws Exception {
        String id=UUID.randomUUID().toString();Path dir=state.resolve("restore-jobs").resolve(id);Files.createDirectories(dir);var verified=RestoreArchive.verify(archive,RestoreFiles.sha(archive));Properties p=new Properties();
        p.setProperty("formatVersion","1");p.setProperty("jobId",id);p.setProperty("backupId","test");p.setProperty("serverRoot",root.toString());p.setProperty("backupDirectory",backups.toString());p.setProperty("stateDirectory",state.toString());p.setProperty("archive",archive.toString());p.setProperty("archiveSha256",RestoreFiles.sha(archive));p.setProperty("manifestSha256",verified.manifestSha256());p.setProperty("entryCount",Integer.toString(verified.entries().size()));p.setProperty("totalBytes",Long.toString(verified.totalBytes()));p.setProperty("parentPid",Long.toString(pid));p.setProperty("parentStartedAt",start.toString());p.setProperty("nonce",RestoreFiles.randomSecret());p.setProperty("automaticDeadline",Instant.now().plusSeconds(60).toString());
        for(String kind:List.of("stage","rollback","failed"))p.setProperty(kind,root.resolveSibling(root.getFileName()+".vaultbackup-"+kind+"-"+id).toString());
        RestoreFiles.write(dir.resolve("job.properties"),p);Properties active=new Properties();active.setProperty("jobId",id);active.setProperty("backupId","test");RestoreFiles.write(state.resolve("active-restore.properties"),active);RestoreFiles.status(dir,"COMMITTED","fixture");Properties report=new Properties();report.setProperty("jobFile",dir.resolve("job.properties").toString());return report;
    }
    private static void parent(Path fixture,Path jar)throws Exception {
        Path root=fixture.resolve("server"),backups=fixture.resolve("backups"),state=fixture.resolve("state"),archive=backups.resolve("snapshot.zip");
        RestoreCoordinator coordinator=new RestoreCoordinator(root,backups,state,jar,"ja");
        RestoreCoordinator.LaunchResult result=reserveAndReport(coordinator,archive,fixture.resolve("ready.properties"));
        while(!Files.exists(fixture.resolve("stop.request"))) {
            if(Files.exists(fixture.resolve("cancel.request")) && !Files.exists(fixture.resolve("cancelled.properties")))cancelAndReport(coordinator,result.jobId(),fixture.resolve("cancelled.properties"));
            if(Files.exists(fixture.resolve("reserve-again.request")) && !Files.exists(fixture.resolve("ready2.properties")))result=reserveAndReport(coordinator,archive,fixture.resolve("ready2.properties"));
            if(Files.exists(fixture.resolve("cancel-again.request")) && !Files.exists(fixture.resolve("cancelled2.properties")))cancelAndReport(coordinator,result.jobId(),fixture.resolve("cancelled2.properties"));
            Thread.sleep(30);
        }
        coordinator.noteNormalShutdown();
        Files.createFile(fixture.resolve("disable-observed"));Thread.sleep(800);
        // No coordinator.close(), no restore in onDisable. Actual process exit releases guard.
    }
    private static RestoreCoordinator.LaunchResult reserveAndReport(RestoreCoordinator c,Path archive,Path report)throws Exception {
        var reservation=c.reserve("snapshot",archive,RestoreFiles.sha(archive),Duration.ofMinutes(2));var result=c.confirm(reservation.token());var duplicate=c.confirm(reservation.token());
        Properties p=new Properties();p.setProperty("helperReady",Boolean.toString(result.helperReady()));p.setProperty("jobFile",result.jobFile().toString());p.setProperty("message",result.message());p.setProperty("duplicateJob",Boolean.toString(result.jobId().equals(duplicate.jobId())));RestoreFiles.write(report,p);
        if(!result.helperReady())throw new IOException(result.message());return result;
    }
    private static void cancelAndReport(RestoreCoordinator c,String id,Path report)throws Exception {
        var result=c.cancelCommitted(id);Properties p=new Properties();p.setProperty("cancelled",Boolean.toString(result.cancelled()));p.setProperty("protected",Boolean.toString(c.protectedBackupIds().contains("snapshot")));p.setProperty("empty",Boolean.toString(c.status().isEmpty()));p.setProperty("idempotent",Boolean.toString(c.cancelCommitted(id).cancelled()));RestoreFiles.write(report,p);
    }
    private static Path fixture(Path f)throws Exception {
        Files.createDirectories(f);Path root=Files.createDirectory(f.resolve("server"));Files.createDirectories(root.resolve("world"));Files.writeString(root.resolve("world/level.dat"),"ORIGINAL");
        Path backups=Files.createDirectory(f.resolve("backups"));Files.createDirectory(f.resolve("state"));zip(backups.resolve("snapshot.zip"),Map.of("world/level.dat","BACKUP"),List.of());return root;
    }
    private static Path ready(Path f,Process parent)throws Exception {
        await(()->Files.exists(f.resolve("ready.properties")),Duration.ofSeconds(20),"reservation",parent,f);
        Properties report=RestoreFiles.load(f.resolve("ready.properties"));check(report.getProperty("helperReady").equals("true"),"helper reservation acknowledged");return Path.of(report.getProperty("jobFile"));
    }
    private static void cancelThenStopTest(Path f)throws Exception {
        Path root=fixture(f);Process parent=spawn("--parent",f.toString(),helperJar.toString());
        try {
            Path file=ready(f,parent),dir=file.getParent();Properties job=RestoreFiles.load(file);
            check(parent.isAlive(),"reservation never stops the server");Thread.sleep(500);
            check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL") && !Files.exists(Path.of(job.getProperty("stage"))),"no data or staging changes while reserved");
            check(RestoreFiles.load(dir.resolve("status.properties")).getProperty("message").contains("手動"),"Japanese helper status is persisted");
            Files.createFile(f.resolve("cancel.request"));await(()->Files.exists(f.resolve("cancelled.properties")),Duration.ofSeconds(10),"cancellation acknowledgment",parent,f);
            Properties c=RestoreFiles.load(f.resolve("cancelled.properties"));check(c.getProperty("cancelled").equals("true"),"committed cancellation is acknowledged");check(c.getProperty("protected").equals("false") && c.getProperty("empty").equals("true"),"acknowledged cancellation clears active marker and backup pin");check(c.getProperty("idempotent").equals("true"),"cancel by job ID remains idempotent after cleanup");
            check(RestoreJobState.acknowledged(dir,job),"bound authenticated cancellation acknowledgment is durable");
            Files.createFile(f.resolve("reserve-again.request"));await(()->Files.exists(f.resolve("ready2.properties")),Duration.ofSeconds(20),"second reservation",parent,f);
            Path second=Path.of(RestoreFiles.load(f.resolve("ready2.properties")).getProperty("jobFile"));
            check(!second.equals(file),"a new reservation can be made in the same JVM after cancellation");
            Process old=helper(file,"--manual","--i-confirm-server-stopped");check(old.waitFor(10,java.util.concurrent.TimeUnit.SECONDS) && old.exitValue()==3,"cancelled old job cannot be manually resumed even while new job is active");
            check(RestoreFiles.load(f.resolve("state/active-restore.properties")).getProperty("jobId").equals(second.getParent().getFileName().toString()),"cancelled old helper cannot clear or change newer active marker");
            Files.createFile(f.resolve("cancel-again.request"));await(()->Files.exists(f.resolve("cancelled2.properties")),Duration.ofSeconds(10),"second cancellation",parent,f);
            Files.createFile(f.resolve("stop.request"));check(parent.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"cancelled reservation parent exits normally");Thread.sleep(400);
            check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL"),"cancel then actual JVM stop never restores");
            check(!Files.exists(Path.of(job.getProperty("rollback"))) && !Files.exists(dir.resolve("receipt.properties")),"cancelled job never swaps or writes a success receipt");
            Process retry=helper(file,"--manual","--i-confirm-server-stopped");check(retry.waitFor(10,java.util.concurrent.TimeUnit.SECONDS) && retry.exitValue()==3,"cancelled job remains non-resumable after JVM exit");
            RestoreCoordinator restarted=new RestoreCoordinator(root,f.resolve("backups"),f.resolve("state"),helperJar);check(restarted.status().isEmpty(),"restart after acknowledged cancellation has no restore reservation");
        }finally{if(parent.isAlive()){parent.destroyForcibly();parent.waitFor();}}
    }
    private static void cancellationGateTest(Path f)throws Exception {
        Path root=fixture(f),archive=f.resolve("backups/snapshot.zip");RestoreCoordinator c=new RestoreCoordinator(root,f.resolve("backups"),f.resolve("state"),helperJar);
        var r=c.reserve("snapshot",archive,RestoreFiles.sha(archive),Duration.ofMinutes(1));var result=c.confirm(r.token());Path dir=result.jobFile().getParent();
        Process gate=spawn("--gate",dir.toString());
        try {
            await(()->Files.exists(dir.resolve("gate-held")),Duration.ofSeconds(5),"job gate",gate,f);
            var blocked=c.cancelCommitted();check(!blocked.cancelled(),"a cancellation blocked behind application never falsely acknowledges");check(!Files.exists(dir.resolve("cancel.properties")),"a losing cancellation cannot publish an out-of-order tombstone");check(c.protectedBackupIds().contains("snapshot"),"target archive remains pinned while cancellation is unacknowledged");
        }finally{Files.createFile(dir.resolve("gate-release"));gate.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);if(gate.isAlive())gate.destroyForcibly();}
        check(c.cancelCommitted().cancelled(),"cancellation succeeds after waiting gate is released");
        check(c.status().isEmpty(),"cancel does not deadlock with the waiting helper");
    }
    private static void holdGate(Path dir)throws Exception {
        try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)){Files.createFile(dir.resolve("gate-held"));while(!Files.exists(dir.resolve("gate-release")))Thread.sleep(20);}
    }
    private static void deadHelperTest(Path f)throws Exception {
        Path root=fixture(f),archive=f.resolve("backups/snapshot.zip");RestoreCoordinator c=new RestoreCoordinator(root,f.resolve("backups"),f.resolve("state"),helperJar);var r=c.reserve("snapshot",archive,RestoreFiles.sha(archive),Duration.ofMinutes(1));var result=c.confirm(r.token());Path dir=result.jobFile().getParent();Properties job=RestoreFiles.load(result.jobFile());
        long pid=Long.parseLong(RestoreFiles.load(dir.resolve("helper-process.properties")).getProperty("pid"));ProcessHandle handle=ProcessHandle.of(pid).orElseThrow();handle.destroyForcibly();handle.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS);
        check(c.status().orElseThrow().state().equals("HELPER_EXITED"),"dead helper is not presented as waiting/armed");check(c.protectedBackupIds().contains("snapshot"),"dead helper still pins unresolved job");check(c.cancelCommitted().cancelled(),"confirmed helper death safely acknowledges cancellation");check(RestoreJobState.acknowledged(dir,job),"confirmed-death cancellation has a durable authenticated acknowledgment");
    }
    /** No-op helper fixture for a deliberately unacknowledged cancellation, not production logic. */
    public static final class UnresponsiveHelper {
        public static void main(String[] args)throws Exception {
            Path dir=Path.of(args[1]).getParent();Properties p=new Properties();p.setProperty("status","WAITING_FOR_MANUAL_STOP");p.setProperty("message","fixture");
            Path temp=dir.resolve("fixture-status.tmp");try(OutputStream out=Files.newOutputStream(temp)){p.store(out,"");}Files.move(temp,dir.resolve("status.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            Thread.sleep(60000);
        }
    }
    private static Path fixtureJar(Path f,Class<?> entry)throws Exception {
        Path jar=f.resolve(entry.getSimpleName()+".jar");java.util.jar.Manifest manifest=new java.util.jar.Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class",entry.getName());String resource=entry.getName().replace('.','/')+".class";
        try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(jar),manifest);var input=entry.getClassLoader().getResourceAsStream(resource)){out.putNextEntry(new java.util.jar.JarEntry(resource));Objects.requireNonNull(input).transferTo(out);out.closeEntry();}return jar;
    }
    private static void pendingAcknowledgmentTest(Path f)throws Exception {
        Path root=fixture(f),archive=f.resolve("backups/snapshot.zip"),jar=fixtureJar(f,UnresponsiveHelper.class);RestoreCoordinator c=new RestoreCoordinator(root,f.resolve("backups"),f.resolve("state"),jar);var r=c.reserve("snapshot",archive,RestoreFiles.sha(archive),Duration.ofMinutes(1));var result=c.confirm(r.token());Path dir=result.jobFile().getParent();
        long pid=Long.parseLong(RestoreFiles.load(dir.resolve("helper-process.properties")).getProperty("pid"));ProcessHandle handle=ProcessHandle.of(pid).orElseThrow();
        try {
            var cancel=c.cancelCommitted();check(!cancel.cancelled(),"a durable request alone cannot claim cancellation acknowledged");check(c.status().orElseThrow().state().equals("CANCEL_PENDING"),"unresponsive helper remains visibly pending");check(c.protectedBackupIds().contains("snapshot") && Files.exists(f.resolve("state/active-restore.properties")),"pending cancellation keeps target pinned and active marker present");
            Properties bad=new Properties();bad.setProperty("jobId",result.jobId());bad.setProperty("cancelId","wrong");bad.setProperty("source","HELPER");bad.setProperty("acknowledgedAt",Instant.now().toString());bad.setProperty("authentication","0".repeat(64));RestoreFiles.write(dir.resolve("cancel-ack.properties"),bad);
            refuses(c::cancelCommitted,"mismatched cancellation acknowledgment cannot clear an active reservation");check(Files.exists(f.resolve("state/active-restore.properties")),"ambiguous acknowledgment retains active reservation");
        }finally{handle.destroyForcibly();handle.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS);}
        check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL"),"unacknowledged or forged cancellation never mutates server files");
    }
    private static void confirmationRaceTest(Path f)throws Exception {
        Path root=fixture(f),archive=f.resolve("backups/snapshot.zip");RestoreCoordinator c=new RestoreCoordinator(root,f.resolve("backups"),f.resolve("state"),helperJar);var r=c.reserve("snapshot",archive,RestoreFiles.sha(archive),Duration.ofMinutes(1));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var confirmed=pool.submit(()->c.confirm(r.token()));
            await(()->Files.exists(f.resolve("state/active-restore.properties")),Duration.ofSeconds(10),"concurrent commit publication",null,f);
            var cancelled=pool.submit(() -> { return c.cancelCommitted(); });var launch=confirmed.get(20,java.util.concurrent.TimeUnit.SECONDS);var cancel=cancelled.get(20,java.util.concurrent.TimeUnit.SECONDS);
            check(cancel.cancelled(),"confirmation/cancellation race resolves to acknowledged cancellation");check(c.status().isEmpty() && c.protectedBackupIds().isEmpty(),"race leaves no armed helper reservation or stale archive pin");
            var repeated=c.confirm(r.token());check(repeated.jobId().equals(launch.jobId()) && !repeated.helperReady(),"repeated confirm of a cancelled token cannot resurrect/launch the job");
            check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL"),"concurrent confirmation/cancellation leaves server untouched");
        }
    }
    private static void cancellationAmbiguityTest(Path f)throws Exception {
        Path root=fixture(f),archive=f.resolve("backups/snapshot.zip");RestoreCoordinator c=new RestoreCoordinator(root,f.resolve("backups"),f.resolve("state"),helperJar);var r=c.reserve("snapshot",archive,RestoreFiles.sha(archive),Duration.ofMinutes(1));var result=c.confirm(r.token());Path dir=result.jobFile().getParent();Properties job=RestoreFiles.load(result.jobFile());
        try(RestoreJobState.Gate gate=RestoreJobState.requireLock(dir)){Files.createDirectory(dir.resolve("cancel.properties"));}
        refuses(c::cancelCommitted,"ambiguous cancellation flag cannot produce a success acknowledgment");
        check(c.protectedBackupIds().contains("snapshot") && Files.exists(f.resolve("state/active-restore.properties")),"ambiguous cancellation retains archive pin and active journal");
        awaitConfirmedDead(dir,job,Duration.ofSeconds(10),"ambiguous cancellation fail-closed helper exit",f);
        Process retry=helper(result.jobFile(),"--manual","--i-confirm-server-stopped");check(retry.waitFor(10,java.util.concurrent.TimeUnit.SECONDS) && retry.exitValue()!=0,"manual mode also refuses ambiguous cancellation");check(Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL"),"ambiguous cancellation never permits application");
    }
    private static void killedParentTest(Path f)throws Exception {
        Path root=fixture(f);Process parent=spawn("--parent",f.toString(),helperJar.toString());Path file=ready(f,parent),dir=file.getParent();Properties job=RestoreFiles.load(file);
        parent.destroyForcibly();check(parent.waitFor(10,java.util.concurrent.TimeUnit.SECONDS),"abrupt parent termination observed");
        await(()->statusEquals(dir,"FAILED_BEFORE_SWAP"),Duration.ofSeconds(10),"crash refusal",null,f);
        check(!Files.exists(dir.resolve("normal-stop.properties")),"crash never forges normal-stop intent");check(!Files.exists(Path.of(job.getProperty("stage"))) && Files.readString(root.resolve("world/level.dat")).equals("ORIGINAL"),"crash without normal stop does not stage or restore");
        awaitConfirmedDead(dir,job,Duration.ofSeconds(10),"crash helper exit",f);
        RestoreCoordinator restarted=new RestoreCoordinator(root,f.resolve("backups"),f.resolve("state"),helperJar);check(restarted.startupFailure().isPresent(),"safe failed crash reservation is reported on restart");check(restarted.status().isEmpty(),"dead failed reservation is durably disarmed before startup cleanup");
        Process retry=helper(file,"--manual","--i-confirm-server-stopped");check(retry.waitFor(10,java.util.concurrent.TimeUnit.SECONDS) && retry.exitValue()==3,"startup-disarmed failed job cannot be manually resumed later");
    }
    private static Process helper(Path job,String...flags)throws IOException {
        List<String> command=new ArrayList<>();command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString());command.add("-jar");command.add(helperJar.toString());command.add("--job");command.add(job.toString());command.addAll(List.of(flags));return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }
    private static Process spawn(String...arguments)throws IOException {
        List<String> command=new ArrayList<>();command.add(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java").toString());command.add("-cp");command.add(System.getProperty("java.class.path"));command.add(RestoreSafetyTest.class.getName());command.addAll(List.of(arguments));return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }
    private interface Condition {boolean test()throws Exception;}
    private static void await(Condition c,Duration timeout,String label,Process parent,Path fixture)throws Exception {
        long until=System.nanoTime()+timeout.toNanos();while(System.nanoTime()<until){if(c.test())return;if(parent!=null && !parent.isAlive())throw new AssertionError("Parent exited before "+label+"; "+fixture);Thread.sleep(30);}throw new AssertionError("Timed out awaiting "+label+"; "+fixture);
    }
    /**
     * The OS may remove an exiting helper between ProcessHandle.of(pid) and
     * info().startInstant(). Production correctly refuses that uncertain sample.
     * Only this bounded test poll retries it; an IOException is never success,
     * and persistent uncertainty fails with the last exception as its cause.
     */
    private static void awaitConfirmedDead(Path dir,Properties job,Duration timeout,String label,Path fixture)throws Exception {
        long until=System.nanoTime()+timeout.toNanos();IOException lastFailure=null;
        while(System.nanoTime()<until) {
            try {if(RestoreJobState.confirmedDead(dir,job))return;}
            catch(IOException uncertain) {lastFailure=uncertain;}
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out awaiting "+label+"; "+fixture,lastFailure);
    }
    private static boolean statusEquals(Path dir,String expected)throws IOException{return RestoreFiles.status(dir).equals(expected);}
    private static void zip(Path destination,Map<String,String> files,List<String> dirs)throws IOException {
        StringBuilder manifest=new StringBuilder("VAULTBACKUP-MANIFEST\t1\n");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(destination))) {
            for(String dir:dirs){zip.putNextEntry(new ZipEntry(dir+"/"));zip.closeEntry();manifest.append("D\t0\t-\t").append(encoded(dir)).append('\n');}
            for(var entry:files.entrySet()){byte[] bytes=entry.getValue().getBytes(StandardCharsets.UTF_8);zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(bytes);zip.closeEntry();manifest.append("F\t").append(bytes.length).append('\t').append(HexFormat.of().formatHex(RestoreFiles.digest().digest(bytes))).append('\t').append(encoded(entry.getKey())).append('\n');}
            zip.putNextEntry(new ZipEntry(RestoreArchive.MANIFEST));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
    }
    private static String encoded(String text){return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));}
}
