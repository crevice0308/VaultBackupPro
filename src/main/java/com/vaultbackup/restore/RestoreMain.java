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
        try(FileChannel helperChannel=FileChannel.open(job.dir.resolve("helper.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
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
                        if(recover){RestoreFiles.requireDeadParent(job.p);try(Guard guard=guard(job)){recover(job);return 0;}}
                        String status=RestoreFiles.status(job.dir);
                        if(status.equals("SUCCESS")){System.out.println(RestoreFiles.message(job.p,"Journal reports SUCCESS. No changes made; validate the receipt before startup. Rollback: ","記録は SUCCESS です。変更していません。起動前に完了証明を検証してください。元データ: ")+job.rollback);return 0;}
                        if(!Set.of("COMMITTED","READY","WAITING_FOR_MANUAL_STOP","EXPIRED","FAILED_BEFORE_SWAP").contains(status))throw new IOException("Job journal is "+status+"; inspect it and use --recover while stopped. Never resume an uncertain swap.");
                        if(!manual) {
                            if(!RestoreFiles.required(job.p,"formatVersion").equals("2") || !"MANUAL_NORMAL_STOP".equals(job.p.getProperty("shutdownPolicy")))throw new IOException("Old or unknown job protocol cannot wait automatically; review it using explicit stopped-server manual mode");
                            validateLivingParent(job.p);
                            RestoreFiles.status(job.dir,"WAITING_FOR_MANUAL_STOP","Reserved. Waiting without a time limit for your normal server stop and actual JVM exit. No restart will be performed.");
                        } else RestoreFiles.requireDeadParent(job.p);
                    }
                    if(!manual && !waitForExit(job))return 3;
                    try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                        if(cancelled(job))return 3;
                        RestoreJobState.requireActive(job.state,job.p);
                        RestoreFiles.requireDeadParent(job.p);
                        if(!manual)RestoreJobState.requireNormalShutdown(job.dir,job.p);
                        try(Guard guard=guard(job)) {
                            RestoreFiles.requireDeadParent(job.p);
                            RestoreFiles.status(job.dir,"PARENT_EXITED","Server shutdown path and recorded JVM exit confirmed; beginning offline application");
                            // This gate remains held through staging, application, verification, and
                            // rollback. A late cancel can fail, but can NEVER falsely acknowledge.
                            execute(job);
                        }
                    }
                } catch(IOException e) {
                    try(RestoreJobState.Gate gate=RestoreJobState.lock(job.dir,Duration.ofSeconds(2))) {
                        if(gate!=null && !RestoreJobState.hasCancellation(job.dir) && RestoreJobState.waiting(RestoreFiles.status(job.dir)))
                            RestoreFiles.status(job.dir,"FAILED_BEFORE_SWAP",e.getMessage());
                    }catch(IOException journalError){e.addSuppressed(journalError);}
                    throw e;
                }
            }
        }
        return 0;
    }
    private static boolean cancelled(Job job)throws IOException {
        if(!RestoreJobState.hasCancellation(job.dir))return false;
        // An invalid/symlink/partial flag is an error, never permission to continue.
        RestoreJobState.acknowledgeCancellation(job.dir,job.p,"HELPER");return true;
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
    private static void validateLivingParent(Properties p)throws IOException {
        long pid;Instant started;
        try{pid=Long.parseLong(RestoreFiles.required(p,"parentPid"));started=Instant.parse(RestoreFiles.required(p,"parentStartedAt"));}
        catch(RuntimeException e){throw new IOException("Invalid parent process identity",e);}
        ProcessHandle process=ProcessHandle.of(pid).orElseThrow(()->new IOException("Automatic helper must be launched before parent exit; use explicit stopped-server manual mode"));
        if(!process.isAlive() || process.info().startInstant().isEmpty() || !process.info().startInstant().get().equals(started))throw new IOException("Parent identity cannot be established; refusing restore");
        if(pid==ProcessHandle.current().pid())throw new IOException("Helper cannot be its own parent");
    }
    private static boolean waitForExit(Job job)throws IOException {
        long pid=Long.parseLong(RestoreFiles.required(job.p,"parentPid"));Instant started=Instant.parse(RestoreFiles.required(job.p,"parentStartedAt"));
        while(true) {
            try(RestoreJobState.Gate gate=RestoreJobState.requireLock(job.dir)) {
                if(cancelled(job))return false;
                RestoreJobState.requireActive(job.state,job.p);
            }
            Optional<ProcessHandle> process=ProcessHandle.of(pid);
            if(process.isEmpty())break;
            Optional<Instant> actual=process.get().info().startInstant();
            if(actual.isEmpty() || !actual.get().equals(started))throw new IOException("Parent PID was reused or identity became uncertain; refusing restore");
            if(!process.get().isAlive())break;
            // There is deliberately no clock deadline after the bounded startup handshake.
            try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while waiting for JVM exit",e);}
        }
        RestoreFiles.requireDeadParent(job.p);return true;
    }
    private record Guard(FileChannel channel,FileLock lock) implements AutoCloseable {
        @Override public void close()throws IOException{lock.release();channel.close();}
    }
    private static Guard guard(Job job)throws IOException {
        FileChannel channel=FileChannel.open(job.state.resolve(RestoreFiles.guardName(job.root)),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException e){channel.close();throw new IOException("Server root guard is still held",e);}
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
    private static void execute(Job job)throws IOException {
        boolean originalMoved=false,installed=false,moveAttempted=false;
        try {
            RestoreFiles.directory(job.root,false);checkWorldLocks(job.root);
            for(Path target:List.of(job.stage,job.rollback,job.failed))if(Files.exists(target,RestoreFiles.NOFOLLOW))throw new IOException("Recovery path already exists: "+target);
            RestoreFiles.status(job.dir,"STAGING","Extracting and verifying archive into a sibling directory; original root unchanged");
            RestoreFiles.createPrivateDirectory(job.stage);
            if(!Files.getFileStore(job.stage).equals(Files.getFileStore(job.root)))throw new IOException("Stage and server-root must be on the same filesystem");
            RestoreArchive.Verified verified=RestoreArchive.extract(job.archive,RestoreFiles.required(job.p,"archiveSha256"),job.stage);
            if(!verified.manifestSha256().equals(RestoreFiles.required(job.p,"manifestSha256")))throw new IOException("Preview manifest changed");
            if(verified.entries().size()!=Integer.parseInt(RestoreFiles.required(job.p,"entryCount")) || verified.totalBytes()!=Long.parseLong(RestoreFiles.required(job.p,"totalBytes")))throw new IOException("Preview scope changed");
            try (var directories = Files.walk(job.stage)) {
                for (Path directory : directories.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) RestoreFiles.syncDirectory(directory);
            }
            RestoreFiles.status(job.dir,"STAGED","All staged data verified; preparing atomic rename");
            RestoreFiles.requireDeadParent(job.p);checkWorldLocks(job.root);
            if(cancelled(job))return;
            RestoreFiles.status(job.dir,"MOVING_ORIGINAL","Moving original root to preserved rollback directory");
            moveAttempted=true;RestoreFiles.move(job.root,job.rollback);originalMoved=true;
            RestoreFiles.status(job.dir,"ORIGINAL_MOVED","Original root safely preserved at "+job.rollback);
            RestoreFiles.status(job.dir,"INSTALLING","Installing verified stage into original server-root path");
            RestoreFiles.move(job.stage,job.root);installed=true;
            RestoreFiles.status(job.dir,"VERIFYING_INSTALLED","Checking every installed file before issuing success receipt");
            RestoreArchive.verifyInstalled(job.root,verified);
            Properties receipt=new Properties();
            for(String key:List.of("jobId","backupId","serverRoot","archiveSha256","manifestSha256"))receipt.setProperty(key,RestoreFiles.required(job.p,key));
            receipt.setProperty("completedAt",Instant.now().toString());receipt.setProperty("rollbackDirectory",job.rollback.toString());
            receipt.setProperty("scope","Exact archive tree installed; excluded/unbacked original files remain in rollback");
            receipt.setProperty("authentication",RestoreFiles.hmac(RestoreFiles.required(job.p,"nonce"),RestoreFiles.receiptPayload(receipt)));
            RestoreFiles.write(job.dir.resolve("receipt.properties"),receipt);
            RestoreFiles.status(job.dir,"SUCCESS","Offline installed data verified. Server remains stopped. Original root retained at "+job.rollback);
            System.out.println(RestoreFiles.message(job.p,"Offline restore verified. No restart was performed. Original data: ","オフライン復元の検証が完了しました。自動再起動は行いません。元データ: ")+job.rollback);
        } catch(Exception error) {
            // A move can succeed while the subsequent directory fsync fails. Inspect existence
            // before deciding whether rollback is needed, rather than trusting the local flags.
            originalMoved=originalMoved || (moveAttempted && Files.exists(job.rollback,RestoreFiles.NOFOLLOW));
            installed=installed || (originalMoved && Files.exists(job.root,RestoreFiles.NOFOLLOW));
            if(originalMoved) {
                try {
                    RestoreFiles.status(job.dir,"ROLLING_BACK","Install failed; restoring untouched original root");
                    if(installed)RestoreFiles.move(job.root,job.failed);
                    RestoreFiles.move(job.rollback,job.root);
                    Files.deleteIfExists(job.dir.resolve("receipt.properties"));
                    RestoreFiles.status(job.dir,"FAILED_ROLLED_BACK","Original root restored; failed/staged data retained. "+error.getMessage());
                } catch(Exception rollbackError) {
                    error.addSuppressed(rollbackError);
                    try{RestoreFiles.status(job.dir,"RECOVERY_REQUIRED","Automatic rollback was incomplete. Do not start server. Preserve every sibling and use --recover while stopped. "+error.getMessage());}catch(IOException statusError){error.addSuppressed(statusError);}
                }
            } else {
                try{RestoreFiles.status(job.dir,Files.exists(job.rollback,RestoreFiles.NOFOLLOW) ? "RECOVERY_REQUIRED" : "FAILED_BEFORE_SWAP","Original server-root was not moved by this attempt. Stage retained. "+error.getMessage());}catch(IOException statusError){error.addSuppressed(statusError);}
            }
            if(error instanceof IOException io)throw io;throw new IOException("Restore failed",error);
        }
    }
    private static void recover(Job job)throws IOException {
        String status=RestoreFiles.status(job.dir);
        if(status.equals("SUCCESS"))throw new IOException("Successful jobs are not automatically reverted. Original rollback is preserved; use a separate reviewed recovery plan.");
        RestoreFiles.requireDeadParent(job.p);checkWorldLocks(job.root);checkWorldLocks(job.rollback);
        if(Files.exists(job.rollback,RestoreFiles.NOFOLLOW)) {
            RestoreFiles.directory(job.rollback,false);
            RestoreFiles.status(job.dir,"ROLLING_BACK","Explicit stopped-server recovery restoring preserved original root");
            if(Files.exists(job.root,RestoreFiles.NOFOLLOW)) {
                if(Files.exists(job.failed,RestoreFiles.NOFOLLOW))throw new IOException("Failed-tree preservation target already exists. No data overwritten; manual inspection required.");
                RestoreFiles.move(job.root,job.failed);
            }
            RestoreFiles.move(job.rollback,job.root);
            Files.deleteIfExists(job.dir.resolve("receipt.properties"));
            RestoreFiles.status(job.dir,"RECOVERED","Original root restored. Stage/failed tree preserved. Server remains stopped.");
            System.out.println(RestoreFiles.message(job.p,"Original server-root recovered. No restart performed.","元のサーバーデータを復旧しました。自動再起動は行いません。"));
        } else if(Set.of("COMMITTED","READY","WAITING_FOR_MANUAL_STOP","PARENT_EXITED","STAGING","STAGED","FAILED_BEFORE_SWAP","CANCELLED","EXPIRED").contains(status)
                && Files.isDirectory(job.root,RestoreFiles.NOFOLLOW)) {
            RestoreFiles.status(job.dir,"RECOVERED","No original-root move was recorded; current root left untouched. Stage preserved.");
            System.out.println(RestoreFiles.message(job.p,"No original-root move recorded. Root was left untouched; stage retained.","元データの移動記録はありません。現在のデータと作業領域を保持しました。"));
        } else throw new IOException("Cannot prove original root location from journal. No data changed. Inspect root, stage, rollback, and failed siblings manually.");
    }
}
