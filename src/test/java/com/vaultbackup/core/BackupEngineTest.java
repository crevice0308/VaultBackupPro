// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Disposable-fixture integration tests; no server, network, or external test framework. */
public final class BackupEngineTest {
    private static int checks;
    private static Path fixture;
    private static final String ZERO_HASH = "0".repeat(64);

    public static void main(String[] args) throws Exception {
        fixture = Files.createTempDirectory("vaultbackup-core-test-");
        try {
            roundTripAndExclusions();
            allowAndDenyPatterns();
            cancellationAndMutation();
            symlinksAndUnsafePaths();
            corruptionAndIncompleteArchives();
            retentionAndLastGood();
            maliciousArchivesAndLimits();
            storeLockIsFailFast();
            System.out.println("PASS BackupEngineTest (" + checks + " checks)");
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static BackupEngine engine(String name) throws IOException {
        Path base = Files.createDirectory(fixture.resolve(name));
        Path root = Files.createDirectory(base.resolve("server"));
        return new BackupEngine(BackupOptions.defaults(root, base.resolve("backups"), base.resolve("state")));
    }

    private static void roundTripAndExclusions() throws Exception {
        Path base = Files.createDirectory(fixture.resolve("roundtrip"));
        Path root = Files.createDirectory(base.resolve("server"));
        Path state = Files.createDirectories(root.resolve("state"));
        Files.writeString(state.resolve("secret-token"), "must never enter an archive");
        Files.createDirectories(root.resolve("world/empty"));
        Files.writeString(root.resolve("server.properties"), "motd=Vault test\n", StandardCharsets.UTF_8);
        byte[] region = new byte[16384]; new Random(19).nextBytes(region);
        Files.write(root.resolve("world/chunk.bin"), region);
        BackupEngine engine = new BackupEngine(BackupOptions.defaults(root, root.resolve("backups"), state));
        BackupRecord record = engine.createBackup("round trip", () -> false, ignored -> {});
        equal(record.totalBytes(), (long) region.length + Files.size(root.resolve("server.properties")), "total bytes");
        equal(engine.verify(record.id()).id(), record.id(), "verification returns id");
        equal(engine.listBackups().size(), 1, "completed backup listed");
        List<BackupEngine.ManifestEntry> manifest = BackupEngine.readManifest(record);
        check(manifest.stream().anyMatch(e -> e.path().equals("world/empty") && e.directory()), "empty directories preserved");
        check(manifest.stream().noneMatch(e -> e.path().startsWith("backups") || e.path().startsWith("state")), "backup and state recursively excluded");
        try (ZipFile zip = new ZipFile(record.zipPath().toFile())) {
            check(Arrays.equals(zip.getInputStream(zip.getEntry("world/chunk.bin")).readAllBytes(), region), "binary file preserved");
        }
        check(Files.exists(record.metadataPath()), "commit marker published");
        try (var files = Files.list(engine.backupDirectory())) {
            check(files.noneMatch(p -> p.toString().endsWith(".part")), "no part files after success");
        }
        expectIOException(() -> engine.delete(record.id(), Set.of()), "cannot delete last good");
    }

    private static void allowAndDenyPatterns() throws Exception {
        BackupEngine first = engine("patterns");
        Path root = first.serverRoot();
        Files.createDirectories(root.resolve("world/cache"));
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("root.dat"), "root");
        Files.writeString(root.resolve("world/level.dat"), "world");
        Files.writeString(root.resolve("world/cache/chunk.dat"), "cache");
        Files.writeString(root.resolve("logs/server.log"), "log");
        BackupOptions defaults = first.options();
        BackupEngine filtered = new BackupEngine(options(defaults, List.of("**/*.dat"), List.of("**/cache/**", "logs/**"), 100, 1024, 4096, 10000));
        BackupRecord record = filtered.createBackup("filtered");
        Set<String> names = new HashSet<>();
        for (var entry : BackupEngine.readManifest(record)) if (!entry.directory()) names.add(entry.path());
        equal(names, Set.of("root.dat", "world/level.dat"), "portable double-star allow/deny patterns");
    }

    private static void cancellationAndMutation() throws Exception {
        BackupEngine engine = engine("cancel");
        Files.writeString(engine.serverRoot().resolve("live.dat"), "old content");
        expectIOException(() -> engine.createBackup("cancel", () -> true, ignored -> {}), "early cancellation");
        equal(engine.listBackups().size(), 0, "cancel publishes no archive");
        AtomicBoolean modified = new AtomicBoolean();
        expectIOException(() -> engine.createBackup("changing", () -> false, message -> {
            if (message.equals("Backing up live.dat") && modified.compareAndSet(false, true)) {
                try {
                    Files.writeString(engine.serverRoot().resolve("live.dat"), "new content much larger");
                    Files.setLastModifiedTime(engine.serverRoot().resolve("live.dat"), FileTime.from(Instant.now().plusSeconds(5)));
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }), "source mutation rejected");
        equal(engine.listBackups().size(), 0, "mutation publishes no archive");
        try (var files = Files.list(engine.backupDirectory())) {
            check(files.noneMatch(p -> p.toString().endsWith(".part") || p.toString().endsWith(".zip")), "failed archive cleaned");
        }
        AtomicBoolean cancel = new AtomicBoolean();
        expectIOException(() -> engine.createBackup("midcancel", cancel::get, message -> {
            if (message.startsWith("Backing up ")) cancel.set(true);
        }), "mid-copy cancellation");
        equal(engine.listBackups().size(), 0, "mid-copy cancellation publishes no archive");
    }

    private static void symlinksAndUnsafePaths() throws Exception {
        BackupEngine engine = engine("links");
        Path outside = Files.writeString(fixture.resolve("outside.txt"), "private");
        Files.createSymbolicLink(engine.serverRoot().resolve("link"), outside);
        expectIOException(() -> engine.createBackup("link"), "symlink file rejected");
        Files.delete(engine.serverRoot().resolve("link"));
        Files.createSymbolicLink(engine.serverRoot().resolve("linked-directory"), outside.getParent());
        expectIOException(() -> engine.createBackup("linkdir"), "symlink directory rejected");
        for (String unsafe : List.of("../escape", "/absolute", "a/../b", "C:/evil", "a\\b", "a//b", "./a", "NUL.txt", "a.", "a ", "x:y", "a/", "\nname")) {
            expectIOException(() -> BackupEngine.validateEntryPath(unsafe), "unsafe path " + unsafe.replace("\n", "<newline>"));
        }
        expectIOException(() -> engine.verify("../fake"), "unsafe backup id");
        BackupEngine.validateEntryPath("world/日本語.dat"); checks++;
        Path linkedStore = fixture.resolve("linked-store");
        Files.createSymbolicLink(linkedStore, engine.backupDirectory());
        expectIOException(() -> new BackupEngine(BackupOptions.defaults(engine.serverRoot(), linkedStore, fixture.resolve("link-state"))), "symlink destination rejected");
    }

    private static void corruptionAndIncompleteArchives() throws Exception {
        BackupEngine engine = engine("corrupt");
        Files.writeString(engine.serverRoot().resolve("x"), "content");
        BackupRecord record = engine.createBackup("good");
        Files.write(engine.backupDirectory().resolve("20260101T000000000Z-aaaaaaaaaaaa.zip"), new byte[]{1,2,3});
        Files.writeString(engine.backupDirectory().resolve(".unfinished.properties.part"), "junk");
        equal(engine.listBackups().size(), 1, "orphan and temporary archive ignored");
        byte[] original = Files.readAllBytes(record.zipPath());
        byte[] corrupt = original.clone(); corrupt[corrupt.length / 2] ^= 1;
        Files.write(record.zipPath(), corrupt);
        expectIOException(() -> engine.verify(record.id()), "whole zip checksum detects tampering");
        equal(engine.listBackups().size(), 0, "corrupt backup not listed as good");
        Files.write(record.zipPath(), original);
        Files.writeString(record.metadataPath(), "formatVersion=1\nid=\\uZZZZ\n");
        equal(engine.listBackups().size(), 0, "malformed sidecar ignored");
    }

    private static void retentionAndLastGood() throws Exception {
        BackupEngine engine = engine("retention");
        Files.writeString(engine.serverRoot().resolve("x"), "first");
        BackupRecord first = engine.createBackup("first");
        Thread.sleep(2);
        Files.writeString(engine.serverRoot().resolve("x"), "second");
        BackupRecord second = engine.createBackup("second");
        Thread.sleep(2);
        BackupRecord third = engine.createBackup("third");
        expectIOException(() -> engine.delete(first.id(), Set.of(first.id())), "reserved target cannot be deleted");
        List<BackupRecord> removed = engine.retain(1, 1, Set.of(first.id(), third.id()), () -> false, ignored -> {});
        equal(removed.stream().map(BackupRecord::id).toList(), List.of(second.id()), "retention skips reserved targets");
        equal(engine.listBackups().size(), 2, "retention preserves all reserved targets");
        engine.retain(1, 1, Set.of(), () -> false, ignored -> {});
        equal(engine.listBackups().size(), 1, "retention preserves last good over byte budget");
        expectIOException(() -> engine.delete(engine.listBackups().getFirst().id(), Set.of()), "last good survives explicit delete");
    }

    private static void maliciousArchivesAndLimits() throws Exception {
        BackupEngine engine = engine("malicious");
        String id = "20260101T000000000Z-0123456789ab";
        Path zip = engine.backupDirectory().resolve(id + ".zip");
        Path metadata = engine.backupDirectory().resolve(id + ".properties");
        crafted(zip, metadata, List.of(new Part("../escape", new byte[]{1})), null, 1, 1);
        expectIOException(() -> engine.verify(id), "traversal archive rejected");
        crafted(zip, metadata, List.of(new Part("a", new byte[]{1})), null, 1, 1);
        expectIOException(() -> engine.verify(id), "missing manifest rejected");
        String wrongHash = line("F", "a", 1, ZERO_HASH);
        crafted(zip, metadata, List.of(new Part("a", new byte[]{1})), wrongHash, 1, 1);
        expectIOException(() -> engine.verify(id), "per-entry SHA detects forged manifest");
        byte[] one = new byte[]{1};
        String aliasManifest = line("F", "File", 1, hash(one)) + line("F", "file", 1, hash(one));
        crafted(zip, metadata, List.of(new Part("File", one), new Part("file", one)), aliasManifest, 2, 2);
        expectIOException(() -> engine.verify(id), "case alias archive rejected");
        String conflict = line("F", "a", 1, hash(one)) + line("F", "a/b", 1, hash(one));
        crafted(zip, metadata, List.of(new Part("a", one), new Part("a/b", one)), conflict, 2, 2);
        expectIOException(() -> engine.verify(id), "file ancestor conflict rejected");
        String implicitAlias = line("F", "Dir/a", 1, hash(one)) + line("F", "dir/b", 1, hash(one));
        crafted(zip, metadata, List.of(new Part("Dir/a", one), new Part("dir/b", one)), implicitAlias, 2, 2);
        expectIOException(() -> engine.verify(id), "implicit ancestor case alias rejected");
        String aliasConflict = line("F", "A", 1, hash(one)) + line("F", "a/b", 1, hash(one));
        crafted(zip, metadata, List.of(new Part("A", one), new Part("a/b", one)), aliasConflict, 2, 2);
        expectIOException(() -> engine.verify(id), "case alias file ancestor conflict rejected");
        String mismatched = line("F", "a", 2, hash(one));
        crafted(zip, metadata, List.of(new Part("a", one)), mismatched, 1, 1);
        expectIOException(() -> engine.verify(id), "manifest size mismatch rejected");
        crafted(zip, metadata, List.of(new Part("a/", one)), line("D", "a", 0, "-"), 0, 1);
        expectIOException(() -> engine.verify(id), "directory data rejected");
        crafted(zip, metadata, List.of(new Part("a", one)), "x".repeat(9000) + "\n", 1, 1);
        expectIOException(() -> engine.verify(id), "overlong manifest line rejected");
        crafted(zip, metadata, List.of(new Part("a", one)), line("F", "a", 1, hash(one)).stripTrailing(), 1, 1);
        expectIOException(() -> engine.verify(id), "truncated manifest rejected");
        byte[] sixtyFour = new byte[64];
        String two = line("F", "a", 64, hash(sixtyFour)) + line("F", "b", 64, hash(sixtyFour));
        crafted(zip, metadata, List.of(new Part("a", sixtyFour), new Part("b", sixtyFour)), two, 128, 2);
        BackupOptions totalLimit = options(engine.options(), List.of(), List.of(), 10, 80, 100, 10000);
        expectIOException(() -> BackupEngine.verifyArchive(zip, metadata, totalLimit, () -> false, ignored -> {}), "expanded total size bound");
        BackupOptions countLimit = options(engine.options(), List.of(), List.of(), 1, 1024, 1024, 10000);
        expectIOException(() -> BackupEngine.verifyArchive(zip, metadata, countLimit, () -> false, ignored -> {}), "entry count bound");
        byte[] compressed = new byte[65536];
        crafted(zip, metadata, List.of(new Part("bomb", compressed)), line("F", "bomb", compressed.length, hash(compressed)), compressed.length, 1);
        BackupOptions strict = options(engine.options(), List.of(), List.of(), 100, 100000, 1000000, 2);
        expectIOException(() -> BackupEngine.verifyArchive(zip, metadata, strict, () -> false, ignored -> {}), "compression ratio bound");
        BackupOptions tiny = options(engine.options(), List.of(), List.of(), 100, 100, 1000, 10000);
        expectIOException(() -> BackupEngine.verifyArchive(zip, metadata, tiny, () -> false, ignored -> {}), "expanded entry size bound");
        Files.writeString(engine.serverRoot().resolve(".vaultbackup-manifest"), "collision");
        expectIOException(() -> engine.createBackup("collision"), "reserved source manifest rejected");
    }

    private static void storeLockIsFailFast() throws Exception {
        BackupEngine engine = engine("locked");
        Files.writeString(engine.serverRoot().resolve("x"), "x");
        try (FileChannel channel = FileChannel.open(engine.backupDirectory().resolve(".vaultbackup.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            expectIOException(() -> engine.createBackup("busy"), "busy store returns a checked failure");
        }
        equal(engine.listBackups().size(), 0, "busy store publishes nothing");
    }

    private record Part(String name, byte[] data) { }
    private static void crafted(Path zip, Path metadata, List<Part> parts, String manifest, long bytes, int count) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Part part : parts) {
                output.putNextEntry(new ZipEntry(part.name())); output.write(part.data()); output.closeEntry();
            }
            if (manifest != null) {
                output.putNextEntry(new ZipEntry(BackupEngine.MANIFEST_PATH));
                output.write((BackupEngine.MANIFEST_HEADER + "\n" + manifest).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        Properties properties = new Properties();
        properties.setProperty("formatVersion", "1");
        properties.setProperty("id", zip.getFileName().toString().replace(".zip", ""));
        properties.setProperty("createdAt", Instant.now().toString());
        properties.setProperty("label", "crafted");
        properties.setProperty("archiveBytes", Long.toString(Files.size(zip)));
        properties.setProperty("totalBytes", Long.toString(bytes));
        properties.setProperty("entryCount", Integer.toString(count));
        properties.setProperty("sha256", hash(Files.readAllBytes(zip)));
        try (OutputStream output = Files.newOutputStream(metadata)) { properties.store(output, "test fixture"); }
    }
    private static String line(String type, String path, long size, String hash) {
        return type + "\t" + size + "\t" + hash + "\t" + Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8)) + "\n";
    }
    private static String hash(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static BackupOptions options(BackupOptions source, List<String> includes, List<String> excludes,
                                         int entries, long entry, long total, double ratio) {
        return new BackupOptions(source.serverRoot(), source.backupDirectory(), source.stateDirectory(), includes, excludes, entries, entry, total, ratio, 6);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object actual, Object expected, String message) { check(Objects.equals(actual, expected), message + ": expected " + expected + ", got " + actual); }
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    private static void expectIOException(ThrowingRunnable action, String message) throws Exception {
        checks++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException: " + message);
    }
}
