// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small, dependency-free filesystem primitives shared by the plugin and offline helper. */
final class RestoreFiles {
    private RestoreFiles() {}
    static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    static Path directory(Path path, boolean create) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor=absolute.getRoot();
        for(Path component:absolute) { cursor=cursor.resolve(component); if(Files.isSymbolicLink(cursor)) throw new IOException("Symlink directory components are not permitted: "+cursor); }
        if (create) {
            try { Files.createDirectories(absolute, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))); }
            catch (UnsupportedOperationException e) { Files.createDirectories(absolute); }
        }
        if (!Files.isDirectory(absolute, NOFOLLOW) || Files.isSymbolicLink(absolute))
            throw new IOException("Not a real directory: " + absolute);
        Path real = absolute.toRealPath();
        if (!sameSpelling(real, absolute)) throw new IOException("Symlink directory components are not permitted: " + absolute);
        return real;
    }
    /**
     * macOS Path.of() stores names decomposed (NFD) while realpath(3) returns the name as stored,
     * often precomposed. Both string forms are NFC there, so compare those; the per-component
     * symlink checks remain the actual link detection. Elsewhere the bytes must match exactly.
     */
    private static boolean sameSpelling(Path real, Path absolute) {
        if (real.equals(absolute)) return true;
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") && real.toString().equals(absolute.toString());
    }
    static void createPrivateDirectory(Path path) throws IOException {
        try { Files.createDirectory(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))); }
        catch (UnsupportedOperationException e) { Files.createDirectory(path); }
    }
    static void external(Path root, Path other, String name) throws IOException {
        if (other.startsWith(root) || root.startsWith(other))
            throw new IOException(name + " must be outside and must not contain server-root: " + root);
    }
    static void regular(Path file) throws IOException {
        if (!Files.isRegularFile(file, NOFOLLOW) || Files.isSymbolicLink(file)
                || !sameSpelling(file.toRealPath(), file.toAbsolutePath().normalize()))
            throw new IOException("Expected a regular file without symlink components: " + file);
    }
    static Properties load(Path file) throws IOException {
        regular(file);
        if (Files.size(file) > 128 * 1024) throw new IOException("State file is unexpectedly large: " + file);
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return p;
    }
    /** Journal files can hold the job's HMAC secret: owner-only from creation, not after writing. */
    static FileChannel createPrivateFile(Path file) throws IOException {
        Set<OpenOption> open = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            return FileChannel.open(file, open, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            return FileChannel.open(file, open);
        }
    }
    /**
     * Lock files are owner-only: on POSIX, any account able to open one could hold a shared lock
     * and block the exclusive locks restore safety depends on. Files from older versions are
     * tightened in place; the inode other processes lock is never replaced.
     */
    static FileChannel openLockFile(Path file) throws IOException {
        Set<OpenOption> open = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileChannel channel;
        try {
            channel = FileChannel.open(file, open, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            channel = FileChannel.open(file, open);
        }
        var view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            var ownerOnly = EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            try {
                var current = view.readAttributes();
                if (!ownerOnly.containsAll(current.permissions())) view.setPermissions(ownerOnly);
            } catch (IOException | RuntimeException bestEffort) {
                // Never turn a lock that still works into a startup conflict over a permission change.
            }
        }
        return channel;
    }
    static void write(Path file, Properties p) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = createPrivateFile(temp);
                 OutputStream out = java.nio.channels.Channels.newOutputStream(channel)) {
                p.store(out, "VaultBackupPro offline restore state");
                out.flush(); channel.force(true);
            }
            try { Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(file.getParent());
        } finally { Files.deleteIfExists(temp); }
    }
    static void syncDirectory(Path dir) throws IOException {
        // Unix supports directory fsync. Windows rejects opening directories; the forced file
        // plus atomic rename still provides crash detection, never automatic crash recovery.
        try (FileChannel c = FileChannel.open(dir, StandardOpenOption.READ)) { c.force(true); }
        catch (AccessDeniedException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) throw e;
        }
    }
    static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing restore field: " + key);
        return value;
    }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sha(Path file) throws IOException {
        regular(file); MessageDigest d = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = new byte[128 * 1024]; int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }
    static String hmac(String secret, String value) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("Invalid receipt authentication", e); }
    }
    static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static void move(Path from, Path to) throws IOException {
        if (Files.exists(to, NOFOLLOW)) throw new IOException("Refusing to replace existing path: " + to);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); break; }
            catch (FileSystemException e) {
                // Windows refuses to rename a directory while any process (a console whose current
                // directory is inside it, an indexer, antivirus) has an open handle in it. A failed
                // atomic rename changed nothing, so a bounded retry is safe.
                if (!windows || e instanceof AtomicMoveNotSupportedException || e instanceof NoSuchFileException
                        || e instanceof FileAlreadyExistsException || System.nanoTime() >= deadline
                        || Files.exists(to, NOFOLLOW) || !Files.exists(from, NOFOLLOW)) {
                    if (windows && !(e instanceof AtomicMoveNotSupportedException))
                        throw new IOException("Could not rename " + from + " to " + to + ". Close every console, Explorer window and program"
                                + " whose current folder is inside the server folder, then run the helper again.", e);
                    throw e;
                }
                try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        syncDirectory(to.getParent());
    }
    static String randomSecret() {
        byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return HexFormat.of().formatHex(b);
    }
    static String guardName(Path root) {
        return HexFormat.of().formatHex(digest().digest(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ".server.lock";
    }
    static String message(Properties job,String english,String japanese) {
        return job.getProperty("language","en").toLowerCase(Locale.ROOT).startsWith("ja")?japanese:english;
    }
    static void status(Path jobDir, String status, String message) throws IOException {
        Path jobFile=jobDir.resolve("job.properties");
        if(Files.exists(jobFile,NOFOLLOW) && load(jobFile).getProperty("language","en").toLowerCase(Locale.ROOT).startsWith("ja")) {
            String translated=switch(status) {
                case "COMMITTED" -> "復元を予約しました。ヘルパーの起動を確認しています。";
                case "READY", "WAITING_FOR_MANUAL_STOP" -> "復元予約を受け付けました。期限なしで手動の正常停止と JVM 終了を待っています。自動停止・自動再起動は行いません。";
                case "CANCEL_PENDING" -> "取消を要求しました。確認が完了するまでサーバーを停止しないでください。";
                case "CANCELLED" -> "復元予約の取消を確認しました。このジョブは停止後も復元しません。";
                case "PARENT_EXITED" -> "停止処理への移行と対象 JVM の終了を確認しました。オフライン復元を開始します。";
                case "STAGING" -> "作業領域に展開して検証しています。元のサーバーデータは変更していません。";
                case "STAGED" -> "展開データの検証が完了しました。安全な置換を準備しています。";
                case "MOVING_ORIGINAL" -> "元のサーバーデータをロールバック用フォルダーに保存しています。";
                case "ORIGINAL_MOVED" -> "元のサーバーデータを保存しました。";
                case "INSTALLING" -> "検証済みデータをサーバーフォルダーに配置しています。";
                case "VERIFYING_INSTALLED" -> "配置済みの全ファイルを再検証しています。";
                case "SUCCESS" -> "オフライン復元と検証が完了しました。自動再起動は行いません。元データは保存されています。";
                case "ROLLING_BACK" -> "元のサーバーデータに戻しています。サーバーを起動しないでください。";
                case "FAILED_ROLLED_BACK" -> "復元に失敗したため、元のサーバーデータを復旧しました。詳細: "+message;
                case "FAILED_BEFORE_SWAP" -> "復元を中止しました。元のサーバーデータは移動していません。詳細: "+message;
                case "RECOVERY_REQUIRED" -> "復旧が必要です。サーバーを起動せず、保持されたデータを確認してください。詳細: "+message;
                case "RECOVERED" -> "元のサーバーデータの復旧を確認しました。自動再起動は行いません。";
                case "EXPIRED" -> "旧形式の復元予約は期限切れです。現在のデータは変更していません。";
                default -> message;
            };
            message=translated;
        }
        Properties p = new Properties(); p.setProperty("status", status); p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("message", message == null ? "" : message); write(jobDir.resolve("status.properties"), p);
    }
    static String status(Path jobDir) throws IOException {
        Path path = jobDir.resolve("status.properties");
        return required(load(path), "status");
    }
    static String receiptPayload(Properties p) throws IOException {
        return String.join("\n", required(p,"jobId"), required(p,"backupId"), required(p,"serverRoot"),
                required(p,"archiveSha256"), required(p,"manifestSha256"), required(p,"completedAt"), required(p,"rollbackDirectory"));
    }
    static String recoveryPayload(Properties p)throws IOException {
        return String.join("\n",required(p,"formatVersion"),required(p,"jobId"),required(p,"backupId"),
                required(p,"serverRoot"),required(p,"archiveSha256"),required(p,"manifestSha256"),
                required(p,"stage"),required(p,"rollback"),required(p,"failed"),
                required(p,"outcome"),required(p,"completedAt"));
    }
    /**
     * A PID that now belongs to a process started clearly after the recorded process (and after the
     * job existed) proves the recorded process is gone. Each JVM derives start instants from its
     * own cached boot time, so small differences are uncertainty, never proof. The lifetime root
     * guard and world session locks remain the independent, authoritative liveness checks.
     */
    static final Duration IDENTITY_TOLERANCE = Duration.ofMinutes(2);
    static boolean reusedAfter(Instant actual, Instant recordedStart, Properties job, boolean requireJobCreation) {
        Instant floor = recordedStart;
        String created = job.getProperty("createdAt");
        if (created == null) {
            if (requireJobCreation) return false;
        } else {
            try { Instant value = Instant.parse(created); if (value.isAfter(floor)) floor = value; }
            catch (RuntimeException unreadable) { if (requireJobCreation) return false; }
        }
        return actual.isAfter(floor.plus(IDENTITY_TOLERANCE));
    }
    /**
     * Linux ProcessHandle.of() also resolves thread IDs. A number that is only a thread of another
     * process names no process at all, so the recorded JVM (always a process) is gone.
     */
    static boolean threadOfAnotherProcess(long pid) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return false;
        try {
            for (String line : Files.readAllLines(Path.of("/proc", Long.toString(pid), "status"), java.nio.charset.StandardCharsets.ISO_8859_1))
                if (line.startsWith("Tgid:")) return Long.parseLong(line.substring(5).trim()) != pid;
        } catch (IOException | RuntimeException unknown) { /* Never proof of death. */ }
        return false;
    }
    /**
     * Linux reports an exited but not yet reaped process (a zombie) as alive. It has already closed
     * every file and released every lock, so it is not a running server JVM or helper.
     */
    static boolean exitedButUnreaped(long pid) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return false;
        try {
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"), java.nio.charset.StandardCharsets.ISO_8859_1);
            int end = stat.lastIndexOf(')');
            if (end < 0 || end + 2 >= stat.length()) return false;
            char state = stat.charAt(end + 2);
            return state == 'Z' || state == 'X';
        } catch (IOException | RuntimeException unknown) { return false; }
    }
    /** Strict check outside the root guard: a living process with the recorded identity, or none readable. */
    static void requireDeadParent(Properties job) throws IOException { requireDeadParent(job, null, false); }
    /**
     * observedStart: this JVM's own earlier observation of the living parent, when it has one.
     * guardHeld: the caller holds the server-root guard, which every living server JVM holds, so a
     * PID that now names a process started after this job existed proves the recorded JVM is gone.
     */
    static void requireDeadParent(Properties job, Instant observedStart, boolean guardHeld) throws IOException {
        long pid;
        Instant start;
        try { pid = Long.parseLong(required(job,"parentPid")); start = Instant.parse(required(job,"parentStartedAt")); }
        catch (RuntimeException e) { throw new IOException("Invalid recorded parent identity", e); }
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || threadOfAnotherProcess(pid) || exitedButUnreaped(pid)) return;
        Optional<Instant> actual = process.get().info().startInstant();
        if (actual.isEmpty()) throw new IOException("Parent PID identity is uncertain or reused. No restore is permitted.");
        boolean same = actual.get().equals(start) || actual.get().equals(observedStart);
        if (!same) {
            // This JVM watched the living parent itself: its own, exact observation now names
            // a different process, so the parent exited and its PID was reused.
            if (observedStart != null) return;
            if (guardHeld && reusedAfter(actual.get(), start, job, true)) return;
            throw new IOException("Parent PID identity is uncertain or reused. No restore is permitted.");
        }
        if (process.get().isAlive()) throw new IOException("Recorded server JVM is still alive. No restore is permitted.");
    }
    /** Before the root guard: refuse a living recorded JVM; a different identity is decided under the guard. */
    static void refuseLiveParent(Properties job) throws IOException {
        long pid;
        Instant start;
        try { pid = Long.parseLong(required(job,"parentPid")); start = Instant.parse(required(job,"parentStartedAt")); }
        catch (RuntimeException e) { throw new IOException("Invalid recorded parent identity", e); }
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || threadOfAnotherProcess(pid) || exitedButUnreaped(pid)) return;
        Optional<Instant> actual = process.get().info().startInstant();
        if (actual.isEmpty()) throw new IOException("Parent PID identity is uncertain or reused. No restore is permitted.");
        if (actual.get().equals(start) && process.get().isAlive()) throw new IOException("Recorded server JVM is still alive. No restore is permitted.");
    }
}
