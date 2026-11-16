// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.*;

/** Disposable-fixture integration tests; no server, network, or external test framework. */
public final class BackupEngineTest {
    private static int checks;
    private static Path fixture;
    private static final String ZERO_HASH = "0".repeat(64);

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--locale-child")) { localeChild(Path.of(args[1])); return; }
        if (args.length == 3 && args[0].equals("--memory-child")) { memoryChild(Path.of(args[1]), Path.of(args[2])); return; }
        if (args.length == 2 && args[0].equals("--lock-child")) { lockChild(Path.of(args[1])); return; }
        fixture = Files.createTempDirectory("vaultbackup-core-test-");
        try {
            roundTripAndExclusions();
            allowAndDenyPatterns();
            cancellationAndMutation();
            symlinksAndUnsafePaths();
            corruptionAndIncompleteArchives();
            retentionWithoutFloor();
            manualDeletionWithoutFloor();
            deletionAndRetentionRejectSymlinkTargets();
            concurrentStoreMutationIsRefused();
            maliciousArchivesAndLimits();
            storeLockIsFailFast();
            privateFilesAndAbandonedPartCleanup();
            verificationIsNotRepeatedForUnchangedArchives();
            liveTreeChangesAreTolerated();
            unsafeChangesDuringCopyAreRefused();
            everyProblemIsReportedAtOnce();
            selectionRules();
            interruptedChangesAreSwept();
            rejectedArchivesAreVisibleAndDeletable();
            craftedArchivesStayWithinMemory();
            spaceIsCheckedBeforeAndWhileWriting();
            fileNamesNeedARepresentableEncoding();
            readsCannotBeRedirectedOutsideTheRoot();
            recentTimestampsProveNothing();
            vanishedFilesAreCountedAndAnEmptyResultIsRefused();
            sessionLockIsNeverOpened();
            unreadableFilesAreFoundBeforeCopying();
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
        engine.delete(record.id(), Set.of());
        equal(engine.listBackups().size(), 0, "ordinary deletion may remove the final recovery point");
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
        try (var files = Files.list(engine.backupDirectory())) {
            check(files.noneMatch(p -> p.toString().endsWith(".part") || p.toString().endsWith(".zip")), "cancelled archive cleaned");
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

    private static void retentionWithoutFloor() throws Exception {
        BackupEngine engine = engine("retention");
        Files.writeString(engine.serverRoot().resolve("x"), "first");
        BackupRecord first = engine.createBackup("first");
        Thread.sleep(2);
        Files.writeString(engine.serverRoot().resolve("x"), "second");
        BackupRecord second = engine.createBackup("second");
        Thread.sleep(2);
        BackupRecord third = engine.createBackup("third");
        expectRefusal(() -> engine.delete(first.id(), Set.of(first.id())),
                BackupDeletionRefusedException.Reason.RESTORE_PROTECTED, first.id());
        List<BackupRecord> removed = engine.retain(1, 1, Set.of(first.id(), third.id()), () -> false, ignored -> {});
        equal(removed.stream().map(BackupRecord::id).toList(), List.of(second.id()), "retention skips reserved targets");
        equal(engine.listBackups().size(), 2, "retention preserves all reserved targets over both limits");
        removed = engine.retain(1, Long.MAX_VALUE, Set.of(), () -> false, ignored -> {});
        equal(removed.stream().map(BackupRecord::id).toList(), List.of(first.id()), "count retention removes oldest first");
        equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(third.id()), "count retention meets positive limit");
        removed = engine.retain(1, 1, Set.of(), () -> false, ignored -> {});
        equal(removed.stream().map(BackupRecord::id).toList(), List.of(third.id()), "byte retention removes final good archive");
        equal(engine.listBackups().size(), 0, "impossible positive byte budget leaves zero verified archives");
        check(!Files.exists(third.zipPath()) && !Files.exists(third.metadataPath()), "retention removes final archive and sidecar");
        equal(engine.retain(1, 1, Set.of(), () -> false, ignored -> {}).size(), 0, "retention of empty store removes nothing");
        BackupRecord recreated = engine.createBackup("after-retention-zero");
        equal(engine.verify(recreated.id()).id(), recreated.id(), "backup creation works after zero retention");
        equal(engine.retain(Integer.MAX_VALUE, Long.MAX_VALUE, Set.of(), () -> false, ignored -> {}).size(), 0,
                "unlimited settings mapped by plugin preserve archives");

        BackupEngine all = engine("retention-all");
        Files.writeString(all.serverRoot().resolve("x"), "data");
        BackupRecord oldest = all.createBackup("oldest");
        Thread.sleep(2);
        BackupRecord newest = all.createBackup("newest");
        equal(all.retain(1, 1, Set.of(), () -> false, ignored -> {}).stream().map(BackupRecord::id).toList(),
                List.of(oldest.id(), newest.id()), "one retention pass can remove every archive oldest first");
        equal(all.listBackups().size(), 0, "all-pruned store truthfully reports zero");
        expectIOException(() -> all.retain(-1, 1, Set.of(), () -> false, ignored -> {}), "negative count limit rejected");
        expectIOException(() -> all.retain(1, -1, Set.of(), () -> false, ignored -> {}), "negative byte limit rejected");
    }

    private static void manualDeletionWithoutFloor() throws Exception {
        BackupEngine engine = engine("manual-no-floor");
        Files.writeString(engine.serverRoot().resolve("x"), "first");
        BackupRecord first = engine.createBackup("first");
        BackupRecord second = engine.createBackup("second");
        engine.delete(first.id(), Set.of());
        check(!Files.exists(first.zipPath()) && !Files.exists(first.metadataPath()), "ordinary deletion removes archive and sidecar");
        equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(second.id()), "ordinary delete preserves other backup");
        byte[] archive = Files.readAllBytes(second.zipPath());
        byte[] metadata = Files.readAllBytes(second.metadataPath());
        expectRefusal(() -> engine.delete(second.id(), Set.of(second.id())),
                BackupDeletionRefusedException.Reason.RESTORE_PROTECTED, second.id());
        check(Arrays.equals(archive, Files.readAllBytes(second.zipPath()))
                && Arrays.equals(metadata, Files.readAllBytes(second.metadataPath())), "reserved final backup stays byte-identical");
        engine.delete(second.id(), Set.of());
        equal(engine.listBackups().size(), 0, "ordinary manual deletion may leave zero without a special flag");
        check(!Files.exists(second.zipPath()) && !Files.exists(second.metadataPath()), "final deletion removes archive and sidecar");
        try { engine.delete(second.id(), Set.of()); throw new AssertionError("missing deletion accepted"); }
        catch (IOException expected) { check(!(expected instanceof BackupDeletionRefusedException), "missing backup remains IO diagnostic"); }
        BackupRecord recreated = engine.createBackup("after-zero");
        equal(engine.verify(recreated.id()).id(), recreated.id(), "backup creation works after deleting final generation");

        BackupEngine corruptEngine = engine("manual-corrupt-sibling");
        Files.writeString(corruptEngine.serverRoot().resolve("x"), "data");
        BackupRecord good = corruptEngine.createBackup("good");
        BackupRecord corrupt = corruptEngine.createBackup("will-corrupt");
        Files.write(corrupt.zipPath(), new byte[]{1,2,3});
        corruptEngine.delete(good.id(), Set.of());
        equal(corruptEngine.listBackups().size(), 0, "manual deletion ignores corrupt siblings and may leave zero");
        check(Files.exists(corrupt.zipPath()) && Files.exists(corrupt.metadataPath()), "manual delete does not remove other corrupt files");
        good = corruptEngine.createBackup("good-again");
        equal(corruptEngine.retain(1, 1, Set.of(), () -> false, ignored -> {}).stream().map(BackupRecord::id).toList(),
                List.of(good.id()), "retention removes final valid target with corrupt sibling present");
        equal(corruptEngine.listBackups().size(), 0, "corrupt sibling does not fabricate a recovery point");
        check(Files.exists(corrupt.zipPath()) && Files.exists(corrupt.metadataPath()), "retention leaves corrupt files for diagnosis");
    }

