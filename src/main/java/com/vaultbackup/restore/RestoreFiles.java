// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
        if (!real.equals(absolute)) throw new IOException("Symlink directory components are not permitted: " + absolute);
        return real;
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
                || !file.toAbsolutePath().normalize().equals(file.toRealPath()))
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
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
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
    /** Whether the process recorded as (PID, start instant) still runs. */
    enum Liveness { ALIVE, GONE, UNCERTAIN }
    /**
     * Two JVMs can compute start instants for one process that differ slightly: each derives them
     * from the boot time it sampled when it started, and that moves when the clock is adjusted.
     */
    private static final Duration START_TOLERANCE = Duration.ofSeconds(5);
    /**
     * A PID that now belongs to a process started at another time is a reused PID: the recorded
     * process is gone. That happens after every host reboot and must not block anything for good.
     * This is only a first look. Whether a server is really using the root is decided by its
     * lifetime root guard, which every caller that would touch the root takes as well.
     */
    static Liveness liveness(long pid, Instant recordedStart) {
        for (int sample = 0; sample < 3; sample++) {
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            if (process.isEmpty()) return Liveness.GONE;
            Optional<Instant> actual = process.get().info().startInstant();
            if (actual.isPresent()) {
                if (Duration.between(recordedStart, actual.get()).abs().compareTo(START_TOLERANCE) > 0) return Liveness.GONE;
                return process.get().isAlive() ? Liveness.ALIVE : Liveness.GONE;
            }
            // No start instant: the process may have exited between the two calls. Look again.
            if (!process.get().isAlive()) return Liveness.GONE;
            try { Thread.sleep(20); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Liveness.UNCERTAIN; }
        }
        return Liveness.UNCERTAIN;
    }
    static Liveness parentLiveness(Properties job) throws IOException {
        try { return liveness(Long.parseLong(required(job,"parentPid")), Instant.parse(required(job,"parentStartedAt"))); }
        catch (RuntimeException e) { throw new IOException("Invalid recorded parent identity", e); }
    }
    static void requireDeadParent(Properties job) throws IOException {
        switch (parentLiveness(job)) {
            case GONE -> { }
            case ALIVE -> throw new IOException("Recorded server JVM is still alive. No restore is permitted.");
            case UNCERTAIN -> throw new IOException("The recorded server JVM's process cannot be identified. No restore is permitted.");
        }
    }

    /**
     * One channel per lock file for the life of the JVM, never closed: on POSIX, closing any
     * descriptor of a file drops every lock the process holds on it, including a lock taken through
     * another channel by another plugin instance in the same JVM.
     */
    private static final Map<Path, FileChannel> LOCK_CHANNELS = new ConcurrentHashMap<>();
    static FileChannel lockChannel(Path file) throws IOException {
        FileChannel known = LOCK_CHANNELS.get(file);
        if (known != null && known.isOpen()) return known;
        FileChannel opened = openLockFile(file);
        FileChannel raced = LOCK_CHANNELS.putIfAbsent(file, opened);
        if (raced != null && raced.isOpen()) { retained(opened); return raced; }
        LOCK_CHANNELS.put(file, opened);
        return opened;
    }
    /** Lock files carry no data, but another local user who could open one could hold a lock on it. */
    static FileChannel openLockFile(Path file) throws IOException {
        Set<OpenOption> open = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            return FileChannel.open(file, open, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            return FileChannel.open(file, open);
        }
    }
    private static final List<FileChannel> RETAINED = Collections.synchronizedList(new ArrayList<>());
    /** Keeps a channel open until the JVM exits instead of closing it. */
    static void retained(FileChannel channel) { RETAINED.add(channel); }

    /** Identity of whatever is at the path, or an empty string where the filesystem has none (Windows). */
    static String fileIdentity(Path path) throws IOException {
        Object key = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW).fileKey();
        return key == null ? "" : key.toString();
    }
    private static final java.util.regex.Pattern DEVICE_AND_INODE = java.util.regex.Pattern.compile("\\(dev=([0-9a-fA-F]+),ino=([0-9]+)\\)");
    /**
     * Whether two identities, possibly taken before and after a reboot, name the same file: TRUE,
     * FALSE, or null when that cannot be said. Device numbers are handed out again at every boot and
     * may then differ for the same filesystem (LVM, btrfs, ZFS, NFS), so another device proves
     * nothing in either direction. Another inode number on the same device is another file.
     */
    static Boolean sameFile(String recorded, String actual) {
        if (recorded == null || actual == null || recorded.isEmpty() || actual.isEmpty()) return null;
        if (recorded.equals(actual)) return Boolean.TRUE;
        java.util.regex.Matcher a = DEVICE_AND_INODE.matcher(recorded), b = DEVICE_AND_INODE.matcher(actual);
        if (!a.matches() || !b.matches()) return null;
        boolean sameDevice = a.group(1).equalsIgnoreCase(b.group(1)), sameInode = a.group(2).equals(b.group(2));
        return sameDevice && !sameInode ? Boolean.FALSE : null;
    }
    /**
     * Whether any process holds a lock on the file right now: TRUE when none does (or the file does
     * not exist), FALSE when one does, null when this filesystem cannot tell. The operating system
     * releases a lock with the process that held it, so this is a better witness of a process being
     * gone than a process ID, which is reused.
     */
    static Boolean unlocked(Path file) {
        if (!Files.exists(file, NOFOLLOW)) return Boolean.TRUE;
        try (FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            FileLock lock = channel.tryLock();
            if (lock == null) return Boolean.FALSE;
            lock.release();
            return Boolean.TRUE;
        } catch (java.nio.channels.OverlappingFileLockException heldByThisJvm) {
            return Boolean.FALSE;
        } catch (IOException | UnsupportedOperationException unknown) {
            return null;
        }
    }

    /** Free space the filesystem of the directory must have beyond the bytes to be written there. */
    static final long SPACE_MARGIN_BYTES = 64L * 1024 * 1024;

    /** For messages: never the literal text "null". */
    static String reason(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** Removes a directory tree this program created itself. Links are removed, never followed. */
    static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, NOFOLLOW)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
