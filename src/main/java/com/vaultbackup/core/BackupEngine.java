// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.*;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/**
 * Standard-library-only archive storage with a final atomic sidecar commit marker.
 * Backups must be taken while the application has quiesced its writes. The change
 * checks detect ordinary races; they cannot provide a filesystem snapshot against
 * a hostile process with concurrent write access to the source or backup store.
 */
public final class BackupEngine {
    public static final String MANIFEST_PATH = ".vaultbackup-manifest";
    public static final String MANIFEST_HEADER = "VAULTBACKUP-MANIFEST\t1";
    public static final long MAX_MANIFEST_BYTES = 128L * 1024 * 1024;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final Pattern ID = Pattern.compile("[0-9]{8}T[0-9]{9}Z-[a-f0-9]{12}");
    /** Exactly the temporary names createBackup uses; nothing else in the store is ever swept. */
    private static final Pattern ABANDONED_PART = Pattern.compile("\\.[0-9]{8}T[0-9]{9}Z-[a-f0-9]{12}\\.(?:zip|properties)\\.part");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    /** Binds a backup store to exactly one server root; never listed as a backup (no ID name). */
    public static final String OWNER_MARKER = ".vaultbackup-owner.properties";
    /** Windows-only name rules apply where the archive can be materialized on Windows. */
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final Pattern DEVICE = Pattern.compile("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9\u00b9\u00b2\u00b3]|LPT[0-9\u00b9\u00b2\u00b3]");
    private static final BooleanSupplier NEVER_CANCEL = () -> false;
    private static final Consumer<String> NO_PROGRESS = value -> { };
    private final BackupOptions options;
    private final Path root;
    private final Path destination;
    private final Path state;
    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    public record ManifestEntry(String path, boolean directory, long size, String sha256) { }
    private record Source(Path path, String name, boolean directory, BasicFileAttributes attributes) { }
    /** Identity, size and timestamps of an archive/sidecar pair at the moment it fully verified. */
    private record Fingerprint(Object zipKey, long zipSize, FileTime zipModified, FileTime zipCreated,
                               Object metadataKey, long metadataSize, FileTime metadataModified, FileTime metadataCreated) { }
    private record VerifiedArchive(Fingerprint fingerprint, BackupRecord record) { }
    /**
     * Archives this instance fully verified, keyed by ID. A listing reuses an entry only while
     * both files are provably the same, unchanged files; any difference forces full verification.
     * Explicit verify() and revalidateBackups() always re-read every byte. Guarded by this.
     */
    private final Map<String, VerifiedArchive> verifiedArchives = new HashMap<>();
    /** Archives the latest catalogue pass could not verify, with a printable reason. Guarded by this. */
    private final Map<String, String> rejected = new LinkedHashMap<>();

    public BackupEngine(BackupOptions options) throws IOException {
        this.options = Objects.requireNonNull(options);
        root = checkedExistingDirectory(options.serverRoot());
        destination = prepareDirectory(options.backupDirectory());
        state = checkedAbsolutePath(options.stateDirectory());
        if (root.startsWith(destination)) throw new IOException("Backup destination cannot be the server root or its ancestor");
        if (root.startsWith(state)) throw new IOException("State directory cannot be the server root or its ancestor");
        if (destination.equals(state)) throw new IOException("Backup and state directories must differ");
        includes = compileGlobs(options.includeGlobs());
        excludes = compileGlobs(options.excludeGlobs());
        claimStore();
    }