    private static void deletionAndRetentionRejectSymlinkTargets() throws Exception {
        BackupEngine engine = engine("delete-links");
        Files.writeString(engine.serverRoot().resolve("x"), "safe fixture");
        BackupRecord record = engine.createBackup("linked-archive");
        Path outside = Files.write(fixture.resolve("outside-archive.zip"), Files.readAllBytes(record.zipPath()));
        byte[] untouched = Files.readAllBytes(outside);
        Files.delete(record.zipPath());
        Files.createSymbolicLink(record.zipPath(), outside);
        expectIOException(() -> engine.delete(record.id(), Set.of()), "manual deletion rejects symlink archive");
        equal(engine.retain(1, 1, Set.of(), () -> false, ignored -> {}).size(), 0, "retention ignores symlink archive");
        check(Files.isSymbolicLink(record.zipPath()) && Files.exists(record.metadataPath()), "unsafe record is not removed");
        check(Arrays.equals(untouched, Files.readAllBytes(outside)), "deletion and retention leave symlink destination untouched");
        Files.delete(record.zipPath());
        Files.write(record.zipPath(), untouched);
        Path outsideMetadata = Files.write(fixture.resolve("outside-metadata.properties"), Files.readAllBytes(record.metadataPath()));
        byte[] untouchedMetadata = Files.readAllBytes(outsideMetadata);
        Files.delete(record.metadataPath());
        Files.createSymbolicLink(record.metadataPath(), outsideMetadata);
        expectIOException(() -> engine.delete(record.id(), Set.of()), "manual deletion rejects symlink metadata");
        equal(engine.retain(1, 1, Set.of(), () -> false, ignored -> {}).size(), 0, "retention ignores symlink metadata");
        check(Files.isSymbolicLink(record.metadataPath()) && Files.exists(record.zipPath()), "symlink sidecar record is not removed");
        check(Arrays.equals(untouchedMetadata, Files.readAllBytes(outsideMetadata)), "symlink metadata destination stays untouched");
    }

