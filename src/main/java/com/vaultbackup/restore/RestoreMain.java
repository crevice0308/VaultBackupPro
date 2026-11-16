// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;

/** Standalone Java 21 helper. No Bukkit, network, restart, or shell dependency. */
public final class RestoreMain {
    private record Job(Path file, Path dir, Properties p, Path root, Path state, Path archive,
                       Path stage, Path rollback, Path failed) {}
    private RestoreMain() {}
    public static void main(String[] args) {
        int code;
        try {code=run(args);}catch(Exception e){System.err.println("復元を拒否、または失敗しました / RESTORE REFUSED OR FAILED: "+e.getMessage());e.printStackTrace(System.err);code=2;}
        if(code!=0)System.exit(code);
    }
    public static int run(String[] args)throws IOException {
        Path jobFile=null;boolean manual=false,stopped=false,recover=false;
        for(int i=0;i<args.length;i++) {
            switch(args[i]) {
                case "--job" -> {if(++i>=args.length)throw new IOException("--job requires a path");jobFile=Path.of(args[i]);}
                case "--manual" -> manual=true;
                case "--i-confirm-server-stopped" -> stopped=true;
                case "--recover" -> {manual=true;recover=true;}
                default -> throw new IOException("Unknown argument: "+args[i]);
            }
        }
        if(jobFile==null)throw new IOException("Usage: java -jar vaultbackup-restore.jar --job /absolute/job.properties [--manual --i-confirm-server-stopped | --recover --i-confirm-server-stopped]");
        if(manual!=stopped)throw new IOException("Manual execution/recovery requires both --manual (or --recover) and --i-confirm-server-stopped");
        Job job=validateJob(jobFile);
        // Held for this helper's whole run. Startup treats an uncontended helper.lock as proof that
        // no helper for the job is alive, so it is taken before the first job-gate check.
        Instant observedParent=null;
        try(FileChannel helperChannel=RestoreFiles.openLockFile(job.dir.resolve("helper.lock"))) {
            FileLock helperLock;
            try{helperLock=helperChannel.tryLock();}catch(OverlappingFileLockException e){throw new IOException("This restore helper is already running",e);}
            if(helperLock==null)throw new IOException("This restore helper is already running");
            try(helperLock) {
                try {
                    // helper.lock prevents duplicate helpers. job.lock instead linearizes state changes
                    // with the coordinator and is deliberately released for the entire waiting period.
                    try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                        if(cancelled(job))return 3;
                        RestoreJobState.requireActive(job.state,job.p);
                        RestoreJobState.recordProcess(job.dir,job.p,ProcessHandle.current());
                        // Recovery decides parent death under the root guard, which every living
                        // server JVM holds: a reused PID must not block it after a crash and reboot.
                        if(recover){RestoreFiles.refuseLiveParent(job.p);try(Guard guard=guard(job)){RestoreFiles.requireDeadParent(job.p,null,true);recover(job);return 0;}}
                        String status=RestoreFiles.status(job.dir);
                        if(Files.exists(job.dir.resolve("recovery-receipt.properties"),RestoreFiles.NOFOLLOW)) {
                            RestoreJobState.verifiedRecovery(job.dir,job.p);
                            throw new IOException("This job already completed original-root recovery and cannot restore again. Start the server to reconcile its completed-recovery receipt.");
                        }
                        if(status.equals("SUCCESS")){System.out.println(RestoreFiles.message(job.p,"Journal reports SUCCESS. No changes made; validate the receipt before startup. Rollback: ","記録は SUCCESS です。変更していません。起動前に完了証明を検証してください。元データ: ")+job.rollback);return 0;}
                        if(!Set.of("COMMITTED","READY","WAITING_FOR_MANUAL_STOP","EXPIRED","FAILED_BEFORE_SWAP").contains(status))throw new IOException("Job journal is "+status+"; inspect it and use --recover while stopped. Never resume an uncertain swap.");
                        if(!manual) {
                            if(!RestoreFiles.required(job.p,"formatVersion").equals("2") || !"MANUAL_NORMAL_STOP".equals(job.p.getProperty("shutdownPolicy")))throw new IOException("Old or unknown job protocol cannot wait automatically; review it using explicit stopped-server manual mode");
                            observedParent=validateLivingParent(job.p);
                            status(job,"WAITING_FOR_MANUAL_STOP","Reserved. Waiting without a time limit for your normal server stop and actual JVM exit. No restart will be performed.");
                        } else RestoreFiles.refuseLiveParent(job.p);
                    }
                    if(!manual && !waitForExit(job,observedParent))return 3;
                    try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                        if(cancelled(job))return 3;
                        RestoreJobState.requireActive(job.state,job.p);
                        if(!manual){RestoreFiles.requireDeadParent(job.p,observedParent,false);RestoreJobState.requireNormalShutdown(job.dir,job.p);}
                        try(Guard guard=guard(job)) {
                            RestoreFiles.requireDeadParent(job.p,observedParent,true);
                            status(job,"PARENT_EXITED","Server shutdown path and recorded JVM exit confirmed; beginning offline application");
                            // This gate remains held through staging, application, verification, and
                            // rollback. A late cancel can fail, but can NEVER falsely acknowledge.
                            execute(job,observedParent);
                        }
                    }
                } catch(IOException e) {
                    try(RestoreJobState.Gate gate=RestoreJobState.lock(job.dir,Duration.ofSeconds(2))) {
                        if(gate!=null && !RestoreJobState.hasCancellation(job.dir) && RestoreJobState.waiting(RestoreFiles.status(job.dir)))
                            status(job,"FAILED_BEFORE_SWAP",e.getMessage());
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
    private static Job validateJob(Path file)throws IOException {
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
        Path backups=RestoreFiles.directory(Path.of(RestoreFiles.required(p,"backupDirectory")),false);
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
     * Returns the parent's start instant as observed by THIS JVM. Each JVM derives start instants
     * from its own cached boot time, so after a wall-clock step the value recorded by the server
     * JVM can differ for the same living process. Being that process's direct child is the proof
     * then, and every later comparison in this helper uses its own observation.
     */
    private static Instant validateLivingParent(Properties p)throws IOException {
        long pid;Instant started;
        try{pid=Long.parseLong(RestoreFiles.required(p,"parentPid"));started=Instant.parse(RestoreFiles.required(p,"parentStartedAt"));}
        catch(RuntimeException e){throw new IOException("Invalid parent process identity",e);}
        if(pid==ProcessHandle.current().pid())throw new IOException("Helper cannot be its own parent");
        ProcessHandle process=ProcessHandle.of(pid).orElseThrow(()->new IOException("Automatic helper must be launched before parent exit; use explicit stopped-server manual mode"));
        Optional<Instant> actual=process.info().startInstant();
        if(!process.isAlive() || actual.isEmpty())throw new IOException("Parent identity cannot be established; refusing restore");
        if(actual.get().equals(started))return actual.get();
        Optional<ProcessHandle> parent=ProcessHandle.current().parent();
        if(parent.isPresent() && parent.get().pid()==pid && parent.get().isAlive())return actual.get();
        throw new IOException("Parent identity cannot be established; refusing restore");
    }
    private static boolean waitForExit(Job job,Instant observed)throws IOException {
        long pid=Long.parseLong(RestoreFiles.required(job.p,"parentPid"));
        while(true) {
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                if(cancelled(job))return false;
                RestoreJobState.requireActive(job.state,job.p);
            }
            Optional<ProcessHandle> process=ProcessHandle.of(pid);
            if(process.isEmpty() || RestoreFiles.exitedButUnreaped(pid))break;
            Optional<Instant> actual=process.get().info().startInstant();
            if(actual.isEmpty())throw new IOException("Parent PID identity became uncertain; refusing restore");
            // Same-JVM observations are exact: a different start instant is a different process,
            // so the watched parent has exited and its PID was reused (common on Windows).
            if(!actual.get().equals(observed) || !process.get().isAlive())break;
            // There is deliberately no clock deadline after the bounded startup handshake.
            try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while waiting for JVM exit",e);}
        }
        RestoreFiles.requireDeadParent(job.p,observed,false);return true;
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
    /** Free space the stage needs beside the root: the full expanded archive plus a margin. */
    static long requiredStageSpace(Properties job)throws IOException {
        long total,entries;
        try{total=Long.parseLong(RestoreFiles.required(job,"totalBytes"));entries=Long.parseLong(RestoreFiles.required(job,"entryCount"));}
        catch(RuntimeException e){throw new IOException("Invalid restore scope",e);}
        return requiredStageSpace(total,entries);
    }
    static long requiredStageSpace(long total,long entries)throws IOException {
        if(total<0 || entries<0)throw new IOException("Invalid restore scope");
        long perEntry=entries>Long.MAX_VALUE/4096?Long.MAX_VALUE:entries*4096;
        long margin=64L<<20;
        long required=total>Long.MAX_VALUE-perEntry?Long.MAX_VALUE:total+perEntry;
        return required>Long.MAX_VALUE-margin?Long.MAX_VALUE:required+margin;
    }
    static String bytes(long value) {
        if(value<1024)return value+" B";
        String[] units={"KiB","MiB","GiB","TiB","PiB","EiB"};double v=value;int u=-1;
        do{v/=1024;u++;}while(v>=1024 && u<units.length-1);
        return String.format(Locale.ROOT,"%.1f %s",v,units[u]);
    }
    /** Removes a stage this attempt created; it holds only data that is still in the verified archive. */
    private static void deleteStage(Path stage)throws IOException {
        if(!Files.exists(stage,RestoreFiles.NOFOLLOW))return;
        RestoreFiles.directory(stage,false);
        Files.walkFileTree(stage,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException exception)throws IOException {
                if(exception!=null)throw exception;Files.delete(dir);return FileVisitResult.CONTINUE;
            }
        });
        RestoreFiles.syncDirectory(stage.getParent());
    }
    private static void execute(Job job,Instant observedParent)throws IOException {
        boolean originalMoved=false,installed=false,moveAttempted=false,stageCreated=false;
        try {
            RestoreFiles.directory(job.root,false);checkWorldLocks(job.root);
            for(Path target:List.of(job.stage,job.rollback,job.failed))if(Files.exists(target,RestoreFiles.NOFOLLOW))throw new IOException("Recovery path already exists: "+target);
            String encoding=job.p.getProperty("fileNameEncoding");
            if(encoding!=null && !RestoreArchive.WINDOWS && !encoding.equals(System.getProperty("sun.jnu.encoding")))
                throw new IOException("This helper uses file-name encoding "+System.getProperty("sun.jnu.encoding")+", but the server used "+encoding
                        +". Run it with the server's locale (the same LANG/LC_ALL) so restored file names are unchanged. Nothing was changed.");
            long required=requiredStageSpace(job.p),usable=Files.getFileStore(job.root).getUsableSpace();
            if(usable<required)throw new IOException("Not enough free space beside the server root to stage this restore: about "+bytes(required)
                    +" is needed, "+bytes(usable)+" is available. Nothing was changed.");
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
            RestoreFiles.requireDeadParent(job.p,observedParent,true);checkWorldLocks(job.root);
            if(cancelled(job)) {
                try{deleteStage(job.stage);}catch(IOException ignored){ /* A retained cancelled stage is accepted at startup. */ }
                return;
            }
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
        } catch(Exception error) {
            // A move can succeed while the subsequent directory fsync fails. Inspect existence
            // before deciding whether rollback is needed, rather than trusting the local flags.
            originalMoved=originalMoved || (moveAttempted && Files.exists(job.rollback,RestoreFiles.NOFOLLOW));
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
                    try{status(job,"FAILED_ROLLED_BACK","Original root restored; failed/staged data retained. "+error.getMessage());}catch(Exception statusError){error.addSuppressed(statusError);}
                } catch(Exception rollbackError) {
                    error.addSuppressed(rollbackError);
                    try{status(job,"RECOVERY_REQUIRED","Automatic rollback was incomplete. Do not start server. Preserve every sibling and use --recover while stopped. "+error.getMessage());}catch(Exception statusError){error.addSuppressed(statusError);}
                }
            } else {
                boolean rollbackExists=Files.exists(job.rollback,RestoreFiles.NOFOLLOW);
                String stageNote="";
                // Before any move, the stage is only a partial copy of the verified archive. Freeing
                // it matters most after a full disk, where the journal write below needs the space.
                if(stageCreated && !moveAttempted && !rollbackExists && !Files.exists(job.failed,RestoreFiles.NOFOLLOW)) {
                    try{deleteStage(job.stage);stageNote="Partial stage removed. ";}
                    catch(Exception cleanup){error.addSuppressed(cleanup);stageNote="Stage retained at "+job.stage+". ";}
                } else if(Files.exists(job.stage,RestoreFiles.NOFOLLOW))stageNote="Stage retained at "+job.stage+". ";
                try{status(job,rollbackExists ? "RECOVERY_REQUIRED" : "FAILED_BEFORE_SWAP","Original server-root was not moved by this attempt. "+stageNote+error.getMessage());}catch(Exception statusError){error.addSuppressed(statusError);}
            }
            if(error instanceof IOException io)throw io;throw new IOException("Restore failed",error);
        }
    }
    private static void recover(Job job)throws IOException {
        String status=RestoreFiles.status(job.dir);
        if(Files.exists(job.dir.resolve("recovery-receipt.properties"),RestoreFiles.NOFOLLOW)) {
            RestoreJobState.verifiedRecovery(job.dir,job.p);
            System.out.println(RestoreFiles.message(job.p,"Original-root recovery was already completed and authenticated. No changes made.","元データの復旧は既に完了し、証明を確認しました。変更していません。"));return;
        }
        if(status.equals("SUCCESS"))throw new IOException("Successful jobs are not automatically reverted. Original rollback is preserved; use a separate reviewed recovery plan.");
        RestoreFiles.requireDeadParent(job.p,null,true);checkWorldLocks(job.root);checkWorldLocks(job.rollback);
        if(Files.exists(job.rollback,RestoreFiles.NOFOLLOW)) {
            RestoreFiles.directory(job.rollback,false);
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
        } else if(Set.of("MOVING_ORIGINAL","ORIGINAL_MOVED","INSTALLING","VERIFYING_INSTALLED","ROLLING_BACK","RECOVERY_REQUIRED").contains(status)
                && Files.isDirectory(job.root,RestoreFiles.NOFOLLOW) && !Files.isSymbolicLink(job.root)
                && (Files.exists(job.stage,RestoreFiles.NOFOLLOW) || Files.exists(job.failed,RestoreFiles.NOFOLLOW))) {
            // The rollback tree is gone, yet the archive tree is still accounted for in stage or
            // failed, so root holds the original: the rename back completed, and only the journal
            // write after it was lost (crash, full disk or I/O error), or the first rename never ran.
            RestoreFiles.directory(job.root,false);
            for(Path sibling:List.of(job.stage,job.failed))if(Files.exists(sibling,RestoreFiles.NOFOLLOW))RestoreFiles.directory(sibling,false);
            Files.deleteIfExists(job.dir.resolve("receipt.properties"));
            RestoreJobState.recordCompletedRecovery(job.dir,job.p,"RECOVERED");
            status(job,"RECOVERED","Original root is already in place. Stage/failed tree preserved. Server remains stopped.");
            System.out.println(RestoreFiles.message(job.p,"Original server-root is already in place; recovery recorded. No restart performed.","元のサーバーデータは既に元の場所にあります。復旧を記録しました。自動再起動は行いません。"));
        } else if(Set.of("COMMITTED","READY","WAITING_FOR_MANUAL_STOP","PARENT_EXITED","STAGING","STAGED","FAILED_BEFORE_SWAP","CANCELLED","EXPIRED").contains(status)
                && Files.isDirectory(job.root,RestoreFiles.NOFOLLOW)) {
            status(job,"FAILED_BEFORE_SWAP","No original-root move was recorded; current root left untouched. Stage preserved.");
            System.out.println(RestoreFiles.message(job.p,"No original-root move recorded. Root was left untouched; stage retained.","元データの移動記録はありません。現在のデータと作業領域を保持しました。"));
        } else throw new IOException("Cannot prove original root location from journal. No data changed. Inspect root, stage, rollback, and failed siblings manually.");
    }
}