    /**
     * Sibling servers resolving the same default paths must not list, prune or restore each
     * other's archives. The first engine claims an unclaimed store; any other root is refused.
     */
    private void claimStore() throws IOException {
        Path marker = destination.resolve(OWNER_MARKER);
        String expected = root.toString();
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = ensureRegularFile(marker);
            // A crash can leave the freshly created marker empty; that never names an owner.
            if (attributes.size() > 0) {
                String owner = readOwner(marker);
                if (!owner.equals(expected)) throw new IOException("Backup directory " + printable(destination.toString())
                        + " belongs to another server root (" + printable(owner) + "). Give every server its own storage.backup-directory"
                        + " and storage.state-directory. If this server was moved, delete " + printable(marker.toString())
                        + " only after confirming that no other server uses this directory.");
                return;
            }
        }
        Properties owner = new Properties();
        owner.setProperty("formatVersion", "1");
        owner.setProperty("serverRoot", expected);
        Path temp = destination.resolve(".vaultbackup-owner." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = createPrivateFile(temp)) {
                OutputStream output = Channels.newOutputStream(channel);
                owner.store(output, "VaultBackup Pro backup store owner");
                output.flush();
                channel.force(true);
            }
            if (Files.exists(marker, NOFOLLOW_LINKS) && Files.size(marker) > 0) {
                if (!readOwner(marker).equals(expected)) throw new IOException("Backup directory was claimed by another server root: " + printable(destination.toString()));
                return;
            }
            Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(destination);
        } finally { Files.deleteIfExists(temp); }
    }

    private static String readOwner(Path marker) throws IOException {
        ensureRegularFile(marker);
        if (Files.size(marker) > 16 * 1024) throw new IOException("Backup store owner marker is too large: " + marker);
        Properties owner = new Properties();
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(marker, Set.of(READ, NOFOLLOW_LINKS)))) { owner.load(input); }
        catch (IllegalArgumentException e) { throw new IOException("Malformed backup store owner marker: " + marker, e); }
        String value = owner.getProperty("serverRoot");
        if (!"1".equals(owner.getProperty("formatVersion")) || value == null || value.isBlank()) throw new IOException("Unreadable backup store owner marker: " + marker);
        return value;
    }

    public BackupOptions options() { return options; }
    public Path serverRoot() { return root; }
    public Path backupDirectory() { return destination; }

    public synchronized BackupRecord createBackup(String label, BooleanSupplier cancelled,
                                                    Consumer<String> progress) throws IOException {
        cancelled = cancellation(cancelled);
        progress = reporter(progress);
        if (label == null) label = "";
        if (!validLabel(label)) throw new IOException("Label must be at most 200 printable characters");
        checkCancelled(cancelled);
        checkedExistingDirectory(root);
        checkedExistingDirectory(destination);
        try (FileChannel lockChannel = openLock(); FileLock ignored = acquireLock(lockChannel)) {
            removeAbandonedPartFiles();
            String id = ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Path tempZip = destination.resolve("." + id + ".zip.part");
            Path zip = destination.resolve(id + ".zip");
            Path tempMetadata = destination.resolve("." + id + ".properties.part");
            Path metadata = destination.resolve(id + ".properties");
            boolean committed = false;
            try {
                progress.accept("Scanning server files");
                List<Source> sources = scan(cancelled);
                List<ManifestEntry> manifest = new ArrayList<>();
                long total = 0;
                try (FileChannel channel = createPrivateFile(tempZip);
                     ZipOutputStream output = new ZipOutputStream(Channels.newOutputStream(channel), StandardCharsets.UTF_8)) {
                    output.setLevel(options.compressionLevel());
                    byte[] buffer = new byte[BUFFER_SIZE];
                    for (Source source : sources) {
                        checkCancelled(cancelled);
                        ensureSourceUnchanged(source);
                        if (source.name().isEmpty()) continue; // Root is checked, never serialized.
                        ZipEntry entry = new ZipEntry(source.name() + (source.directory() ? "/" : ""));
                        entry.setLastModifiedTime(source.attributes().lastModifiedTime());
                        output.putNextEntry(entry);
                        if (source.directory()) {
                            manifest.add(new ManifestEntry(source.name(), true, 0, "-"));
                        } else {
                            progress.accept("Backing up " + source.name());
                            MessageDigest digest = sha256();
                            long copied = 0;
                            try (InputStream input = Channels.newInputStream(Files.newByteChannel(source.path(), Set.of(READ, NOFOLLOW_LINKS)))) {
                                int count;
                                while ((count = input.read(buffer)) != -1) {
                                    checkCancelled(cancelled);
                                    copied = boundedAdd(copied, count, options.maxEntryBytes(), "Entry exceeds byte limit");
                                    total = boundedAdd(total, count, options.maxTotalBytes(), "Backup exceeds total byte limit");
                                    digest.update(buffer, 0, count);
                                    output.write(buffer, 0, count);
                                }
                            }
                            ensureSourceUnchanged(source);
                            if (copied != source.attributes().size()) throw new IOException("Source changed during backup: " + source.name());
                            manifest.add(new ManifestEntry(source.name(), false, copied, hex(digest.digest())));
                        }
                        output.closeEntry();
                    }
                    for (Source source : sources) ensureSourceUnchanged(source);
                    output.putNextEntry(new ZipEntry(MANIFEST_PATH));
                    writeManifest(output, manifest);
                    output.closeEntry();
                    output.finish();
                    output.flush();
                    channel.force(true);
                }
                checkCancelled(cancelled);
                BackupRecord provisional = new BackupRecord(id, Instant.now(), label, tempZip, tempMetadata,
                        Files.size(tempZip), total, manifest.size(), hashFile(tempZip, cancelled));
                writeMetadata(tempMetadata, provisional);
                progress.accept("Verifying new archive");
                validateArchive(provisional, options, cancelled, progress);
                checkCancelled(cancelled);
                // Metadata is the commit marker and is published last. No nonatomic fallback.
                atomicMove(tempZip, zip);
                atomicMove(tempMetadata, metadata);
                syncDirectory(destination); // Persist publication before retention may remove older generations.
                committed = true;
                progress.accept("Backup complete: " + id);
                return new BackupRecord(id, provisional.createdAt(), label, zip, metadata,
                        provisional.compressedBytes(), total, manifest.size(), provisional.sha256());
            } finally {
                Files.deleteIfExists(tempZip);
                Files.deleteIfExists(tempMetadata);
                if (!committed) {
                    Files.deleteIfExists(metadata);
                    Files.deleteIfExists(zip);
                }
            }
        }
    }

    public BackupRecord createBackup(String label) throws IOException {
        return createBackup(label, NEVER_CANCEL, NO_PROGRESS);
    }

    /**
     * Returns only archives whose sidecar, whole-file hash, manifest and every entry verified.
     * An archive this instance already verified is not re-read while both of its files keep the
     * same file identity, size and timestamps; anything else is verified in full again. Use
     * revalidateBackups() to re-read every archive regardless.
     */
    public synchronized List<BackupRecord> listBackups() throws IOException {
        return listBackups(NEVER_CANCEL, NO_PROGRESS);
    }

    public synchronized List<BackupRecord> listBackups(BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        return catalogue(cancelled, progress, false);
    }

    /** Re-reads and re-hashes every archive, ignoring earlier verification results. */
    public synchronized List<BackupRecord> revalidateBackups(BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        return catalogue(cancelled, progress, true);
    }

    private List<BackupRecord> catalogue(BooleanSupplier cancelled, Consumer<String> progress, boolean revalidate) throws IOException {
        List<BackupRecord> good = new ArrayList<>();
        Set<String> present = new HashSet<>();
        Map<String, String> failures = new LinkedHashMap<>();
        checkedExistingDirectory(destination);
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(destination, "*.properties")) {
            for (Path path : paths) {
                checkCancelled(cancellation(cancelled));
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - ".properties".length());
                if (!ID.matcher(id).matches()) continue;
                present.add(id);
                try {
                    BackupRecord unchanged = revalidate ? null : unchangedVerified(id);
                    good.add(unchanged != null ? unchanged : verify(id, cancelled, progress));
                } catch (InterruptedIOException e) { throw e; }
                catch (IOException e) {
                    String reason = printable(String.valueOf(e.getMessage()));
                    failures.put(id, reason);
                    reporter(progress).accept("Ignoring incomplete or corrupt backup " + id + ": " + reason);
                }
            }
        }
        verifiedArchives.keySet().retainAll(present);
        rejected.clear();
        rejected.putAll(failures);
        good.sort(Comparator.comparing(BackupRecord::createdAt).reversed().thenComparing(BackupRecord::id));
        return List.copyOf(good);
    }

    /**
     * Sidecars the latest listing found but could not verify (ID to reason). They are never listed,
     * restored or pruned by retention, and stay on disk for the administrator to inspect.
     */
    public synchronized Map<String, String> rejectedBackups() { return Map.copyOf(rejected); }

    /** Always performs full verification, then remembers the result only if the files did not change meanwhile. */
    public synchronized BackupRecord verify(String id, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        validateId(id);
        checkedExistingDirectory(destination);
        Path zip = destination.resolve(id + ".zip");
        Path metadata = destination.resolve(id + ".properties");
        verifiedArchives.remove(id);
        Fingerprint before = fingerprint(zip, metadata);
        BackupRecord record = verifyArchive(zip, metadata, options, cancellation(cancelled), reporter(progress));
        Fingerprint after = fingerprint(zip, metadata);
        if (before != null && before.equals(after)) verifiedArchives.put(id, new VerifiedArchive(after, record));
        return record;
    }

    private BackupRecord unchangedVerified(String id) {
        VerifiedArchive known = verifiedArchives.get(id);
        if (known == null) return null;
        Fingerprint now = fingerprint(destination.resolve(id + ".zip"), destination.resolve(id + ".properties"));
        if (known.fingerprint().equals(now)) return known.record();
        verifiedArchives.remove(id);
        return null;
    }

    /** Null when either file is missing, unsafe or unreadable; a null fingerprint never matches. */
    private static Fingerprint fingerprint(Path zip, Path metadata) {
        try {
            BasicFileAttributes z = ensureRegularFile(zip);
            BasicFileAttributes m = ensureRegularFile(metadata);
            return new Fingerprint(z.fileKey(), z.size(), z.lastModifiedTime(), z.creationTime(),
                    m.fileKey(), m.size(), m.lastModifiedTime(), m.creationTime());
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    public BackupRecord verify(String id) throws IOException { return verify(id, NEVER_CANCEL, NO_PROGRESS); }

    /** Independent verifier for the offline restore helper; does not mutate any files. */
    public static BackupRecord verifyArchive(Path zip, Path metadata, BackupOptions limits,
                                            BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        Objects.requireNonNull(limits);
        cancelled = cancellation(cancelled);
        checkedAbsolutePath(zip);
        checkedAbsolutePath(metadata);
        BackupRecord record = readMetadata(zip, metadata);
        if (!zip.getFileName().toString().equals(record.id() + ".zip") ||
                !metadata.getFileName().toString().equals(record.id() + ".properties")) throw new IOException("Metadata/archive filename mismatch");
        validateArchive(record, limits, cancelled, reporter(progress));
        return record;
    }

    /** Read after verification; the caller must still hold exclusive control of the archive store. */
    public static List<ManifestEntry> readManifest(BackupRecord record) throws IOException {
        ensureRegularFile(record.zipPath());
        try (ZipFile zip = new ZipFile(record.zipPath().toFile(), StandardCharsets.UTF_8)) {
            ZipEntry manifest = zip.getEntry(MANIFEST_PATH);
            if (manifest == null || manifest.isDirectory()) throw new IOException("Missing manifest");
            return readManifest(zip, manifest, 1_000_000, NEVER_CANCEL);
        }
    }

    /**
     * Deletes a verified backup, including the final recovery point. Restore
     * reservations stay protected. The current verified store, not a UI catalogue
     * snapshot, determines whether the target is valid under the store lock.
     */
    public synchronized void delete(String id, Set<String> protectedIds) throws IOException {
        validateId(id);
        Set<String> protectedCopy = Set.copyOf(Objects.requireNonNull(protectedIds));
        if (protectedCopy.contains(id)) throw new BackupDeletionRefusedException(
                BackupDeletionRefusedException.Reason.RESTORE_PROTECTED, id);
        try (FileChannel channel = openLock(); FileLock ignored = acquireLock(channel)) {
            List<BackupRecord> good = listBackups();
            BackupRecord target = good.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new IOException("Backup is missing or corrupt: " + id));
            removeRecord(target);
            removeOrphanedArchives(protectedCopy);
        }
    }

    /** Retention may remove every unprotected archive; reserved targets can exceed the limits. */
    public synchronized List<BackupRecord> retain(int maxCount, long maxBytes, Set<String> protectedIds,
                                                 BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        return retain(maxCount, maxBytes, protectedIds, null, cancelled, progress);
    }

    /**
     * As above, but the just-created newestId is always treated as the newest record. Wall-clock
     * creation times can be skewed (clock steps, VM snapshots), and must never make retention
     * delete the recovery point that was just made before older generations.
     */
    public synchronized List<BackupRecord> retain(int maxCount, long maxBytes, Set<String> protectedIds, String newestId,
                                                 BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        if (maxCount < 1 || maxBytes < 1) throw new IOException("Retention limits must be positive");
        Set<String> protectedCopy = Set.copyOf(Objects.requireNonNull(protectedIds));
        cancelled = cancellation(cancelled);
        progress = reporter(progress);
        try (FileChannel channel = openLock(); FileLock ignored = acquireLock(channel)) {
            removeOrphanedArchives(protectedCopy);
            List<BackupRecord> good = new ArrayList<>(listBackups(cancelled, progress));
            for (int i = 0; newestId != null && i < good.size(); i++) {
                if (good.get(i).id().equals(newestId)) { good.addFirst(good.remove(i)); break; }
            }
            List<BackupRecord> removed = new ArrayList<>();
            long bytes = 0;
            for (BackupRecord r : good) bytes = boundedAdd(bytes, r.compressedBytes(), Long.MAX_VALUE, "Backup size overflow");
            int remaining = good.size();
            for (int i = good.size() - 1; i >= 0 && (remaining > maxCount || bytes > maxBytes); i--) {
                checkCancelled(cancelled);
                BackupRecord target = good.get(i);
                if (protectedCopy.contains(target.id())) continue;
                removeRecord(target);
                removed.add(target);
                remaining--;
                bytes -= target.compressedBytes();
                progress.accept("Retention deleted " + target.id());
            }
            return List.copyOf(removed);
        }
    }

    private void removeRecord(BackupRecord target) throws IOException {
        ensureRegularFile(target.zipPath());
        ensureRegularFile(target.metadataPath());
        verifiedArchives.remove(target.id());
        // Withdraw the commit marker first, so an interrupted delete is never listed.
        Files.delete(target.metadataPath());
        syncDirectory(destination);
        try {
            Files.delete(target.zipPath());
        } catch (IOException e) {
            throw new IOException("Backup " + target.id() + " was withdrawn, but its archive could not be deleted yet;"
                    + " a later delete or retention run removes it: " + printable(target.zipPath().toString()), e);
        }
        syncDirectory(destination);
    }

    /**
     * Caller holds the store lock. "ID.zip" without its "ID.properties" commit marker is left by a
     * crash or failed delete after the marker was withdrawn, or by a crash between the two
     * publication renames. It is never listed or counted, so it would otherwise leak a whole
     * archive of disk forever. Reserved IDs, symlinks and anything with a sidecar entry of any
     * kind are kept. Best effort: a locked file is retried by the next locked operation.
     */
    private void removeOrphanedArchives(Set<String> protectedIds) {
        List<Path> orphans = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(destination, "*.zip")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - ".zip".length());
                if (!ID.matcher(id).matches() || protectedIds.contains(id)) continue;
                if (Files.exists(destination.resolve(id + ".properties"), NOFOLLOW_LINKS)) continue;
                orphans.add(path);
            }
        } catch (IOException | DirectoryIteratorException unreadable) { return; }
        boolean removed = false;
        for (Path path : orphans) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attributes.isRegularFile() && !attributes.isSymbolicLink()) removed |= Files.deleteIfExists(path);
            } catch (IOException ignored) { /* Retried by the next locked operation. */ }
        }
        if (removed) {
            try { syncDirectory(destination); } catch (IOException ignored) { /* Durability of a cleanup is best effort. */ }
        }
    }

    /**
     * Caller holds the store lock. Only createBackup writes ".ID.zip.part" / ".ID.properties.part"
     * files, and always under that lock, so any found now were left by a crash or a forced JVM
     * stop and would otherwise occupy disk space forever. Best effort: never blocks a backup.
     */
    private void removeAbandonedPartFiles() {
        List<Path> abandoned = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(destination, ".*.part")) {
            for (Path path : paths) if (ABANDONED_PART.matcher(path.getFileName().toString()).matches()) abandoned.add(path);
        } catch (IOException | DirectoryIteratorException unreadable) { return; }
        for (Path path : abandoned) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attributes.isRegularFile() && !attributes.isSymbolicLink()) Files.deleteIfExists(path);
            } catch (IOException ignored) { /* Leave it for the next attempt; never fail the backup over cleanup. */ }
        }
    }

    /** Archives and sidecars contain server secrets: create them owner-only where POSIX permissions exist. */
    private static FileChannel createPrivateFile(Path path) throws IOException {
        Set<OpenOption> open = Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS);
        try {
            return FileChannel.open(path, open, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            // Windows and other non-POSIX stores keep the directory's inherited ACL.
            return FileChannel.open(path, open);
        }
    }

    /**
     * Owner-only: a shared (read) lock held by any account that can open the file would block every
     * exclusive store lock, and with it all backups, retention and deletes. Older versions created
     * the file with default permissions; tighten those in place, never replace the shared inode.
     */
    private FileChannel openLock() throws IOException {
        checkedExistingDirectory(destination);
        Path lockPath = destination.resolve(".vaultbackup.lock");
        if (Files.exists(lockPath, NOFOLLOW_LINKS)) ensureRegularFile(lockPath);
        FileChannel channel;
        try {
            channel = FileChannel.open(lockPath, Set.of(CREATE, WRITE, NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException nonPosix) {
            channel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS);
        }
        try {
            restrictToOwner(lockPath);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return channel;
    }

    private static void restrictToOwner(Path file) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        if (view == null) return;
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (ownerOnly.containsAll(permissions)) return;
        // Best effort: a lock that still works must never block backups over a permission change.
        try { view.setPermissions(ownerOnly); } catch (IOException | RuntimeException ignored) { }
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Backup store is busy in another process");
            return lock;
        } catch (OverlappingFileLockException e) {
            throw new IOException("Backup store is busy in another operation", e);
        }
    }

    /**
     * Includes apply to directories as excludes do: a directory whose path (or path + "/") matches
     * an include brings in its whole subtree, still subject to excludes. Any other directory is
     * archived only as an ancestor of an included entry, so writes in parts of the server the
     * administrator chose not to back up cannot abort a backup or consume the entry limit.
     */
    private List<Source> scan(BooleanSupplier cancelled) throws IOException {
        List<Source> sources = new ArrayList<>();
        Set<String> portableNames = new HashSet<>();
        Map<String, Source> ancestorCandidates = new HashMap<>();
        Deque<Boolean> includedDirectories = new ArrayDeque<>();
        BasicFileAttributes rootBefore = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancelled);
                if (isAutomaticallyExcluded(directory)) return FileVisitResult.SKIP_SUBTREE;
                String name = relative(directory);
                if (!name.isEmpty() && denied(name, true)) return FileVisitResult.SKIP_SUBTREE;
                if (attrs.isSymbolicLink() || !attrs.isDirectory()) throw new IOException("Unsafe directory: " + printable(directory.toString()));
                checkedAbsolutePath(directory);
                boolean included = name.isEmpty() ? includes.isEmpty() : insideIncludedDirectory() || includedDirectory(name);
                includedDirectories.push(included);
                if (!name.isEmpty()) {
                    Source source = new Source(directory, name, true, attrs);
                    if (included) addSource(sources, portableNames, source); else ancestorCandidates.put(name, source);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) throw exception;
                includedDirectories.pop();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancelled);
                if (isAutomaticallyExcluded(file)) return FileVisitResult.CONTINUE;
                String name = relative(file);
                if (denied(name, false) || !(insideIncludedDirectory() || included(name))) return FileVisitResult.CONTINUE;
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) throw new IOException("Symlinks and nonregular files are refused: " + printable(name));
                if (attrs.size() > options.maxEntryBytes()) throw new IOException("File exceeds entry byte limit: " + printable(name));
                addSource(sources, portableNames, new Source(file, name, false, attrs));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                // An unreadable directory is reported here without preVisitDirectory, so apply the
                // same policy first: excluded paths are skipped, anything that would be archived fails.
                if (isAutomaticallyExcluded(file)) return FileVisitResult.CONTINUE;
                String name = relative(file);
                if (!name.isEmpty() && excludedWhenUnreadable(file, name)) return FileVisitResult.CONTINUE;
                throw exception;
            }
            private boolean insideIncludedDirectory() { return !includedDirectories.isEmpty() && includedDirectories.peek(); }
            private boolean excludedWhenUnreadable(Path file, String name) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
                    if (attributes.isDirectory()) return denied(name, true);
                    return denied(name, false) || !(insideIncludedDirectory() || included(name));
                } catch (IOException | RuntimeException unknownType) {
                    return denied(name, true) && denied(name, false);
                }
            }
        });
        addAncestors(sources, portableNames, ancestorCandidates);
        BasicFileAttributes rootAfter = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!sameIdentity(rootBefore, rootAfter) || !rootBefore.lastModifiedTime().equals(rootAfter.lastModifiedTime())) throw new IOException("Server root changed while scanning");
        sources.sort(Comparator.comparing(Source::name));
        // The root is not an archive entry, but must be checked again after copy.
        sources.addFirst(new Source(root, "", true, rootBefore));
        return sources;
    }

    private void addSource(List<Source> sources, Set<String> portableNames, Source source) throws IOException {
        String name = source.name();
        validateEntryPath(name);
        requireLosslessName(source);
        if (name.equalsIgnoreCase(MANIFEST_PATH)) throw new IOException("Source uses reserved manifest name");
        if (!portableNames.add(portableKey(name))) throw new IOException("Case/Unicode-colliding archive paths: " + printable(name));
        if (sources.size() >= options.maxEntries()) throw new IOException("Backup entry count exceeds limit");
        sources.add(source);
    }

    /**
     * U+FFFD is how the JVM reports a file name it could not decode (a non-UTF-8 locale, or a
     * name that is not valid UTF-8). Archiving it would rename the file on restore, or make the
     * backup impossible to restore at all, so the backup is refused with the reason instead.
     */
    private void requireLosslessName(Source source) throws IOException {
        String name = source.name();
        String hint = " (file-name encoding " + System.getProperty("sun.jnu.encoding") + "; start the server with a UTF-8 locale such as"
                + " LANG=C.UTF-8, or exclude or rename the file): ";
        if (name.indexOf('\uFFFD') >= 0) throw new IOException("A file name cannot be stored without changing it" + hint + printable(name));
        if (WINDOWS || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return;
        try {
            // Byte-exact on Linux: the name must lead back to the very file that was walked.
            if (!root.resolve(name).equals(source.path())) throw new IOException("A file name does not round-trip" + hint + printable(name));
        } catch (InvalidPathException unrepresentable) {
            throw new IOException("A file name cannot be represented" + hint + printable(name), unrepresentable);
        }
    }

    /** Adds the not-themselves-included directories that contain an archived entry. */
    private void addAncestors(List<Source> sources, Set<String> portableNames, Map<String, Source> candidates) throws IOException {
        Set<String> covered = new HashSet<>();
        List<Source> ancestors = new ArrayList<>();
        for (Source source : sources) {
            String parent = source.name();
            int slash;
            while ((slash = parent.lastIndexOf('/')) > 0) {
                parent = parent.substring(0, slash);
                if (!covered.add(parent)) break; // Every ancestor above it was already handled.
                Source ancestor = candidates.remove(parent);
                if (ancestor != null) ancestors.add(ancestor);
            }
        }
        for (Source ancestor : ancestors) addSource(sources, portableNames, ancestor);
    }

    private boolean isAutomaticallyExcluded(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(destination) || absolute.startsWith(state);
    }
    private String relative(Path path) { return root.relativize(path).toString().replace(File.separatorChar, '/'); }
    private boolean included(String path) { return includes.isEmpty() || includes.stream().anyMatch(p -> p.matcher(path).matches()); }
    private boolean includedDirectory(String path) { return included(path) || included(path + "/"); }
    private boolean denied(String path, boolean directory) {
        return excludes.stream().anyMatch(p -> p.matcher(path).matches() || (directory && p.matcher(path + "/").matches()));
    }

    private static void ensureSourceUnchanged(Source source) throws IOException {
        checkedAbsolutePath(source.path());
        BasicFileAttributes now = Files.readAttributes(source.path(), BasicFileAttributes.class, NOFOLLOW_LINKS);
        BasicFileAttributes before = source.attributes();
        if (now.isSymbolicLink() || now.isDirectory() != source.directory() || (!source.directory() && !now.isRegularFile()) ||
                !sameIdentity(before, now) || !before.lastModifiedTime().equals(now.lastModifiedTime()) ||
                (!source.directory() && before.size() != now.size())) throw new IOException("Source changed during backup: " + source.path());
    }

    private static boolean sameIdentity(BasicFileAttributes a, BasicFileAttributes b) {
        return Objects.equals(a.fileKey(), b.fileKey());
    }

    private static void validateArchive(BackupRecord record, BackupOptions limits, BooleanSupplier cancelled,
                                        Consumer<String> progress) throws IOException {
        checkCancelled(cancelled);
        if (record.entryCount() > limits.maxEntries() || record.totalBytes() > limits.maxTotalBytes()) throw new IOException("Archive metadata exceeds configured limits");
        // Bound even the compressed input before hashing. Allow conservative deflate and ZIP overhead.
        long overhead = MAX_MANIFEST_BYTES + (long) limits.maxEntries() * 16_384L + 65_536L;
        long compressedLimit = saturatedAdd(saturatedAdd(limits.maxTotalBytes(), limits.maxTotalBytes() / 100L), overhead);
        if (record.compressedBytes() > compressedLimit) throw new IOException("Compressed archive exceeds configured limits");
        BasicFileAttributes before = ensureRegularFile(record.zipPath());
        if (before.size() != record.compressedBytes()) throw new IOException("Archive size does not match metadata");
        if (!MessageDigest.isEqual(hashFile(record.zipPath(), cancelled).getBytes(StandardCharsets.US_ASCII),
                record.sha256().getBytes(StandardCharsets.US_ASCII))) throw new IOException("Whole-archive SHA-256 mismatch");
        try (ZipFile zip = new ZipFile(record.zipPath().toFile(), StandardCharsets.UTF_8)) {
            Map<String, ZipEntry> entries = new LinkedHashMap<>();
            Set<String> portable = new HashSet<>();
            PortableTree tree = new PortableTree();
            ZipEntry manifestEntry = null;
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                checkCancelled(cancelled);
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                if (name.equals(MANIFEST_PATH)) {
                    if (manifestEntry != null || entry.isDirectory()) throw new IOException("Duplicate or invalid manifest");
                    manifestEntry = entry;
                    continue;
                }
                if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                validateEntryPath(name);
                if (name.equalsIgnoreCase(MANIFEST_PATH)) throw new IOException("Reserved manifest name collision");
                if (entries.putIfAbsent(name, entry) != null || !portable.add(portableKey(name))) throw new IOException("Duplicate/colliding ZIP entry: " + printable(name));
                // Also rejects any file that is an ancestor of another entry.
                tree.register(name, entry.isDirectory());
                if (entries.size() > limits.maxEntries()) throw new IOException("Archive entry count exceeds limit");
                if (entry.getSize() < 0 || entry.getCompressedSize() < 0 || entry.getSize() > limits.maxEntryBytes()) throw new IOException("Invalid or excessive ZIP entry size: " + printable(name));
                if (entry.isDirectory() && entry.getSize() != 0) throw new IOException("Directory contains data: " + printable(name));
                checkCompressionRatio(entry, limits.maxCompressionRatio());
            }
            if (manifestEntry == null) throw new IOException("Missing archive manifest");
            checkCompressionRatio(manifestEntry, limits.maxCompressionRatio());
            List<ManifestEntry> manifest = readManifest(zip, manifestEntry, limits.maxEntries(), cancelled);
            if (manifest.size() != entries.size() || manifest.size() != record.entryCount()) throw new IOException("Manifest entry count mismatch");
            long total = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            for (ManifestEntry expected : manifest) {
                checkCancelled(cancelled);
                ZipEntry entry = entries.remove(expected.path());
                if (entry == null || entry.isDirectory() != expected.directory() || entry.getSize() != expected.size()) throw new IOException("Manifest/ZIP disagreement: " + printable(expected.path()));
                progress.accept("Verifying " + expected.path());
                MessageDigest digest = sha256();
                CRC32 crc = new CRC32();
                long copied = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        copied = boundedAdd(copied, count, limits.maxEntryBytes(), "Expanded entry exceeds limit");
                        total = boundedAdd(total, count, limits.maxTotalBytes(), "Expanded archive exceeds limit");
                        if (copied > expected.size()) throw new IOException("Expanded entry exceeds manifest size");
                        digest.update(buffer, 0, count);
                        crc.update(buffer, 0, count);
                    }
                }
                if (copied != expected.size() || crc.getValue() != entry.getCrc()) throw new IOException("Entry size/CRC mismatch: " + printable(expected.path()));
                if (!expected.directory() && !hex(digest.digest()).equals(expected.sha256())) throw new IOException("Entry SHA-256 mismatch: " + printable(expected.path()));
            }
            if (!entries.isEmpty() || total != record.totalBytes()) throw new IOException("Archive total/manifest mismatch");
        } catch (IllegalArgumentException e) { throw new IOException("Malformed ZIP archive", e); }
        BasicFileAttributes after = ensureRegularFile(record.zipPath());
        if (!sameIdentity(before, after) || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("Archive changed during verification");
    }

    private static List<ManifestEntry> readManifest(ZipFile zip, ZipEntry manifest, int maxEntries,
                                                   BooleanSupplier cancelled) throws IOException {
        if (manifest.getSize() < 0 || manifest.getSize() > MAX_MANIFEST_BYTES) throw new IOException("Manifest exceeds size limit");
        List<ManifestEntry> result = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        try (ManifestInputStream input = new ManifestInputStream(zip.getInputStream(manifest), cancelled);
             Reader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            if (!MANIFEST_HEADER.equals(readManifestLine(reader))) throw new IOException("Unsupported manifest format");
            String line;
            while ((line = readManifestLine(reader)) != null) {
                checkCancelled(cancelled);
                if (result.size() >= maxEntries) throw new IOException("Manifest entry limit exceeded");
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4 || (!fields[0].equals("F") && !fields[0].equals("D"))) throw new IOException("Invalid manifest line");
                boolean directory = fields[0].equals("D");
                long size;
                String path;
                try {
                    if (!fields[1].matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid size");
                    size = Long.parseLong(fields[1]);
                    path = strictUtf8(Base64.getUrlDecoder().decode(fields[3]));
                } catch (IllegalArgumentException e) { throw new IOException("Invalid manifest encoding", e); }
                validateEntryPath(path);
                if (path.equalsIgnoreCase(MANIFEST_PATH) || !paths.add(portableKey(path))) throw new IOException("Reserved or duplicate manifest path");
                if (directory ? (size != 0 || !fields[2].equals("-")) : !HASH.matcher(fields[2]).matches()) throw new IOException("Invalid manifest digest");
                result.add(new ManifestEntry(path, directory, size, fields[2]));
            }
            if (input.count != manifest.getSize() || input.crc.getValue() != manifest.getCrc()) throw new IOException("Manifest size/CRC mismatch");
        }
        return List.copyOf(result);
    }

    private static String readManifestLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (line.length() >= 8192) throw new IOException("Manifest line exceeds size limit");
            line.append((char) ch);
        }
        if (!line.isEmpty()) throw new IOException("Manifest is truncated");
        return null;
    }

    private static final class ManifestInputStream extends FilterInputStream {
        private final BooleanSupplier cancelled;
        private final CRC32 crc = new CRC32();
        private long count;
        ManifestInputStream(InputStream input, BooleanSupplier cancelled) { super(input); this.cancelled = cancelled; }
        @Override public int read() throws IOException {
            checkCancelled(cancelled);
            int value = in.read();
            if (value != -1) { count = boundedAdd(count, 1, MAX_MANIFEST_BYTES, "Manifest exceeds size limit"); crc.update(value); }
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled(cancelled);
            int read = in.read(bytes, offset, length);
            if (read > 0) { count = boundedAdd(count, read, MAX_MANIFEST_BYTES, "Manifest exceeds size limit"); crc.update(bytes, offset, read); }
            return read;
        }
    }

    private static void writeManifest(OutputStream output, List<ManifestEntry> entries) throws IOException {
        long length = 0;
        byte[] header = (MANIFEST_HEADER + "\n").getBytes(StandardCharsets.UTF_8);
        output.write(header); length += header.length;
        for (ManifestEntry entry : entries) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(entry.path().getBytes(StandardCharsets.UTF_8));
            byte[] line = ((entry.directory() ? "D" : "F") + "\t" + entry.size() + "\t" + entry.sha256() + "\t" + encoded + "\n").getBytes(StandardCharsets.UTF_8);
            length = boundedAdd(length, line.length, MAX_MANIFEST_BYTES, "Manifest exceeds size limit");
            output.write(line);
        }
    }

    private static void writeMetadata(Path target, BackupRecord record) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("formatVersion", "1");
        metadata.setProperty("id", record.id());
        metadata.setProperty("createdAt", record.createdAt().toString());
        metadata.setProperty("label", record.label());
        metadata.setProperty("archiveBytes", Long.toString(record.compressedBytes()));
        metadata.setProperty("totalBytes", Long.toString(record.totalBytes()));
        metadata.setProperty("entryCount", Integer.toString(record.entryCount()));
        metadata.setProperty("sha256", record.sha256());
        try (FileChannel channel = createPrivateFile(target)) {
            OutputStream output = Channels.newOutputStream(channel);
            metadata.store(output, "VaultBackup Pro completed archive metadata v1");
            output.flush();
            channel.force(true);
        }
    }

    private static BackupRecord readMetadata(Path zip, Path path) throws IOException {
        BasicFileAttributes attributes = ensureRegularFile(path);
        if (attributes.size() > 16 * 1024) throw new IOException("Metadata is too large");
        Properties metadata = new Properties();
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS)))) {
            byte[] bytes = input.readNBytes(16 * 1024 + 1);
            if (bytes.length > 16 * 1024) throw new IOException("Metadata is too large");
            try { metadata.load(new ByteArrayInputStream(bytes)); }
            catch (IllegalArgumentException e) { throw new IOException("Malformed metadata properties", e); }
        }
        try {
            if (!"1".equals(metadata.getProperty("formatVersion"))) throw new IOException("Unsupported metadata format");
            String id = required(metadata, "id"); validateId(id);
            String digest = required(metadata, "sha256");
            if (!HASH.matcher(digest).matches()) throw new IOException("Invalid archive digest");
            long bytes = Long.parseLong(required(metadata, "archiveBytes"));
            long total = Long.parseLong(required(metadata, "totalBytes"));
            int count = Integer.parseInt(required(metadata, "entryCount"));
            String label = required(metadata, "label");
            if (bytes < 1 || total < 0 || count < 0 || !validLabel(label)) throw new IOException("Invalid archive metadata");
            return new BackupRecord(id, Instant.parse(required(metadata, "createdAt")), label,
                    zip.toAbsolutePath().normalize(), path.toAbsolutePath().normalize(), bytes, total, count, digest);
        } catch (RuntimeException e) { throw new IOException("Malformed metadata", e); }
    }

    private static String required(Properties metadata, String key) throws IOException {
        String value = metadata.getProperty(key);
        if (value == null) throw new IOException("Missing metadata field: " + key);
        return value;
    }

    /**
     * Strict archive paths: never absolute, traversal, control characters or undecodable names.
     * The Windows-only rules (device names, reserved characters, trailing dots or spaces) apply
     * where the host is Windows. Elsewhere such names are ordinary plugin data that must not abort
     * every backup; an archive holding them still fails closed in verification on a Windows host.
     */
    public static void validateEntryPath(String path) throws IOException { validateEntryPath(path, WINDOWS); }

    public static void validateEntryPath(String path, boolean windowsRules) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 4096 || path.getBytes(StandardCharsets.UTF_8).length > 4096 ||
                path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0 ||
                path.chars().anyMatch(c -> Character.isISOControl(c) || c == 0)) throw new IOException("Unsafe archive path: " + printable(path));
        if (hasUnpairedSurrogate(path)) throw new IOException("Archive path is not valid Unicode: " + printable(path));
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) throw new IOException("Unsafe archive path: " + printable(path));
            if (!windowsRules) continue;
            if (component.endsWith(".") || component.endsWith(" ") || component.chars().anyMatch(c -> "<>:\"|?*".indexOf(c) >= 0))
                throw new IOException("Name is not valid on Windows: " + printable(path));
            // Windows ignores trailing spaces before the extension: "CON .txt" still opens CON.
            String base = component.split("\\.", 2)[0].replaceAll(" +$", "").toUpperCase(Locale.ROOT);
            if (DEVICE.matcher(base).matches()) throw new IOException("Reserved device name in archive path: " + printable(path));
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { i++; continue; }
            if (Character.isSurrogate(c)) return true;
        }
        return false;
    }

    /** Labels are shown to administrators; reject anything that can forge or restyle a chat line. */
    public static boolean validLabel(String label) {
        if (label == null || label.length() > 200 || hasUnpairedSurrogate(label)) return false;
        return label.codePoints().noneMatch(c -> needsEscape(c) || Character.getType(c) == Character.PRIVATE_USE
                || Character.getType(c) == Character.UNASSIGNED);
    }

    private static boolean needsEscape(int c) {
        int type = Character.getType(c);
        return c == '\u00a7' || Character.isISOControl(c) || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT || type == Character.SURROGATE;
    }

    /**
     * Archive-derived text for exception and log messages: control, format and line-separator
     * characters (and the legacy formatting prefix) become visible escapes, so a crafted name can
     * neither forge log lines nor inject terminal sequences.
     */
    public static String printable(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(Math.min(value.length(), 512) + 16);
        int count = 0;
        for (int i = 0; i < value.length(); ) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            if (++count > 512) { out.append("..."); break; }
            if (needsEscape(c)) out.append(String.format(Locale.ROOT, "\\u%04X", c));
            else out.appendCodePoint(c);
        }
        return out.toString();
    }

    public static void validateId(String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) throw new IOException("Invalid backup ID");
    }

    /**
     * Linear-memory portable path bookkeeping: one node per distinct (parent, portable component).
     * Storing every full ancestor prefix instead is quadratic in path depth, which lets a small
     * crafted archive of deep paths exhaust the heap during routine catalogue verification.
     */
    private static final class PortableTree {
        private final Map<String, Integer> nodes = new HashMap<>();
        private final List<String> spellings = new ArrayList<>();
        private final BitSet files = new BitSet();
        private final BitSet directories = new BitSet();

        void register(String path, boolean directory) throws IOException {
            String[] components = path.split("/");
            int parent = 0;
            for (int i = 0; i < components.length; i++) {
                String key = parent + "/" + portableKey(components[i]);
                Integer node = nodes.get(key);
                if (node == null) {
                    spellings.add(components[i]);
                    node = spellings.size();
                    nodes.put(key, node);
                } else if (!spellings.get(node - 1).equals(components[i])) {
                    throw new IOException("Case/Unicode-alias ancestor path: " + printable(path));
                }
                if (i < components.length - 1 || directory) {
                    if (files.get(node)) throw new IOException("File/directory ancestor conflict: " + printable(path));
                    directories.set(node);
                } else {
                    if (directories.get(node)) throw new IOException("File/directory ancestor conflict: " + printable(path));
                    files.set(node);
                }
                parent = node;
            }
        }
    }

    private static String portableKey(String path) { return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    private static void checkCompressionRatio(ZipEntry entry, double maximum) throws IOException {
        if (entry.getSize() < 0 || entry.getCompressedSize() < 0 ||
                (entry.getSize() > 0 && entry.getSize() / (double) Math.max(1L, entry.getCompressedSize()) > maximum)) throw new IOException("ZIP compression ratio limit exceeded: " + entry.getName());
    }
    private static long saturatedAdd(long value, long addition) {
        return value > Long.MAX_VALUE - addition ? Long.MAX_VALUE : value + addition;
    }
    private static long boundedAdd(long value, long addition, long maximum, String error) throws IOException {
        if (addition < 0 || value > maximum - addition) throw new IOException(error);
        return value + addition;
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static String hashFile(Path file, BooleanSupplier cancelled) throws IOException {
        ensureRegularFile(file);
        MessageDigest digest = sha256();
        try (InputStream input = Channels.newInputStream(Files.newByteChannel(file, Set.of(READ, NOFOLLOW_LINKS)))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }
    private static String strictUtf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8", e); }
    }
    private static BasicFileAttributes ensureRegularFile(Path path) throws IOException {
        checkedAbsolutePath(path);
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("Not a regular file: " + path);
        return attrs;
    }
    private static Path checkedAbsolutePath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symlink path components are refused: " + cursor);
        }
        return absolute;
    }
    private static Path checkedExistingDirectory(Path path) throws IOException {
        Path absolute = checkedAbsolutePath(path);
        if (!Files.isDirectory(absolute, NOFOLLOW_LINKS)) throw new IOException("Directory does not exist: " + absolute);
        return absolute.toRealPath(NOFOLLOW_LINKS);
    }
    private static Path prepareDirectory(Path path) throws IOException {
        Path absolute = checkedAbsolutePath(path);
        try { Files.createDirectories(absolute, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))); }
        catch (UnsupportedOperationException nonPosix) { Files.createDirectories(absolute); }
        return checkedExistingDirectory(absolute);
    }
    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ)) { channel.force(true); }
        catch (FileSystemException unsupported) {
            // Java/Windows commonly cannot open directories for force(). File bytes are
            // forced, but power-loss durability of directory entries is platform-dependent.
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) throw unsupported;
        }
    }
    private static void atomicMove(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { throw new IOException("Backup store must support atomic file moves", e); }
    }
    private static BooleanSupplier cancellation(BooleanSupplier cancelled) { return cancelled == null ? NEVER_CANCEL : cancelled; }
    private static Consumer<String> reporter(Consumer<String> progress) { return progress == null ? NO_PROGRESS : progress; }
    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new InterruptedIOException("Backup operation cancelled");
    }

    /** Portable glob: single star matches a segment; double star matches any path. */
    private static List<Pattern> compileGlobs(List<String> patterns) throws IOException {
        List<Pattern> compiled = new ArrayList<>();
        for (String glob : patterns) {
            if (glob == null || glob.isBlank() || glob.startsWith("/") || glob.indexOf('\\') >= 0 || glob.indexOf(':') >= 0 ||
                    Arrays.asList(glob.split("/", -1)).contains("..")) throw new IOException("Invalid relative glob: " + glob);
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char ch = glob.charAt(i);
                if (ch == '*') {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { i++; regex.append("(?:.*/)?"); }
                        else regex.append(".*");
                    } else regex.append("[^/]*");
                } else if (ch == '?') regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(ch)));
            }
            regex.append('$');
            compiled.add(Pattern.compile(regex.toString()));
        }
        return List.copyOf(compiled);
    }
}