    private static void concurrentStoreMutationIsRefused() throws Exception {
        BackupEngine creating = engine("concurrent-mutation");
        Files.writeString(creating.serverRoot().resolve("x"), "first");
        BackupRecord first = creating.createBackup("first");
        BackupEngine competing = new BackupEngine(creating.options());
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<BackupRecord> created = worker.submit(() -> creating.createBackup("concurrent", () -> false, message -> {
                if (message.equals("Scanning server files")) {
                    lockHeld.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }));
            try {
                check(lockHeld.await(10, TimeUnit.SECONDS), "concurrent creator holds store lock");
                expectIOException(() -> competing.delete(first.id(), Set.of()), "manual delete refuses concurrent creator");
                expectIOException(() -> competing.retain(1, 1, Set.of(), () -> false, ignored -> {}), "retention refuses concurrent creator");
                equal(competing.verify(first.id()).id(), first.id(), "lock contention leaves existing recovery point valid");
            } finally { release.countDown(); }
            BackupRecord second = created.get(10, TimeUnit.SECONDS);
            equal(competing.listBackups().size(), 2, "concurrent creation publishes without mutation");
            competing.delete(first.id(), Set.of());
            // The old catalogue may have had two entries; final deletion needs no extra mode.
            competing.delete(second.id(), Set.of());
            equal(competing.listBackups().size(), 0, "sequential deletes revalidate current store and may leave zero");
        }
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
        BackupRecord existing = engine.createBackup("existing");
        try (FileChannel channel = FileChannel.open(engine.backupDirectory().resolve(".vaultbackup.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            expectIOException(() -> engine.createBackup("busy"), "busy store returns a checked failure");
            expectIOException(() -> engine.delete(existing.id(), Set.of()), "busy store refuses deletion");
            expectIOException(() -> engine.retain(1, 1, Set.of(), () -> false, progress -> {}), "busy store refuses retention");
        }
        equal(engine.listBackups().size(), 1, "busy store preserves existing archive and publishes nothing");
    }

    private static void privateFilesAndAbandonedPartCleanup() throws Exception {
        Path base = Files.createDirectory(fixture.resolve("hardening"));
        Path root = Files.createDirectory(base.resolve("server"));
        Files.writeString(root.resolve("server.properties"), "rcon.password=fixture-secret\n");
        // An administrator-created, ordinary shared directory: archives must not inherit its readability.
        Path backups = Files.createDirectory(base.resolve("backups"));
        boolean posix = Files.getFileStore(backups).supportsFileAttributeView(PosixFileAttributeView.class);
        if (posix) Files.setPosixFilePermissions(backups, PosixFilePermissions.fromString("rwxr-xr-x"));
        BackupEngine engine = new BackupEngine(BackupOptions.defaults(root, backups, base.resolve("state")));
        String abandonedId = "20260101T000000000Z-0123456789ab";
        Path abandonedZip = Files.write(backups.resolve("." + abandonedId + ".zip.part"), new byte[65536]);
        Path abandonedMetadata = Files.writeString(backups.resolve("." + abandonedId + ".properties.part"), "partial");
        Path foreign = Files.writeString(backups.resolve(".operator-notes.part"), "not created by VaultBackup");
        Path outside = Files.writeString(base.resolve("outside-target"), "must survive");
        Path linkedPart = backups.resolve(".20260101T000000001Z-0123456789ab.zip.part");
        boolean links = true;
        try { Files.createSymbolicLink(linkedPart, outside); } catch (UnsupportedOperationException | IOException unavailable) { links = false; }
        BackupRecord record = engine.createBackup("hardened");
        check(!Files.exists(abandonedZip, LinkOption.NOFOLLOW_LINKS) && !Files.exists(abandonedMetadata, LinkOption.NOFOLLOW_LINKS),
                "abandoned temporary archive files are removed under the store lock");
        check(Files.exists(foreign), "part files with other names are never removed");
        if (links) {
            check(Files.isSymbolicLink(linkedPart) && Files.readString(outside).equals("must survive"), "part-named symlinks are neither followed nor removed");
            Files.delete(linkedPart);
        }
        equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(record.id()), "cleanup leaves the new archive valid");
        if (posix) {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            equal(Files.getPosixFilePermissions(record.zipPath()), ownerOnly, "archive is owner-only even in a shared directory");
            equal(Files.getPosixFilePermissions(record.metadataPath()), ownerOnly, "sidecar is owner-only even in a shared directory");
        }
    }

    private static void verificationIsNotRepeatedForUnchangedArchives() throws Exception {
        BackupEngine engine = engine("verification-cache");
        Files.writeString(engine.serverRoot().resolve("a.dat"), "first file");
        Files.writeString(engine.serverRoot().resolve("b.dat"), "second file");
        BackupRecord first = engine.createBackup("first");
        BackupRecord second = engine.createBackup("second");
        AtomicInteger reads = new AtomicInteger();
        Consumer<String> counter = message -> { if (message.startsWith("Verifying ")) reads.incrementAndGet(); };
        // Each archive has exactly two manifest entries, so a full read reports two "Verifying" steps.
        equal(engine.listBackups(() -> false, counter).size(), 2, "the creating engine lists both archives");
        equal(reads.getAndSet(0), 0, "an archive verified while it was created is not read again by its engine");
        BackupEngine restarted = new BackupEngine(engine.options());
        equal(restarted.listBackups(() -> false, counter).size(), 2, "first listing of another engine returns both archives");
        equal(reads.getAndSet(0), 4, "archives not yet verified by an engine are read in full");
        BackupRecord extra = engine.createBackup("extra");
        new BackupEngine(engine.options()).delete(extra.id(), Set.of());
        equal(engine.listBackups(() -> false, counter).size(), 2, "a deletion by another engine is seen");
        equal(reads.getAndSet(0), 0, "and needs no archive to be read again");
        equal(engine.listBackups(() -> false, counter).size(), 2, "routine listing keeps unchanged archives");
        equal(reads.getAndSet(0), 0, "routine listing does not re-read unchanged archives");
        equal(engine.retain(10, Long.MAX_VALUE, Set.of(), () -> false, counter).size(), 0, "retention within limits removes nothing");
        equal(reads.getAndSet(0), 0, "post-backup retention does not re-read unchanged archives");
        equal(engine.revalidateBackups(() -> false, counter).size(), 2, "explicit revalidation returns both archives");
        equal(reads.getAndSet(0), 4, "explicit revalidation re-reads every archive");
        engine.verify(first.id(), () -> false, counter);
        equal(reads.getAndSet(0), 2, "explicit verify always re-reads its archive");

        byte[] original = Files.readAllBytes(first.zipPath());
        FileTime originalTime = Files.getLastModifiedTime(first.zipPath());
        byte[] corrupt = original.clone(); corrupt[corrupt.length / 2] ^= 1;
        Files.write(first.zipPath(), corrupt);
        Files.setLastModifiedTime(first.zipPath(), FileTime.fromMillis(originalTime.toMillis() + 5_000));
        equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(second.id()), "modified archive is re-verified and dropped");
        Files.write(first.zipPath(), original);
        Files.setLastModifiedTime(first.zipPath(), originalTime);
        equal(engine.listBackups().size(), 2, "restored archive verifies again");
        // A different file with identical size and timestamps is still a different file.
        Path swap = engine.backupDirectory().resolve("swap.tmp");
        Files.write(swap, corrupt);
        Files.setLastModifiedTime(swap, originalTime);
        Files.move(swap, first.zipPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (Files.readAttributes(first.zipPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey() != null) {
            equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(second.id()),
                    "replacement with identical size and timestamps is re-verified and dropped");
        }
        // Corruption that keeps identity, size and timestamps is caught by explicit revalidation,
        // and the failed result is not hidden by later routine listings.
        Files.write(first.zipPath(), original);
        Files.setLastModifiedTime(first.zipPath(), originalTime);
        equal(engine.listBackups().size(), 2, "genuine bytes verify again");
        try (FileChannel channel = FileChannel.open(first.zipPath(), StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{corrupt[corrupt.length / 2]}), corrupt.length / 2);
        }
        Files.setLastModifiedTime(first.zipPath(), originalTime);
        equal(engine.revalidateBackups(() -> false, ignored -> {}).stream().map(BackupRecord::id).toList(), List.of(second.id()),
                "explicit revalidation catches in-place corruption");
        equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(second.id()), "failed revalidation is never served from cache");
        expectIOException(() -> engine.verify(first.id()), "explicit verify rejects the corrupt archive");
        engine.delete(second.id(), Set.of());
        equal(engine.listBackups().size(), 0, "deleted archive is not served from cache");
    }

    private static Map<String, byte[]> archived(BackupRecord record) throws IOException {
        Map<String, byte[]> contents = new TreeMap<>();
        try (ZipFile zip = new ZipFile(record.zipPath().toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : Collections.list(zip.entries()))
                if (!entry.isDirectory() && !entry.getName().equals(BackupEngine.MANIFEST_PATH)) contents.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
        }
        return contents;
    }
    private static String text(Map<String, byte[]> contents, String name) {
        return contents.containsKey(name) ? new String(contents.get(name), StandardCharsets.UTF_8) : null;
    }
    private static void replaceByRename(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(temporary, content);
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void rewriteInPlace(Path file, String content) {
        try {
            Files.writeString(file, content);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(1 + (long) (Math.random() * 1000))));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * What a running server does to its own tree during a backup: player data and level.dat are
     * replaced by rename, plugins rewrite and create files, temporary files vanish. Before 1.3.0
     * each of these aborted the whole backup.
     */
    private static void liveTreeChangesAreTolerated() throws Exception {
        BackupEngine engine = engine("live");
        Path root = engine.serverRoot();
        Files.createDirectories(root.resolve("world/playerdata"));
        Files.createDirectories(root.resolve("plugins/Foo"));
        Files.writeString(root.resolve("a-first.txt"), "first");
        Files.writeString(root.resolve("world/level.dat"), "level-v1");
        Files.writeString(root.resolve("world/playerdata/p1.dat"), "p1-v1");
        Files.writeString(root.resolve("plugins/Foo/config.yml"), "config-v1");
        Files.writeString(root.resolve("plugins/Foo/temp.tmp"), "temporary");
        Files.writeString(root.resolve("usercache.json"), "[v1]");
        Files.writeString(root.resolve("zz-busy.db"), "busy-0");
        Set<String> done = new HashSet<>();
        AtomicInteger busyLooks = new AtomicInteger();
        engine.afterReadProbe = name -> {
            // A plugin rewrites its file exactly while it is being read: the first look is unstable.
            if (name.equals("plugins/Foo/config.yml") && done.add("config")) rewriteInPlace(root.resolve(name), "config-v2-longer");
            // A database another plugin never stops writing: every look is unstable.
            if (name.equals("zz-busy.db")) rewriteInPlace(root.resolve(name), "busy-" + busyLooks.incrementAndGet());
        };
        List<String> unstable = new ArrayList<>();
        BackupRecord record = engine.createBackup("live", () -> false, message -> {
            if (!message.equals("Backing up a-first.txt") || !done.add("server")) return;
            try {
                // The server's periodic save, a player joining and a plugin cleaning up, all after the scan.
                replaceByRename(root.resolve("world/level.dat"), "level-v2");
                replaceByRename(root.resolve("world/playerdata/p1.dat"), "p1-v2");
                Files.writeString(root.resolve("world/playerdata/p2.dat"), "joined later");
                replaceByRename(root.resolve("usercache.json"), "[v2]");
                Files.writeString(root.resolve("created-in-root.txt"), "new");
                Files.delete(root.resolve("plugins/Foo/temp.tmp"));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }, unstable::add);
        engine.afterReadProbe = null;
        Map<String, byte[]> contents = archived(record);
        equal(text(contents, "world/level.dat"), "level-v2", "a file replaced by rename is archived as one complete version");
        equal(text(contents, "world/playerdata/p1.dat"), "p1-v2", "replaced player data is archived");
        equal(text(contents, "usercache.json"), "[v2]", "replaced root file is archived");
        equal(text(contents, "plugins/Foo/config.yml"), "config-v2-longer", "a file rewritten during its copy is read again until it is one version");
        check(!contents.containsKey("plugins/Foo/temp.tmp"), "a file deleted after the scan is skipped, not an error");
        check(!contents.containsKey("world/playerdata/p2.dat") && !contents.containsKey("created-in-root.txt"), "files created after the scan belong to the next backup");
        equal(unstable, List.of("zz-busy.db"), "only the file that never settled is reported");
        check(busyLooks.get() >= 2 && text(contents, "zz-busy.db").startsWith("busy-"), "a never-settling file is retried, then archived as last read");
        equal(record.unstableEntries(), 1, "the count of unsettled files is returned");
        equal(engine.verify(record.id()).unstableEntries(), 1, "and stored with the backup");
        equal(new BackupEngine(engine.options()).listBackups().get(0).unstableEntries(), 1, "and read back by another engine");
        equal(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(record.zipPath()))), record.sha256(),
                "the digest taken while writing is the digest of the file on disk");
        equal(BackupEngine.readManifest(record).size(), record.entryCount(), "manifest lists exactly what was archived");

        // Files too large to read twice: rewritten in place is reported, replaced by rename is not.
        BackupEngine large = engine("live-large");
        byte[] big = new byte[9 * 1024 * 1024]; Arrays.fill(big, (byte) 7);
        Files.write(large.serverRoot().resolve("in-place.bin"), big);
        Files.write(large.serverRoot().resolve("replaced.bin"), big);
        Files.writeString(large.serverRoot().resolve("small.txt"), "small");
        large.afterReadProbe = name -> {
            try {
                if (name.equals("in-place.bin")) {
                    try (FileChannel channel = FileChannel.open(large.serverRoot().resolve(name), StandardOpenOption.WRITE)) {
                        channel.write(java.nio.ByteBuffer.wrap(new byte[]{1, 2, 3}), 4096);
                    }
                    Files.setLastModifiedTime(large.serverRoot().resolve(name), FileTime.from(Instant.now().plusSeconds(60)));
                }
                if (name.equals("replaced.bin")) {
                    Path temporary = large.serverRoot().resolve("replaced.bin.new");
                    Files.write(temporary, new byte[]{9});
                    Files.move(temporary, large.serverRoot().resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        };
        List<String> largeUnstable = new ArrayList<>();
        BackupRecord largeRecord = large.createBackup("large", () -> false, ignored -> {}, largeUnstable::add);
        large.afterReadProbe = null;
        equal(largeUnstable, List.of("in-place.bin"), "a large file written in place is reported; one replaced while open is complete");
        check(Arrays.equals(archived(largeRecord).get("replaced.bin"), big), "the version that was open is archived in full");

        // A mass deletion (arena reset) must not stall the backup while autosave is off.
        BackupEngine reset = engine("live-reset");
        Files.createDirectories(reset.serverRoot().resolve("arena"));
        for (int i = 0; i < 400; i++) Files.writeString(reset.serverRoot().resolve("arena/chunk-" + i), "x");
        Files.writeString(reset.serverRoot().resolve("zz-keep.txt"), "keep");
        AtomicBoolean deleted = new AtomicBoolean();
        long started = System.nanoTime();
        BackupRecord afterReset = reset.createBackup("reset", () -> false, message -> {
            if (message.startsWith("Backing up arena/") && deleted.compareAndSet(false, true)) {
                try (var files = Files.list(reset.serverRoot().resolve("arena"))) { for (Path file : files.toList()) Files.delete(file); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }, ignored -> {});
        long millis = (System.nanoTime() - started) / 1_000_000;
        check(millis < 15_000, "400 vanished files do not each cost a wait (took " + millis + " ms)");
        equal(archived(afterReset).keySet(), Set.of("zz-keep.txt"), "vanished files are skipped and the rest is archived");
        equal(afterReset.vanishedEntries(), 400, "the backup records how many files were gone when they were to be copied");
        equal(reset.listBackups().get(0).vanishedEntries(), 400, "and the count is read back from the store");
    }

    /** The one thing a change may never do is redirect the copy outside the server root. */
    private static void unsafeChangesDuringCopyAreRefused() throws Exception {
        BackupEngine engine = engine("live-unsafe");
        Path root = engine.serverRoot();
        Files.createDirectories(root.resolve("world/datapacks/builder"));
        Files.writeString(root.resolve("a-first.txt"), "first");
        Files.writeString(root.resolve("world/datapacks/builder/a.txt"), "inside");
        Path outside = Files.createDirectory(fixture.resolve("live-unsafe-outside"));
        Files.writeString(outside.resolve("a.txt"), "SECRET outside the server root");
        AtomicBoolean swapped = new AtomicBoolean();
        expectIOException(() -> engine.createBackup("swap", () -> false, message -> {
            if (message.equals("Backing up a-first.txt") && swapped.compareAndSet(false, true)) {
                try {
                    Files.move(root.resolve("world/datapacks/builder"), root.resolve("world/datapacks/builder.real"));
                    Files.createSymbolicLink(root.resolve("world/datapacks/builder"), outside);
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }, ignored -> {}), "a directory swapped for a symlink during the copy is refused");
        equal(engine.listBackups().size(), 0, "nothing is published after a refused copy");
        try (var files = Files.list(engine.backupDirectory())) {
            check(files.noneMatch(p -> p.toString().endsWith(".part") || p.toString().endsWith(".zip")), "refused archive cleaned");
        }
        Files.delete(root.resolve("world/datapacks/builder"));
        Files.move(root.resolve("world/datapacks/builder.real"), root.resolve("world/datapacks/builder"));
        AtomicBoolean replaced = new AtomicBoolean();
        expectIOException(() -> engine.createBackup("filelink", () -> false, message -> {
            if (message.equals("Backing up a-first.txt") && replaced.compareAndSet(false, true)) {
                try {
                    Files.delete(root.resolve("world/datapacks/builder/a.txt"));
                    Files.createSymbolicLink(root.resolve("world/datapacks/builder/a.txt"), outside.resolve("a.txt"));
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }, ignored -> {}), "a file swapped for a symlink during the copy is refused");
        equal(engine.listBackups().size(), 0, "nothing is published after a refused file");
    }

    private static void everyProblemIsReportedAtOnce() throws Exception {
        BackupEngine engine = engine("problems");
        Path root = engine.serverRoot();
        Files.createDirectories(root.resolve("plugins/Twin"));
        Files.writeString(root.resolve("ok.txt"), "ok");
        Files.writeString(root.resolve("plugins/dump:2026.txt"), "colon");
        Files.writeString(root.resolve("plugins/aux.yml"), "device name");
        Files.createSymbolicLink(root.resolve("plugins/linked.yml"), root.resolve("ok.txt"));
        Files.createSymbolicLink(root.resolve("linked-directory"), root.resolve("plugins"));
        boolean caseSensitive = true;
        try { Files.createDirectories(root.resolve("plugins/twin")); Files.writeString(root.resolve("plugins/twin/x"), "x"); }
        catch (IOException insensitive) { caseSensitive = false; }
        caseSensitive &= !Files.isSameFile(root.resolve("plugins/Twin"), root.resolve("plugins/twin"));
        try { engine.createBackup("problems"); throw new AssertionError("unbackupable paths accepted"); }
        catch (BackupEngine.SourceRefusedException refused) {
            List<String> problems = refused.problems();
            for (String expected : List.of("plugins/dump:2026.txt", "plugins/aux.yml", "plugins/linked.yml", "linked-directory"))
                check(problems.stream().anyMatch(problem -> problem.startsWith(expected + ": ")), "one refusal names " + expected);
            if (caseSensitive) check(problems.stream().anyMatch(problem -> problem.startsWith("plugins/twin: ") || problem.startsWith("plugins/Twin: ")), "one refusal names the case twin");
            equal(refused.total(), caseSensitive ? 5 : 4, "every problem is counted");
            check(refused.getMessage().contains("backup.exclude"), "the refusal says what to do");
        }
        equal(engine.listBackups().size(), 0, "nothing is published while problems exist");
        // Every one of them can be excluded, including the name with a colon.
        BackupEngine excluding = new BackupEngine(options(engine.options(), List.of("**"),
                List.of("plugins/dump:2026.txt", "plugins/aux.yml", "plugins/linked.yml", "linked-directory", "plugins/twin"), 1000, 1 << 20, 1 << 24, 10000));
        equal(archived(excluding.createBackup("excluded")).keySet(), Set.of("ok.txt"), "excluded problems no longer block the backup");

        // More problems than are listed individually are still all counted.
        BackupEngine many = engine("problems-many");
        Files.writeString(many.serverRoot().resolve("ok.txt"), "ok");
        for (int i = 0; i < 60; i++) Files.createSymbolicLink(many.serverRoot().resolve("link-" + i), many.serverRoot().resolve("ok.txt"));
        try { many.createBackup("many"); throw new AssertionError("symlinks accepted"); }
        catch (BackupEngine.SourceRefusedException refused) {
            equal(refused.total(), 60, "all sixty problems counted");
            equal(refused.problems().size(), 50, "fifty named");
            check(refused.getMessage().contains("10 more"), "the rest summarised");
        }

        // An unreadable directory can be excluded (it used to abort before exclusions were consulted).
        BackupEngine unreadable = engine("problems-unreadable");
        Path locked = Files.createDirectories(unreadable.serverRoot().resolve("lost+found"));
        Files.writeString(unreadable.serverRoot().resolve("ok.txt"), "ok");
        if (Files.getFileStore(locked).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
            boolean enforced;
            try (var stream = Files.newDirectoryStream(locked)) { enforced = false; } catch (IOException denied) { enforced = true; }
            if (enforced) { // Not when the harness runs as a user that ignores permission bits.
                try { unreadable.createBackup("unreadable"); throw new AssertionError("unreadable directory accepted"); }
                catch (BackupEngine.SourceRefusedException refused) { check(refused.problems().get(0).startsWith("lost+found: "), "unreadable directory is named"); }
                BackupEngine skipping = new BackupEngine(options(unreadable.options(), List.of("**"), List.of("lost+found/**"), 1000, 1 << 20, 1 << 24, 10000));
                equal(archived(skipping.createBackup("skipped")).keySet(), Set.of("ok.txt"), "an unreadable directory can be excluded");
            }
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static void selectionRules() throws Exception {
        BackupEngine base = engine("selection");
        Path root = base.serverRoot();
        Files.createDirectories(root.resolve("world/region"));
        Files.createDirectories(root.resolve("plugins/Foo/logs"));
        Files.writeString(root.resolve("world/level.dat"), "level");
        Files.writeString(root.resolve("world/region/r.0.0.mca"), "region");
        Files.writeString(root.resolve("plugins/Foo/config.yml"), "config");
        Files.writeString(root.resolve("plugins/Foo/debug.log"), "log");
        Files.writeString(root.resolve("plugins/Foo/logs/latest.log"), "log");
        Files.writeString(root.resolve("server.log"), "log");
        // A pattern that selects nothing must not publish an empty archive that retention would keep instead of real ones.
        for (String nothing : List.of("World/**", "./world/**", "worlds", "*.nothing")) {
            BackupEngine engine = new BackupEngine(options(base.options(), List.of(nothing), List.of(), 1000, 1 << 20, 1 << 24, 10000));
            expectIOException(() -> engine.createBackup("empty"), "include that matches no file is refused: " + nothing);
            equal(engine.listBackups().size(), 0, "no empty archive for " + nothing);
        }
        // A directory named without wildcards means the directory and everything below it.
        for (String directory : List.of("world", "world/", "world/**")) {
            BackupEngine engine = new BackupEngine(options(base.options(), List.of(directory), List.of(), 1000, 1 << 20, 1 << 24, 10000));
            BackupRecord record = engine.createBackup("directory");
            equal(archived(record).keySet(), Set.of("world/level.dat", "world/region/r.0.0.mca"), "include " + directory + " selects the directory tree");
            engine.delete(record.id(), Set.of());
        }
        BackupEngine wildcard = new BackupEngine(options(base.options(), List.of("plugins/*"), List.of(), 1000, 1 << 20, 1 << 24, 10000));
        expectIOException(() -> wildcard.createBackup("wildcard"), "a wildcard pattern keeps its exact meaning: plugins/* matches files directly in plugins only");
        BackupEngine excluded = new BackupEngine(options(base.options(), List.of("**"), List.of("**/*.log", "plugins/Foo/logs"), 1000, 1 << 20, 1 << 24, 10000));
        equal(archived(excluded.createBackup("excluded")).keySet(), Set.of("world/level.dat", "world/region/r.0.0.mca", "plugins/Foo/config.yml"),
                "**/*.log excludes logs at every depth, and a directory name excludes its tree");
        equal(BackupEngine.unsupportedGlobSyntax(List.of("**", "world/**", "*.{log,txt}", "[a-z]*", "!keep", "./world/**", "plain")),
                List.of("*.{log,txt}", "[a-z]*", "!keep", "./world/**"), "glob syntax that is not implemented is identified for a warning");
        for (String invalid : List.of("/absolute", "a/../b", "a\\b", " "))
            expectIOException(() -> new BackupEngine(options(base.options(), List.of(invalid), List.of(), 1000, 1 << 20, 1 << 24, 10000)), "invalid glob rejected: " + invalid);

        // A file that compresses better than the configured ratio is refused with its name, not as an unverifiable archive later.
        BackupEngine ratio = engine("selection-ratio");
        Files.write(ratio.serverRoot().resolve("zeros.bin"), new byte[4 * 1024 * 1024]);
        Files.writeString(ratio.serverRoot().resolve("ok.txt"), "ok");
        BackupEngine strict = new BackupEngine(options(ratio.options(), List.of("**"), List.of(), 1000, 1 << 24, 1 << 26, 100));
        try { strict.createBackup("ratio"); throw new AssertionError("over-compressible file accepted"); }
        catch (IOException refused) { check(refused.getMessage().contains("zeros.bin") && refused.getMessage().contains("max-compression-ratio"), "the refusal names the file and the limit"); }
        equal(strict.listBackups().size(), 0, "nothing is published");
        equal(ratio.createBackup("default-ratio").entryCount(), 2, "the default ratio accepts the same file");
    }

    /** A JVM stop or an interrupt between two filesystem steps must not leave files nobody ever removes. */
    private static void interruptedChangesAreSwept() throws Exception {
        BackupEngine engine = engine("sweep");
        Files.writeString(engine.serverRoot().resolve("x"), "data");
        BackupRecord kept = engine.createBackup("kept");
        BackupRecord victim = engine.createBackup("victim");
        byte[] zipBytes = Files.readAllBytes(victim.zipPath());
        // Deletion interrupted after the commit marker was withdrawn: the archive is still there, unlisted.
        Files.move(victim.metadataPath(), engine.backupDirectory().resolve("." + victim.id() + ".properties.part"));
        equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(kept.id()), "a withdrawn backup is not listed");
        engine.retain(10, Long.MAX_VALUE, Set.of(), () -> false, ignored -> {});
        check(!Files.exists(victim.zipPath()) && !Files.exists(engine.backupDirectory().resolve("." + victim.id() + ".properties.part")),
                "the next store change completes an interrupted deletion");
        // Publication interrupted between the two renames looks the same and is cleaned the same way.
        String id = "20260101T000000000Z-0123456789ab";
        Files.write(engine.backupDirectory().resolve(id + ".zip"), zipBytes);
        Files.writeString(engine.backupDirectory().resolve("." + id + ".properties.part"), "partial");
        // An archive somebody copied in without a sidecar has no such part file and is never touched.
        String foreign = "20260102T000000000Z-0123456789ab";
        Files.write(engine.backupDirectory().resolve(foreign + ".zip"), zipBytes);
        BackupRecord next = engine.createBackup("next");
        check(!Files.exists(engine.backupDirectory().resolve(id + ".zip")), "an archive whose publication was interrupted is removed");
        check(Files.exists(engine.backupDirectory().resolve(foreign + ".zip")), "an archive without any trace of this engine is left alone");
        equal(engine.listBackups().size(), 2, "the store lists the two complete backups");
        check(engine.rejectedArchives().containsKey(foreign), "but it is reported as unusable");
        engine.delete(foreign, Set.of());
        check(!Files.exists(engine.backupDirectory().resolve(foreign + ".zip")) && engine.listBackups().size() == 2 && engine.rejectedArchives().isEmpty(),
                "and can be deleted by its ID");
        engine.delete(next.id(), Set.of());
        try (var files = Files.list(engine.backupDirectory())) {
            equal(files.map(p -> p.getFileName().toString()).filter(name -> !name.equals(".vaultbackup.lock")).sorted().toList(),
                    List.of(kept.id() + ".properties", kept.id() + ".zip"), "nothing but the remaining backup is left in the store");
        }
    }

    private static void rejectedArchivesAreVisibleAndDeletable() throws Exception {
        BackupEngine engine = engine("rejected");
        Files.writeString(engine.serverRoot().resolve("a"), "a");
        Files.writeString(engine.serverRoot().resolve("b"), "b");
        BackupRecord good = engine.createBackup("good");
        BackupRecord broken = engine.createBackup("broken");
        check(engine.rejectedArchives().isEmpty(), "a healthy store rejects nothing");
        byte[] bytes = Files.readAllBytes(broken.zipPath()); bytes[bytes.length / 2] ^= 1;
        Files.write(broken.zipPath(), bytes);
        equal(engine.listBackups().stream().map(BackupRecord::id).toList(), List.of(good.id()), "a corrupt archive is not listed");
        equal(engine.rejectedArchives().keySet(), Set.of(broken.id()), "but it is reported");
        check(engine.rejectedArchives().get(broken.id()).contains("SHA-256"), "with the reason: " + engine.rejectedArchives().get(broken.id()));
        expectRefusal(() -> engine.delete(broken.id(), Set.of(broken.id())), BackupDeletionRefusedException.Reason.RESTORE_PROTECTED, broken.id());
        engine.delete(broken.id(), Set.of());
        check(!Files.exists(broken.zipPath()) && !Files.exists(broken.metadataPath()), "a corrupt archive can be deleted by its ID");
        equal(engine.listBackups().size(), 1, "the good archive is untouched");
        check(engine.rejectedArchives().isEmpty(), "nothing is rejected afterwards");
        // Lowering a limit no longer makes archives vanish without a trace.
        BackupEngine tighter = new BackupEngine(options(engine.options(), List.of("**"), List.of(), 1, 1 << 20, 1 << 24, 10000));
        equal(tighter.listBackups().size(), 0, "an archive beyond the configured limits is not listed");
        check(tighter.rejectedArchives().get(good.id()).contains("limit"), "and the reason names the limits");
        tighter.delete(good.id(), Set.of());
        check(!Files.exists(good.zipPath()), "it can still be deleted");
        expectIOException(() -> engine.delete(good.id(), Set.of()), "deleting what does not exist is an error");
    }

    /** Archives another local user could place in the store must not be able to take the server's heap. */
    private static void craftedArchivesStayWithinMemory() throws Exception {
        BackupEngine engine = engine("crafted-memory");
        String id = "20260101T000000000Z-0123456789ab";
        Path zip = engine.backupDirectory().resolve(id + ".zip"), metadata = engine.backupDirectory().resolve(id + ".properties");
        // 400 entries, each 2,000 one-letter directories deep: every check passes, but keeping every
        // ancestor prefix as a string needed gigabytes.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 2000; i++) deep.append("d/");
        List<Part> parts = new ArrayList<>();
        StringBuilder manifest = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            String name = deep + "f" + i;
            parts.add(new Part(name, new byte[0]));
            manifest.append(line("F", name, 0, hash(new byte[0])));
        }
        crafted(zip, metadata, parts, manifest.toString(), 0, 400);
        equal(engine.verify(id).entryCount(), 400, "a deep but valid archive verifies");
        // Keeping every prefix needed about 1.6 GiB for this archive. A JVM limited to 96 MiB must be
        // able to verify it; unlike a heap measurement, that does not depend on when a collector runs.
        String launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder constrained = new ProcessBuilder(launcher, "-Xmx96m", "-cp", System.getProperty("java.class.path"),
                BackupEngineTest.class.getName(), "--memory-child", zip.toString(), metadata.toString());
        constrained.environment().remove("JAVA_TOOL_OPTIONS");
        constrained.redirectErrorStream(true);
        Process limited = constrained.start();
        String limitedOutput = new String(limited.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(limited.waitFor(120, TimeUnit.SECONDS) && limited.exitValue() == 0 && limitedOutput.contains("MEMORY-CHILD-OK 400"),
                "path bookkeeping stays proportional to the number of components: " + limitedOutput);
        // More implied directories than entries allow is refused instead of tracked.
        BackupOptions few = options(engine.options(), List.of(), List.of(), 400, 1 << 20, 1 << 24, 10000);
        crafted(zip, metadata, parts, manifest.toString(), 0, 400);
        expectIOException(() -> BackupEngine.verifyArchive(zip, metadata, few, () -> false, ignored -> {}), "an unbounded directory structure is refused");

        // An end record that claims a huge central directory inside a sparse file: refused before java.util.zip allocates for it.
        crafted(zip, metadata, List.of(new Part("a", new byte[]{1})), line("F", "a", 1, hash(new byte[]{1})), 1, 1);
        byte[] small = Files.readAllBytes(zip);
        long claimed = 1L << 31;
        try (FileChannel channel = FileChannel.open(zip, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer end = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 1)
                    .putInt((int) (claimed - 64)).putInt(0).putShort((short) 0).flip();
            channel.write(end, claimed); // a hole of 2 GiB in front: almost nothing is allocated on disk
        }
        try {
            ZipDirectory.requireWithin(zip, 100, 1 << 20);
            throw new AssertionError("oversized central directory accepted");
        } catch (IOException refused) { checks++; }
        Files.write(zip, small);
        // The layout this project writes is the only one read: an archive comment moves the end record.
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.setComment("comment");
            output.putNextEntry(new ZipEntry("a")); output.write(1); output.closeEntry();
        }
        expectIOException(() -> ZipDirectory.read(zip), "an archive comment is not the accepted layout");
        Files.write(zip, new byte[]{1, 2, 3});
        expectIOException(() -> ZipDirectory.read(zip), "a file too short to be a ZIP is refused");
        // What the engine writes is accepted, also beyond the 16-bit entry count.
        BackupEngine many = engine("crafted-many");
        for (int i = 0; i < 70; i++) Files.createDirectories(many.serverRoot().resolve("d" + i));
        for (int i = 0; i < 70; i++) for (int j = 0; j < 950; j++) Files.createFile(many.serverRoot().resolve("d" + i + "/f" + j));
        BackupRecord large = many.createBackup("many");
        equal(ZipDirectory.read(large.zipPath()).entries(), 70L * 950 + 70 + 1, "the ZIP64 end record of a large archive is read correctly");
        equal(many.verify(large.id()).entryCount(), 70 * 950 + 70, "and the archive verifies");
    }

    private static void spaceIsCheckedBeforeAndWhileWriting() throws Exception {
        BackupEngine engine = engine("space");
        byte[] random = new byte[40 * 1024 * 1024]; new Random(7).nextBytes(random);
        Files.write(engine.serverRoot().resolve("big.bin"), random);
        Files.writeString(engine.serverRoot().resolve("small.txt"), "small");
        // Not even the reserve is free: refused before anything is written.
        engine.usableSpaceProbe = () -> 16L * 1024 * 1024;
        try { engine.createBackup("full"); throw new AssertionError("backup started without free space"); }
        catch (BackupEngine.InsufficientSpaceException full) { check(full.availableBytes() == 16L * 1024 * 1024 && full.neededBytes() > full.availableBytes(), "the refusal carries both numbers"); }
        try (var files = Files.list(engine.backupDirectory())) { check(files.noneMatch(p -> p.toString().endsWith(".part")), "nothing was written"); }
        // Enough at the start, but the disk fills while the archive grows: stopped at the reserve, not at zero.
        AtomicInteger looks = new AtomicInteger();
        engine.usableSpaceProbe = () -> looks.incrementAndGet() == 1 ? Long.MAX_VALUE / 4 : 1024;
        try { engine.createBackup("filling"); throw new AssertionError("backup ran the disk down"); }
        catch (BackupEngine.InsufficientSpaceException full) { check(looks.get() >= 2, "free space is looked at again while writing"); }
        try (var files = Files.list(engine.backupDirectory())) { check(files.noneMatch(p -> p.toString().endsWith(".part") || p.toString().endsWith(".zip")), "the partial archive is removed"); }
        engine.usableSpaceProbe = null;
        BackupRecord record = engine.createBackup("fits");
        // The estimate follows how the previous archive compressed: incompressible data needs about its own size.
        engine.usableSpaceProbe = () -> record.compressedBytes() / 2;
        try { engine.createBackup("half"); throw new AssertionError("estimate ignored the previous archive"); }
        catch (BackupEngine.InsufficientSpaceException full) { check(full.neededBytes() > record.compressedBytes(), "the estimate is taken from the previous archive"); }
        engine.usableSpaceProbe = null;
        equal(engine.listBackups().size(), 1, "refused backups leave the store as it was");
    }

    /**
     * A JVM started without a UTF-8 locale cannot represent non-ASCII file names. It used to archive
     * them under replacement characters and report success. The check runs in a child JVM because the
     * file-name encoding is fixed when a JVM starts.
     */
    private static void fileNamesNeedARepresentableEncoding() throws Exception {
        Path base = Files.createDirectory(fixture.resolve("locale"));
        Path root = Files.createDirectory(base.resolve("server"));
        Files.writeString(root.resolve("ascii.txt"), "ascii");
        boolean utf8 = "UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"));
        if (utf8) {
            Files.createDirectories(root.resolve("plugins/ワールド設定"));
            Files.writeString(root.resolve("plugins/ワールド設定/設定.yml"), "日本語");
            BackupEngine engine = new BackupEngine(BackupOptions.defaults(root, base.resolve("backups"), base.resolve("state")));
            check(archived(engine.createBackup("utf8")).containsKey("plugins/ワールド設定/設定.yml"), "non-ASCII names are archived under their real names in a UTF-8 JVM");
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return;
        if (!utf8) { // The files must exist under their real UTF-8 names for the child to find.
            Process make = new ProcessBuilder("sh", "-c", "mkdir -p \"$1/plugins/$(printf '\\343\\203\\257')\" && : > \"$1/plugins/$(printf '\\343\\203\\257')/$(printf '\\350\\250\\255').yml\"", "sh", root.toString()).inheritIO().start();
            check(make.waitFor() == 0, "fixture with non-ASCII names created");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), BackupEngineTest.class.getName(), "--locale-child", base.toString());
        child.environment().put("LC_ALL", "C");
        child.environment().put("LANG", "C");
        child.environment().remove("JAVA_TOOL_OPTIONS");
        child.redirectErrorStream(true);
        Process process = child.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "child JVM without a UTF-8 locale: " + output);
        check(output.contains("LOCALE-CHILD-OK"), "child completed its checks: " + output);
    }

    /**
     * The checks before a file is opened and the open itself are separate steps. Whoever can change
     * directories inside the server root between them must not thereby get a file from outside it
     * into the archive, nor make the backup wait forever on something that is not a file.
     */
    private static void readsCannotBeRedirectedOutsideTheRoot() throws Exception {
        BackupEngine engine = engine("redirect");
        Path root = engine.serverRoot(), outside = Files.createDirectory(fixture.resolve("redirect/outside"));
        Files.createDirectories(root.resolve("uploads"));
        Files.writeString(root.resolve("uploads/f.txt"), "INSIDE");
        Files.writeString(root.resolve("keep.txt"), "keep");
        Files.writeString(outside.resolve("f.txt"), "SECRET"); // the same length, so that sizes agree
        boolean links = true;
        try { Files.createSymbolicLink(fixture.resolve("redirect/probe-link"), outside); }
        catch (UnsupportedOperationException | IOException unsupported) { links = false; }
        if (!links) return;
        Path real = root.resolve("uploads"), parked = fixture.resolve("redirect/parked");
        // Exactly the window: the directory is a symlink while the file is opened, and a directory again when it is looked at afterwards.
        engine.beforeOpenProbe = name -> {
            if (!name.equals("uploads/f.txt") || Files.isSymbolicLink(real)) return;
            try { Files.move(real, parked); Files.createSymbolicLink(real, outside); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        };
        engine.afterReadProbe = name -> {
            if (!name.equals("uploads/f.txt") || !Files.isSymbolicLink(real)) return;
            try { Files.delete(real); Files.move(parked, real); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        };
        BackupRecord record = null;
        try { record = engine.createBackup("redirect"); }
        catch (IOException refused) {
            check(refused.getMessage().contains("symlink"), "a directory swapped for a symlink at the moment of the open is refused: " + refused.getMessage());
        }
        engine.beforeOpenProbe = null;
        engine.afterReadProbe = null;
        if (Files.isSymbolicLink(real)) { Files.delete(real); Files.move(parked, real); }
        boolean relativeOpens;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) { relativeOpens = stream instanceof SecureDirectoryStream<Path>; }
        if (relativeOpens) check(record == null, "where files are opened relative to their directory, the swapped directory is refused at the open itself");
        // Refusing is the expected outcome; whatever the outcome, content from outside the root is never archived.
        if (record != null) check(!"SECRET".equals(text(archived(record), "uploads/f.txt")), "content from outside the server root must never be archived");
        for (BackupRecord kept : engine.listBackups())
            check(archived(kept).values().stream().noneMatch(bytes -> new String(bytes, StandardCharsets.UTF_8).contains("SECRET")), "no archive holds the outside file");
        equal(text(archived(engine.createBackup("clean")), "uploads/f.txt"), "INSIDE", "the same tree backs up normally afterwards");

        // Something that is not a file takes the place of a file right before it is opened: opening a
        // FIFO would wait for a writer for ever. The backup must come back, with a refusal.
        Path pipeTarget = root.resolve("uploads/queue.dat");
        Files.writeString(pipeTarget, "data");
        Process probe;
        try { probe = new ProcessBuilder("mkfifo", fixture.resolve("redirect/fifo-probe").toString()).start(); }
        catch (IOException noMkfifo) { return; }
        if (!probe.waitFor(10, TimeUnit.SECONDS) || probe.exitValue() != 0) return;
        engine.beforeOpenProbe = name -> {
            if (!name.equals("uploads/queue.dat")) return;
            try {
                Files.deleteIfExists(pipeTarget);
                Process make = new ProcessBuilder("mkfifo", pipeTarget.toString()).start();
                if (!make.waitFor(10, TimeUnit.SECONDS) || make.exitValue() != 0) throw new IOException("mkfifo failed");
            } catch (IOException e) { throw new UncheckedIOException(e); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Object> outcome = worker.submit(() -> {
                try { return engine.createBackup("fifo"); } catch (IOException refused) { return refused; }
            });
            Object result;
            try { result = outcome.get(30, TimeUnit.SECONDS); }
            catch (TimeoutException hung) {
                // Release the reader so that the harness can end, then fail.
                try (OutputStream unblock = Files.newOutputStream(pipeTarget)) { unblock.write(0); }
                throw new AssertionError("the backup waited on a FIFO that replaced a file");
            }
            check(result instanceof IOException refused && refused.getMessage().contains("special file"), "a FIFO in place of a file is refused without being opened: " + result);
        } finally {
            worker.shutdownNow();
            engine.beforeOpenProbe = null;
            Files.deleteIfExists(pipeTarget);
        }
    }

    /**
     * Filesystems store modification times in steps. A file rewritten within one step has the same
     * size and time before and after, so those prove nothing while the time is close to now.
     */
    private static void recentTimestampsProveNothing() throws Exception {
        BackupEngine engine = engine("racy");
        Path file = engine.serverRoot().resolve("data.bin");
        Files.writeString(file, "AAAA");
        FileTime written = Files.getLastModifiedTime(file);
        AtomicInteger looks = new AtomicInteger();
        // Rewritten in place once, right after the first read, without its time changing.
        engine.afterReadProbe = name -> {
            if (!name.equals("data.bin") || looks.getAndIncrement() > 0) return;
            try { Files.writeString(file, "BBBB", StandardOpenOption.WRITE); Files.setLastModifiedTime(file, written); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        };
        List<String> unstable = new ArrayList<>();
        BackupRecord record = engine.createBackup("racy", () -> false, ignored -> {}, unstable::add);
        equal(text(archived(record), "data.bin"), "BBBB", "a rewrite hidden by an unchanged timestamp is noticed by reading twice");
        equal(unstable, List.of(), "and the file is stable once two reads agree");
        // Rewritten after every read: no two reads agree, and it is reported instead of being called stable.
        AtomicInteger turn = new AtomicInteger();
        engine.afterReadProbe = name -> {
            if (!name.equals("data.bin")) return;
            try { Files.writeString(file, turn.incrementAndGet() % 2 == 0 ? "CCCC" : "DDDD", StandardOpenOption.WRITE); Files.setLastModifiedTime(file, written); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        };
        unstable.clear();
        record = engine.createBackup("racy-always", () -> false, ignored -> {}, unstable::add);
        equal(unstable, List.of("data.bin"), "a file that changes behind an unchanged timestamp on every look is reported");
        equal(record.unstableEntries(), 1, "and counted");
        engine.afterReadProbe = null;
        // An old timestamp is evidence: nothing is read twice. (A read is counted by the probe before each open.)
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3600)));
        AtomicInteger opens = new AtomicInteger();
        engine.beforeOpenProbe = name -> { if (name.equals("data.bin")) opens.incrementAndGet(); };
        engine.createBackup("settled");
        equal(opens.get(), 1, "a file with an old modification time is read once");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        opens.set(0);
        engine.createBackup("fresh");
        equal(opens.get(), 2, "a file modified just now is read twice");
        engine.beforeOpenProbe = null;

        // Too large to read twice: the copy waits until the time is old enough to mean something.
        BackupEngine large = engine("racy-large");
        byte[] big = new byte[9 * 1024 * 1024];
        new Random(5).nextBytes(big);
        Path bigFile = large.serverRoot().resolve("big.bin");
        Files.write(bigFile, big);
        large.racyTimestampMillis = 700;
        unstable.clear();
        long started = System.nanoTime();
        BackupRecord waited = large.createBackup("wait", () -> false, ignored -> {}, unstable::add);
        long millis = (System.nanoTime() - started) / 1_000_000;
        check(unstable.isEmpty() && Arrays.equals(archived(waited).get("big.bin"), big), "a large file written just now is archived as stable after the wait");
        check(millis >= 300, "the copy waited for the timestamp to settle (took " + millis + " ms)");
        // When waiting is not possible, the file is not called stable on the strength of such a timestamp.
        large.racyTimestampMillis = 3_600_000;
        Files.setLastModifiedTime(bigFile, FileTime.from(Instant.now()));
        unstable.clear();
        BackupRecord unproven = large.createBackup("unproven", () -> false, ignored -> {}, unstable::add);
        equal(unstable, List.of("big.bin"), "a large file whose timestamp cannot be trusted is reported, not called stable");
        check(Arrays.equals(archived(unproven).get("big.bin"), big), "it is archived all the same");
        // A time far in the future is not "now" either: rewriting the file would replace it.
        Files.setLastModifiedTime(bigFile, FileTime.from(Instant.now().plusSeconds(86_400 * 30L)));
        large.racyTimestampMillis = 3000;
        unstable.clear();
        large.createBackup("future", () -> false, ignored -> {}, unstable::add);
        equal(unstable, List.of(), "a file dated far in the future is not held up or reported");
    }

    private static void vanishedFilesAreCountedAndAnEmptyResultIsRefused() throws Exception {
        BackupEngine engine = engine("vanish");
        Path data = Files.createDirectories(engine.serverRoot().resolve("data"));
        for (int i = 0; i < 30; i++) Files.writeString(data.resolve("f" + i + ".txt"), "content " + i);
        Files.createDirectories(engine.serverRoot().resolve("empty-dir"));
        BackupRecord good = engine.createBackup("good");
        equal(good.vanishedEntries(), 0, "nothing vanished from a quiet tree");
        // A few files go: counted, named, and the rest is a backup.
        List<String> vanished = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean();
        BackupRecord partial = engine.createBackup("partial", () -> false, message -> {
            if (message.startsWith("Backing up data/") && done.compareAndSet(false, true)) {
                try { for (int i = 20; i < 23; i++) Files.delete(data.resolve("f" + i + ".txt")); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }, ignored -> {}, vanished::add);
        equal(vanished, List.of("data/f20.txt", "data/f21.txt", "data/f22.txt"), "every vanished file is named");
        equal(partial.vanishedEntries(), 3, "and counted in the record");
        equal(archived(partial).size(), 27, "the files that were there are archived");
        // The whole tree is moved away after it was listed (unmounted, renamed, deleted): what is left
        // would verify as an archive, and retention would delete real backups for it.
        Path away = fixture.resolve("vanish/moved-away");
        done.set(false);
        try {
            engine.createBackup("gone", () -> false, message -> {
                if (message.startsWith("Backing up data/") && done.compareAndSet(false, true)) {
                    try { Files.move(data, away); } catch (IOException e) { throw new UncheckedIOException(e); }
                }
            }, ignored -> {});
            throw new AssertionError("an archive without a single file was published");
        } catch (BackupEngine.SourceVanishedException gone) {
            equal(gone.scanned(), 27, "the refusal says how many files there were");
        }
        equal(engine.listBackups().stream().map(BackupRecord::label).sorted().toList(), List.of("good", "partial"), "nothing was published, and the real backups are untouched");
        try (var files = Files.list(engine.backupDirectory())) {
            check(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")), "no partial file is left behind");
        }
    }

    /**
     * On POSIX a process loses its lock on a file when it closes any handle of that file. The server
     * holds a lock on each world's session.lock; a backup that read the file from inside the server
     * would release it without anybody noticing.
     */
    private static void sessionLockIsNeverOpened() throws Exception {
        Path base = Files.createDirectory(fixture.resolve("session-lock"));
        Path root = Files.createDirectory(base.resolve("server"));
        Path world = Files.createDirectories(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "level");
        Path sessionLock = world.resolve("session.lock");
        Files.writeString(sessionLock, "\u2603");
        Files.writeString(root.resolve("session.lock.txt"), "an ordinary file");
        // A configuration that selects everything and excludes nothing.
        BackupEngine engine = new BackupEngine(options(BackupOptions.defaults(root, base.resolve("backups"), base.resolve("state")),
                List.of("**"), List.of(), 1000, 1 << 20, 1 << 24, 1000));
        AtomicBoolean opened = new AtomicBoolean();
        engine.beforeOpenProbe = name -> { if (name.endsWith("/session.lock")) opened.set(true); };
        try (FileChannel channel = FileChannel.open(sessionLock, StandardOpenOption.WRITE); FileLock held = channel.lock()) {
            BackupRecord record = engine.createBackup("locked");
            equal(archived(record).keySet(), Set.of("world/level.dat", "session.lock.txt"), "session.lock is left out whatever the patterns select; similar names are not");
            check(!opened.get() && held.isValid(), "the file is never opened");
            String launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder child = new ProcessBuilder(launcher, "-cp", System.getProperty("java.class.path"), BackupEngineTest.class.getName(), "--lock-child", sessionLock.toString());
            child.environment().remove("JAVA_TOOL_OPTIONS");
            child.redirectErrorStream(true);
            Process process = child.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 && output.contains("LOCK-CHILD HELD"),
                    "another process still finds the world locked after a backup: " + output);
        } finally { engine.beforeOpenProbe = null; }
    }

    private static void lockChild(Path file) throws Exception {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            System.out.println(lock == null ? "LOCK-CHILD HELD" : "LOCK-CHILD FREE");
        }
    }

    /** A file the server may not read is one of the problems that are reported together, before anything is copied. */
    private static void unreadableFilesAreFoundBeforeCopying() throws Exception {
        BackupEngine engine = engine("unreadable");
        Path root = engine.serverRoot();
        Files.writeString(root.resolve("fine.txt"), "fine");
        Path secret = root.resolve("secret.key"), other = root.resolve("other.key");
        Files.writeString(secret, "secret");
        Files.writeString(other, "other");
        if (!Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class)) return;
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("---------"));
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("---------"));
        try {
            if (Files.isReadable(secret)) return; // The harness runs as a user that ignores permission bits.
            AtomicBoolean copying = new AtomicBoolean();
            try {
                engine.createBackup("unreadable", () -> false, message -> { if (message.startsWith("Backing up")) copying.set(true); }, ignored -> {});
                throw new AssertionError("a tree with unreadable files was backed up");
            } catch (BackupEngine.SourceRefusedException refused) {
                equal(refused.total(), 2, "both unreadable files are reported at once");
                check(refused.problems().stream().allMatch(problem -> problem.contains("cannot be read")) && !copying.get(), "before anything is copied: " + refused.problems());
            }
        } finally {
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static void memoryChild(Path zip, Path metadata) throws Exception {
        Path base = zip.toAbsolutePath().getParent().getParent();
        BackupOptions limits = BackupOptions.defaults(base.resolve("server"), base.resolve("backups"), base.resolve("state"));
        BackupRecord record = BackupEngine.verifyArchive(zip, metadata, limits, () -> false, ignored -> {});
        System.out.println("MEMORY-CHILD-OK " + record.entryCount());
    }

    private static void localeChild(Path base) throws Exception {
        if ("UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"))) { System.out.println("LOCALE-CHILD-OK (this platform always uses UTF-8 file names)"); return; }
        BackupEngine engine = new BackupEngine(BackupOptions.defaults(base.resolve("server"), base.resolve("child-backups"), base.resolve("child-state")));
        try { engine.createBackup("posix-locale"); throw new AssertionError("unrepresentable names archived"); }
        catch (BackupEngine.SourceRefusedException refused) {
            if (refused.problems().stream().noneMatch(problem -> problem.contains("UTF-8 locale"))) throw new AssertionError("refusal does not explain the locale: " + refused.problems());
        }
        if (!engine.listBackups().isEmpty()) throw new AssertionError("archive published with wrong names");
        BackupEngine ascii = new BackupEngine(new BackupOptions(base.resolve("server"), base.resolve("child-backups"), base.resolve("child-state"),
                List.of("**"), List.of("plugins/**"), 1000, 1 << 20, 1 << 24, 10000d, 6));
        if (ascii.createBackup("ascii").entryCount() < 1) throw new AssertionError("ASCII names must still be backed up");
        System.out.println("LOCALE-CHILD-OK");
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
    private static void expectRefusal(ThrowingRunnable action, BackupDeletionRefusedException.Reason reason, String id) throws Exception {
        try { action.run(); throw new AssertionError("Expected typed deletion refusal: " + reason); }
        catch (BackupDeletionRefusedException expected) {
            equal(expected.reason(), reason, "typed deletion reason");
            equal(expected.backupId(), id, "typed deletion target");
        }
    }
    private static void expectIOException(ThrowingRunnable action, String message) throws Exception {
        checks++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException: " + message);
    }
}
