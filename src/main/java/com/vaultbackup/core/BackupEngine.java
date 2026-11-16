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
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.*;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/**
 * Standard-library-only archive storage with a final atomic sidecar commit marker.
 *
 * A running server keeps writing: player data, level.dat and plugin files are replaced or
 * rewritten at any time, even while world autosave is off. Every file is therefore copied as
 * one self-consistent version (the copy is repeated while the file changes underneath it), files
 * that disappear are skipped, and a file that never settles is archived as last read and counted
 * in {@link BackupRecord#unstableEntries()}. The archive always matches its own manifest. This is
 * not a filesystem snapshot: files are not captured at one common instant.
 */
public final class BackupEngine {
    public static final String MANIFEST_PATH = ".vaultbackup-manifest";
    public static final String MANIFEST_HEADER = "VAULTBACKUP-MANIFEST\t1";
    public static final long MAX_MANIFEST_BYTES = 128L * 1024 * 1024;
    private static final int BUFFER_SIZE = 128 * 1024;
    /** Files up to this size are read completely before being archived, so an unstable read can be repeated. */
    private static final int STABLE_READ_BYTES = 8 * 1024 * 1024;
    /** Reads of one changing file before it is archived as last read and reported. */
    private static final int STABLE_READ_ATTEMPTS = 4;
    /** A file replaced by "rename old away, rename new in" is briefly absent; look again after these pauses. */
    private static final long[] REAPPEAR_WAIT_MILLIS = {25, 75};
    /** All such pauses of one backup together; a mass deletion must not stall the backup while autosave is off. */
    private static final long REAPPEAR_BUDGET_MILLIS = 2_000;
    /** Free space the backup store must keep, so a growing archive never fills a disk the server also writes to. */
    private static final long SPACE_RESERVE_BYTES = 64L * 1024 * 1024;
    /** Free space is looked at again each time this much has been written. */
    private static final long SPACE_CHECK_INTERVAL_BYTES = 32L * 1024 * 1024;
    /** Problems of one scan that are named individually in the refusal. */
    private static final int MAX_REPORTED_PROBLEMS = 50;
    /** Bytes one central-directory record may need beyond its name, generously. */
    private static final long DIRECTORY_RECORD_OVERHEAD = 128;
    private static final Pattern ID = Pattern.compile("[0-9]{8}T[0-9]{9}Z-[a-f0-9]{12}");
    /** Exactly the temporary names this class uses; nothing else in the store is ever swept. */
    private static final Pattern ABANDONED_PART = Pattern.compile("\\.([0-9]{8}T[0-9]{9}Z-[a-f0-9]{12})\\.(zip|properties)\\.part");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final BooleanSupplier NEVER_CANCEL = () -> false;
    private static final Consumer<String> NO_PROGRESS = value -> { };
    /**
     * One lock channel per store for the life of the JVM, never closed: on POSIX, closing any
     * descriptor of a file drops every lock the process holds on it, including another engine's.
     */
    private static final Map<Path, LockFile> LOCK_FILES = new ConcurrentHashMap<>();
    private final BackupOptions options;
    private final Path root;
    private final Path destination;
    private final Path state;
    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    public record ManifestEntry(String path, boolean directory, long size, String sha256) { }
    private record Source(Path path, String name, boolean directory, BasicFileAttributes attributes) { }
    private record LockFile(FileChannel channel, Object fileKey) { }
    /** Identity, size and timestamps of an archive/sidecar pair at the moment it fully verified. */
    private record Fingerprint(Object zipKey, long zipSize, FileTime zipModified, FileTime zipCreated,
                               Object metadataKey, long metadataSize, FileTime metadataModified, FileTime metadataCreated) { }
    private record VerifiedArchive(Fingerprint fingerprint, BackupRecord record) { }

    /** The source tree contains paths that cannot be archived. Every one found by the scan is listed. */
    /**
     * The progress value reported once everything has been read from the server tree. From here on
     * createBackup only re-reads its own archive, so the server may write its files again.
     */
    public static final String PHASE_VERIFYING_NEW_ARCHIVE = "Verifying new archive";

    public static final class SourceRefusedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final List<String> problems;
        private final int total;
        SourceRefusedException(List<String> problems, int total) {
            super(total + " path(s) cannot be backed up; rename them or add them to backup.exclude:\n - " + String.join("\n - ", problems)
                    + (total > problems.size() ? "\n - ... and " + (total - problems.size()) + " more" : ""));
            this.problems = List.copyOf(problems);
            this.total = total;
        }
        /** Up to the first fifty problems, each "path: reason". */
        public List<String> problems() { return problems; }
        public int total() { return total; }
    }

    /** The backup store does not have room for the next archive. Nothing, or only a removed partial file, was written. */
    public static final class InsufficientSpaceException extends IOException {
        private static final long serialVersionUID = 1L;
        private final long neededBytes, availableBytes;
        InsufficientSpaceException(long neededBytes, long availableBytes) {
            super("Not enough free space in the backup directory: about " + neededBytes + " bytes needed, " + availableBytes + " available");
            this.neededBytes = neededBytes;
            this.availableBytes = availableBytes;
        }
        public long neededBytes() { return neededBytes; }
        public long availableBytes() { return availableBytes; }
    }

    /**
     * Archives this instance fully verified, keyed by ID. A listing reuses an entry only while
     * both files are provably the same, unchanged files; any difference forces full verification.
     * Explicit verify() and revalidateBackups() always re-read every byte. Guarded by this.
     */
    private final Map<String, VerifiedArchive> verifiedArchives = new HashMap<>();
    /** Archives the last listing found but could not verify, with the reason. Guarded by this. */
    private Map<String, String> rejected = Map.of();
    /**
     * Test seam only: called with the archive path after a file's bytes were read and before it is
     * looked at again, i.e. at the one point where a concurrent writer makes the read unstable.
     */
    volatile Consumer<String> afterReadProbe;
    /** Test seam only: replaces the free space reported by the backup store. */
    volatile java.util.function.LongSupplier usableSpaceProbe;

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
    }

    public BackupOptions options() { return options; }
    public Path serverRoot() { return root; }
    public Path backupDirectory() { return destination; }

    /**
     * Patterns that use glob syntax this engine does not implement. They are matched literally and
     * therefore usually match nothing, which callers should tell the administrator.
     */
    public static List<String> unsupportedGlobSyntax(List<String> patterns) {
        List<String> suspicious = new ArrayList<>();
        for (String glob : patterns) {
            if (glob != null && (glob.startsWith("!") || glob.startsWith("./") || glob.indexOf('{') >= 0 || glob.indexOf('}') >= 0
                    || glob.indexOf('[') >= 0 || glob.indexOf(']') >= 0)) suspicious.add(glob);
        }
        return List.copyOf(suspicious);
    }

    public synchronized BackupRecord createBackup(String label, BooleanSupplier cancelled,
                                                    Consumer<String> progress) throws IOException {
        return createBackup(label, cancelled, progress, null);
    }

    /**
     * @param unstable receives the archive path of every file that kept changing while it was copied.
     *                 Such a file is archived exactly as last read; the count is stored with the backup.
     * @throws SourceRefusedException when the tree holds paths that cannot be archived
     * @throws InsufficientSpaceException when the store lacks room; the store is left as it was
     */
    public synchronized BackupRecord createBackup(String label, BooleanSupplier cancelled, Consumer<String> progress,
                                                    Consumer<String> unstable) throws IOException {
        cancelled = cancellation(cancelled);
        progress = reporter(progress);
        unstable = reporter(unstable);
        if (label == null) label = "";
        if (label.length() > 200 || label.chars().anyMatch(Character::isISOControl)) throw new IOException("Label must be at most 200 printable characters");
        checkCancelled(cancelled);
        checkedExistingDirectory(root);
        checkedExistingDirectory(destination);
        try (FileLock ignored = lockStore()) {
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
                long scannedBytes = 0, manifestBytes = MANIFEST_HEADER.length() + 1;
                int files = 0;
                for (Source source : sources) {
                    if (!source.directory()) { files++; scannedBytes = saturatedAdd(scannedBytes, source.attributes().size()); }
                    // Type, tab, decimal size, tab, digest, tab, unpadded base64 of the UTF-8 name, newline.
                    manifestBytes += 90 + (source.name().getBytes(StandardCharsets.UTF_8).length * 4L + 2) / 3;
                }
                // A pattern that selects nothing would otherwise publish an empty archive, and retention
                // would then replace real backups with it.
                if (files == 0) throw new IOException("No files were selected for the backup; check backup.include and backup.exclude");
                if (manifestBytes > MAX_MANIFEST_BYTES) throw new IOException("Backup has too many or too long paths for one archive manifest");
                FileStore store = Files.getFileStore(destination);
                requireSpace(store, estimateArchiveBytes(scannedBytes));
                List<ManifestEntry> manifest = new ArrayList<>(sources.size());
                long total = 0;
                int unstableEntries = 0;
                long[] reappearBudget = {REAPPEAR_BUDGET_MILLIS};
                MessageDigest archiveDigest = sha256();
                try (FileChannel channel = createPrivateFile(tempZip);
                     ZipOutputStream output = new ZipOutputStream(new DigestOutputStream(
                             new BufferedOutputStream(Channels.newOutputStream(channel), BUFFER_SIZE), archiveDigest), StandardCharsets.UTF_8)) {
                    output.setLevel(options.compressionLevel());
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long nextSpaceCheck = SPACE_CHECK_INTERVAL_BYTES;
                    for (Source source : sources) {
                        checkCancelled(cancelled);
                        if (source.directory()) {
                            if (!stillDirectory(source)) continue; // Removed since the scan; its former contents are skipped below.
                            ZipEntry entry = new ZipEntry(source.name() + "/");
                            entry.setLastModifiedTime(source.attributes().lastModifiedTime());
                            output.putNextEntry(entry);
                            output.closeEntry();
                            manifest.add(new ManifestEntry(source.name(), true, 0, "-"));
                            continue;
                        }
                        progress.accept("Backing up " + source.name());
                        Copied copied = copyFile(source, output, buffer, total, store, reappearBudget, cancelled);
                        if (copied == null) continue; // Deleted since the scan: a legitimate state of a live tree.
                        total = boundedAdd(total, copied.size(), options.maxTotalBytes(), "Backup exceeds total byte limit");
                        manifest.add(new ManifestEntry(source.name(), false, copied.size(), copied.sha256()));
                        if (!copied.stable()) {
                            unstableEntries++;
                            unstable.accept(source.name());
                        }
                        if (total >= nextSpaceCheck) {
                            requireReserve(store);
                            nextSpaceCheck = total + SPACE_CHECK_INTERVAL_BYTES;
                        }
                    }
                    ZipEntry manifestEntry = new ZipEntry(MANIFEST_PATH);
                    output.putNextEntry(manifestEntry);
                    long manifestSize = writeManifest(output, manifest);
                    output.closeEntry();
                    requireVerifiableRatio(manifestEntry, manifestSize, MANIFEST_PATH);
                    output.finish();
                    output.flush();
                    channel.force(true);
                }
                checkCancelled(cancelled);
                // The digest covers exactly the bytes handed to the file; validateArchive re-reads the
                // file from disk below, so a write that did not reach storage intact cannot be published.
                BackupRecord provisional = new BackupRecord(id, Instant.now(), label, tempZip, tempMetadata,
                        Files.size(tempZip), total, manifest.size(), hex(archiveDigest.digest()), unstableEntries);
                writeMetadata(tempMetadata, provisional);
                progress.accept(PHASE_VERIFYING_NEW_ARCHIVE);
                validateArchive(provisional, options, cancelled, progress);
                checkCancelled(cancelled);
                // Metadata is the commit marker and is published last. No nonatomic fallback.
                atomicMove(tempZip, zip);
                atomicMove(tempMetadata, metadata);
                syncDirectory(destination); // Persist publication before retention may remove older generations.
                committed = true;
                BackupRecord record = new BackupRecord(id, provisional.createdAt(), label, zip, metadata,
                        provisional.compressedBytes(), total, manifest.size(), provisional.sha256(), unstableEntries);
                // Fully verified a moment ago under its temporary name; renaming changes neither file.
                Fingerprint published = fingerprint(zip, metadata);
                if (published != null) verifiedArchives.put(id, new VerifiedArchive(published, record));
                progress.accept("Backup complete: " + id);
                return record;
            } finally {
                if (!committed) {
                    // The zip may already carry its final name when the sidecar's publication failed.
                    deleteQuietly(metadata);
                    deleteQuietly(zip);
                }
                deleteQuietly(tempZip);
                deleteQuietly(tempMetadata);
            }
        }
    }

    public BackupRecord createBackup(String label) throws IOException {
        return createBackup(label, NEVER_CANCEL, NO_PROGRESS);
    }

    /** Cleanup must not replace the failure that led to it; a leftover is swept before the next backup. */
    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException | RuntimeException leftover) { /* removeAbandonedPartFiles handles .part files later */ }
    }

    /**
     * Size the new archive is likely to have, from how the most recent archive of this store
     * compressed. Without one, half the source size: an estimate that is too high would refuse
     * backups that fit. The reserve check during writing is the hard limit.
     */
    private long estimateArchiveBytes(long scannedBytes) {
        BackupRecord latest = null;
        for (VerifiedArchive known : verifiedArchives.values())
            if (latest == null || known.record().createdAt().isAfter(latest.createdAt())) latest = known.record();
        if (latest == null || latest.totalBytes() <= 0) return scannedBytes / 2;
        double ratio = Math.min(1.0, latest.compressedBytes() / (double) latest.totalBytes());
        return (long) Math.min((double) Long.MAX_VALUE, scannedBytes * ratio * 1.05);
    }

    private long usableSpace(FileStore store) throws IOException {
        java.util.function.LongSupplier probe = usableSpaceProbe;
        return probe != null ? probe.getAsLong() : store.getUsableSpace();
    }

    private void requireSpace(FileStore store, long archiveBytes) throws IOException {
        long available = usableSpace(store);
        long needed = saturatedAdd(archiveBytes, SPACE_RESERVE_BYTES);
        if (available < needed) throw new InsufficientSpaceException(needed, available);
    }

    private void requireReserve(FileStore store) throws IOException {
        long available = usableSpace(store);
        if (available < SPACE_RESERVE_BYTES) throw new InsufficientSpaceException(SPACE_RESERVE_BYTES, available);
    }

    /**
     * Verification refuses entries that expand by more than the configured ratio. A source file
     * that compresses better than that would produce an archive this engine rejects afterwards,
     * so it is reported while the reason is still known.
     */
    private void requireVerifiableRatio(ZipEntry entry, long size, String name) throws IOException {
        long compressed = entry.getCompressedSize();
        if (size > 0 && compressed >= 0 && size / (double) Math.max(1L, compressed) > options.maxCompressionRatio())
            throw new IOException("File compresses beyond limits.max-compression-ratio and could not be verified afterwards (raise the limit or exclude it): " + name);
    }

    /** One archived file: the bytes written, their digest, and whether they are one settled version of the file. */
    private record Copied(long size, String sha256, boolean stable) { }

    /** False when the directory no longer exists. Anything else in its place is refused. */
    private boolean stillDirectory(Source source) throws IOException {
        requireNoSymlinkBelowRoot(source);
        BasicFileAttributes now = attributesOrNull(source.path());
        if (now == null) return false;
        if (now.isSymbolicLink() || !now.isDirectory()) throw new IOException("Source directory was replaced by another file type: " + source.name());
        return true;
    }

    /**
     * No directory on the way to the entry may have become a symlink since the scan refused
     * symlinks: opening the entry would then read outside the server root. The entry itself is
     * opened without following links.
     */
    private void requireNoSymlinkBelowRoot(Source source) throws IOException {
        Path cursor = root;
        Path relative = root.relativize(source.path());
        int parents = relative.getNameCount() - 1;
        for (int i = 0; i < parents; i++) {
            cursor = cursor.resolve(relative.getName(i));
            if (Files.isSymbolicLink(cursor)) throw new IOException("A directory became a symlink during the backup: " + relative(cursor));
        }
    }

    /** Current attributes without following links; null when nothing exists at the path any more. */
    private static BasicFileAttributes attributesOrNull(Path path) throws IOException {
        try { return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS); }
        catch (NoSuchFileException gone) { return null; }
    }

    /** Null when the file is gone. A symlink or special file that took its place is refused, never followed. */
    private BasicFileAttributes regularOrNull(Source source) throws IOException {
        requireNoSymlinkBelowRoot(source);
        BasicFileAttributes now = attributesOrNull(source.path());
        if (now != null && (now.isSymbolicLink() || !now.isRegularFile()))
            throw new IOException("Source file was replaced by a symlink or special file: " + source.name());
        return now;
    }

    private void probeAfterRead(Source source) {
        Consumer<String> probe = afterReadProbe;
        if (probe != null) probe.accept(source.name());
    }

    private static boolean sameVersion(BasicFileAttributes a, BasicFileAttributes b) {
        return sameIdentity(a, b) && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime());
    }

    private static void pause(long millis, BooleanSupplier cancelled) throws InterruptedIOException {
        checkCancelled(cancelled);
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Backup operation cancelled");
        }
        checkCancelled(cancelled);
    }

    /**
     * Looks for a file that is absent right now. Only when its directory still exists can it be in
     * the gap of a two-step replacement, and only then is it worth a short wait.
     */
    private BasicFileAttributes awaitReappearance(Source source, long[] budgetMillis, BooleanSupplier cancelled) throws IOException {
        for (long wait : REAPPEAR_WAIT_MILLIS) {
            if (budgetMillis[0] < wait || attributesOrNull(source.path().getParent()) == null) return null;
            budgetMillis[0] -= wait;
            pause(wait, cancelled);
            BasicFileAttributes now = regularOrNull(source);
            if (now != null) return now;
        }
        return null;
    }

    /**
     * Archives one file as a single self-consistent version, or returns null if it no longer exists.
     * A file being replaced (written elsewhere and renamed into place) is read from whichever
     * complete version is open; a file rewritten in place is read again until two looks agree.
     */
    private Copied copyFile(Source source, ZipOutputStream output, byte[] buffer, long totalSoFar, FileStore store,
                            long[] reappearBudget, BooleanSupplier cancelled) throws IOException {
        byte[] lastRead = null;
        BasicFileAttributes lastSeen = null;
        for (int attempt = 1; attempt <= STABLE_READ_ATTEMPTS; attempt++) {
            if (attempt > 1) pause(150L * (attempt - 1), cancelled);
            BasicFileAttributes before = regularOrNull(source);
            if (before == null) before = awaitReappearance(source, reappearBudget, cancelled);
            if (before == null) return null; // Deleted meanwhile; an earlier partial look at it is not archived.
            if (before.size() > options.maxEntryBytes()) throw new IOException("File exceeds entry byte limit: " + source.name());
            if (before.size() > STABLE_READ_BYTES) return streamLargeFile(source, before, output, buffer, totalSoFar, store, cancelled);
            byte[] bytes;
            try (InputStream input = Channels.newInputStream(Files.newByteChannel(source.path(), Set.of(READ, NOFOLLOW_LINKS)))) {
                bytes = input.readNBytes(STABLE_READ_BYTES + 1);
            } catch (NoSuchFileException replacedMeanwhile) { continue; }
            if (bytes.length > STABLE_READ_BYTES) continue; // Grew past the in-memory bound while open; look again.
            lastRead = bytes;
            lastSeen = before;
            probeAfterRead(source);
            BasicFileAttributes after = regularOrNull(source);
            if (after != null && sameVersion(before, after) && bytes.length == before.size())
                return writeBuffered(source, before, bytes, output, totalSoFar, true);
        }
        if (lastRead == null) {
            // Every look found it absent for a moment or grown past the in-memory bound.
            BasicFileAttributes now = regularOrNull(source);
            if (now == null) return null;
            return streamLargeFile(source, now, output, buffer, totalSoFar, store, cancelled);
        }
        return writeBuffered(source, lastSeen, lastRead, output, totalSoFar, false);
    }

    private Copied writeBuffered(Source source, BasicFileAttributes attributes, byte[] bytes, ZipOutputStream output,
                                 long totalSoFar, boolean stable) throws IOException {
        boundedAdd(0, bytes.length, options.maxEntryBytes(), "Entry exceeds byte limit");
        boundedAdd(totalSoFar, bytes.length, options.maxTotalBytes(), "Backup exceeds total byte limit");
        ZipEntry entry = new ZipEntry(source.name());
        entry.setLastModifiedTime(attributes.lastModifiedTime());
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
        requireVerifiableRatio(entry, bytes.length, source.name());
        return new Copied(bytes.length, hex(sha256().digest(bytes)), stable);
    }

    /**
     * Too large to hold in memory, so it cannot be read twice into the same ZIP entry. The bytes are
     * consistent when the file is the same version before and after, or when it was replaced by
     * another file while the complete earlier version stayed open. Otherwise it is reported.
     */
    private Copied streamLargeFile(Source source, BasicFileAttributes before, ZipOutputStream output, byte[] buffer,
                                   long totalSoFar, FileStore store, BooleanSupplier cancelled) throws IOException {
        FileChannel channel;
        try { channel = FileChannel.open(source.path(), Set.of(READ, NOFOLLOW_LINKS)); }
        catch (NoSuchFileException gone) { return null; }
        try (channel) {
            ZipEntry entry = new ZipEntry(source.name());
            entry.setLastModifiedTime(before.lastModifiedTime());
            output.putNextEntry(entry);
            MessageDigest digest = sha256();
            long copied = 0, nextSpaceCheck = SPACE_CHECK_INTERVAL_BYTES;
            InputStream input = Channels.newInputStream(channel);
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                copied = boundedAdd(copied, count, options.maxEntryBytes(), "Entry exceeds byte limit");
                boundedAdd(totalSoFar, copied, options.maxTotalBytes(), "Backup exceeds total byte limit");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                if (copied >= nextSpaceCheck) {
                    requireReserve(store);
                    nextSpaceCheck = copied + SPACE_CHECK_INTERVAL_BYTES;
                }
            }
            long openSize = channel.size();
            output.closeEntry();
            requireVerifiableRatio(entry, copied, source.name());
            probeAfterRead(source);
            BasicFileAttributes after = regularOrNull(source);
            boolean unchanged = after != null && sameVersion(before, after) && copied == before.size();
            // Replaced by rename (or deleted) while open: the open file is the complete earlier version
            // as long as nothing changed its length after it was looked at.
            boolean replaced = (after == null || !sameIdentity(before, after)) && copied == before.size() && openSize == before.size();
            return new Copied(copied, hex(digest.digest()), unchanged || replaced);
        }
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

    /**
     * Archives the most recent listing found in the store but did not return, each with the reason:
     * corrupt or incomplete files, or an archive beyond the configured limits. They occupy space, are
     * not counted by retention, and can be removed with {@link #delete}.
     */
    public synchronized Map<String, String> rejectedArchives() { return rejected; }

    private List<BackupRecord> catalogue(BooleanSupplier cancelled, Consumer<String> progress, boolean revalidate) throws IOException {
        List<BackupRecord> good = new ArrayList<>();
        Set<String> present = new HashSet<>();
        Map<String, String> refused = new TreeMap<>();
        checkedExistingDirectory(destination);
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(destination)) {
            Set<String> zips = new HashSet<>();
            List<String> sidecars = new ArrayList<>();
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (name.endsWith(".properties") && ID.matcher(name.substring(0, name.length() - ".properties".length())).matches())
                    sidecars.add(name.substring(0, name.length() - ".properties".length()));
                else if (name.endsWith(".zip") && ID.matcher(name.substring(0, name.length() - ".zip".length())).matches())
                    zips.add(name.substring(0, name.length() - ".zip".length()));
            }
            for (String id : sidecars) {
                checkCancelled(cancellation(cancelled));
                present.add(id);
                zips.remove(id);
                try {
                    BackupRecord unchanged = revalidate ? null : unchangedVerified(id);
                    good.add(unchanged != null ? unchanged : verify(id, cancelled, progress));
                } catch (InterruptedIOException e) { throw e; }
                catch (IOException e) { refused.put(id, String.valueOf(e.getMessage())); }
            }
            // An archive without its sidecar was never committed, or its deletion was interrupted.
            for (String id : zips) refused.put(id, "Archive has no metadata file (never completed, or its deletion was interrupted)");
        }
        verifiedArchives.keySet().retainAll(present);
        rejected = Collections.unmodifiableMap(refused);
        good.sort(Comparator.comparing(BackupRecord::createdAt).reversed().thenComparing(BackupRecord::id));
        return List.copyOf(good);
    }

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
        ZipDirectory.requireWithin(record.zipPath(), 1_000_001L, MAX_MANIFEST_BYTES + 1_000_001L * DIRECTORY_RECORD_OVERHEAD);
        try (ZipFile zip = new ZipFile(record.zipPath().toFile(), StandardCharsets.UTF_8)) {
            ZipEntry manifest = zip.getEntry(MANIFEST_PATH);
            if (manifest == null || manifest.isDirectory()) throw new IOException("Missing manifest");
            return readManifest(zip, manifest, 1_000_000, NEVER_CANCEL);
        }
    }

    /**
     * Deletes the named backup, including the final recovery point and including an archive that
     * no longer verifies (the administrator named it; it could never be restored anyway). Restore
     * reservations stay protected. Nothing else in the store is read.
     */
    public synchronized void delete(String id, Set<String> protectedIds) throws IOException {
        validateId(id);
        Set<String> protectedCopy = Set.copyOf(Objects.requireNonNull(protectedIds));
        if (protectedCopy.contains(id)) throw new BackupDeletionRefusedException(
                BackupDeletionRefusedException.Reason.RESTORE_PROTECTED, id);
        try (FileLock ignored = lockStore()) {
            removeAbandonedPartFiles();
            Path zip = destination.resolve(id + ".zip"), metadata = destination.resolve(id + ".properties");
            boolean hasZip = Files.exists(zip, NOFOLLOW_LINKS), hasMetadata = Files.exists(metadata, NOFOLLOW_LINKS);
            if (!hasZip && !hasMetadata) throw new IOException("Backup does not exist: " + id);
            if (hasZip) ensureRegularFile(zip);
            if (hasMetadata) ensureRegularFile(metadata);
            withdraw(id);
        }
    }

    /** Retention may remove every unprotected archive; reserved targets can exceed the limits. */
    public synchronized List<BackupRecord> retain(int maxCount, long maxBytes, Set<String> protectedIds,
                                                 BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        if (maxCount < 1 || maxBytes < 1) throw new IOException("Retention limits must be positive");
        Set<String> protectedCopy = Set.copyOf(Objects.requireNonNull(protectedIds));
        cancelled = cancellation(cancelled);
        progress = reporter(progress);
        try (FileLock ignored = lockStore()) {
            removeAbandonedPartFiles();
            List<BackupRecord> good = new ArrayList<>(listBackups(cancelled, progress));
            List<BackupRecord> removed = new ArrayList<>();
            long bytes = 0;
            for (BackupRecord r : good) bytes = boundedAdd(bytes, r.compressedBytes(), Long.MAX_VALUE, "Backup size overflow");
            int remaining = good.size();
            for (int i = good.size() - 1; i >= 0 && (remaining > maxCount || bytes > maxBytes); i--) {
                checkCancelled(cancelled);
                BackupRecord target = good.get(i);
                if (protectedCopy.contains(target.id())) continue;
                ensureRegularFile(target.zipPath());
                ensureRegularFile(target.metadataPath());
                withdraw(target.id());
                removed.add(target);
                remaining--;
                bytes -= target.compressedBytes();
                progress.accept("Retention deleted " + target.id());
            }
            return List.copyOf(removed);
        }
    }

    /**
     * Caller holds the store lock. Both files are first renamed to the temporary names the sweeper
     * owns, the sidecar (the commit marker) first. From that rename on the backup is gone from every
     * listing, and whatever an interruption leaves behind is removed before the next change to the store.
     */
    private void withdraw(String id) throws IOException {
        verifiedArchives.remove(id);
        Path zip = destination.resolve(id + ".zip"), metadata = destination.resolve(id + ".properties");
        Path zipPart = destination.resolve("." + id + ".zip.part"), metadataPart = destination.resolve("." + id + ".properties.part");
        if (Files.exists(metadata, NOFOLLOW_LINKS)) Files.move(metadata, metadataPart, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(zip, NOFOLLOW_LINKS)) Files.move(zip, zipPart, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(destination);
        Files.deleteIfExists(metadataPart);
        Files.deleteIfExists(zipPart);
        syncDirectory(destination);
    }

    /**
     * Caller holds the store lock. Only this class writes ".ID.zip.part" / ".ID.properties.part"
     * files, and always under that lock, so any found now were left by a crash, a forced JVM stop
     * or an interrupted deletion and would otherwise occupy disk space forever. An "ID.zip" whose
     * sidecar is still such a part file belongs to the same interrupted step and goes with it.
     * Best effort: never blocks the operation that called it.
     */
    private void removeAbandonedPartFiles() {
        List<Path> abandoned = new ArrayList<>();
        Set<String> sidecarParts = new HashSet<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(destination, ".*.part")) {
            for (Path path : paths) {
                java.util.regex.Matcher match = ABANDONED_PART.matcher(path.getFileName().toString());
                if (!match.matches()) continue;
                abandoned.add(path);
                if (match.group(2).equals("properties")) sidecarParts.add(match.group(1));
            }
        } catch (IOException | DirectoryIteratorException unreadable) { return; }
        for (String id : sidecarParts) {
            Path zip = destination.resolve(id + ".zip");
            if (Files.notExists(destination.resolve(id + ".properties"), NOFOLLOW_LINKS) && Files.exists(zip, NOFOLLOW_LINKS)) abandoned.add(zip);
        }
        for (Path path : abandoned) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attributes.isRegularFile() && !attributes.isSymbolicLink()) Files.deleteIfExists(path);
            } catch (IOException ignored) { /* Leave it for the next attempt; never fail the caller over cleanup. */ }
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

    private FileLock lockStore() throws IOException {
        checkedExistingDirectory(destination);
        Path lockPath = destination.resolve(".vaultbackup.lock");
        Object currentKey = null;
        if (Files.exists(lockPath, NOFOLLOW_LINKS)) currentKey = ensureRegularFile(lockPath).fileKey();
        LockFile known = LOCK_FILES.get(lockPath);
        // A lock file somebody removed and recreated is another file; the earlier channel stays open forever.
        if (known == null || !Objects.equals(known.fileKey(), currentKey) || !known.channel().isOpen()) {
            FileChannel channel;
            try {
                channel = FileChannel.open(lockPath, Set.of(CREATE, WRITE, NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException nonPosix) {
                channel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS);
            }
            known = new LockFile(channel, ensureRegularFile(lockPath).fileKey());
            LOCK_FILES.put(lockPath, known);
        }
        try {
            FileLock lock = known.channel().tryLock();
            if (lock == null) throw new IOException("Backup store is busy in another process");
            return lock;
        } catch (OverlappingFileLockException e) {
            throw new IOException("Backup store is busy in another operation", e);
        }
    }

    /**
     * Lists what exists now. Entries that disappear while the tree is walked are skipped: on a
     * running server temporary files come and go, and that is not an error. Unreadable entries,
     * symlinks, special files and names an archive cannot carry fail the backup. All of them are
     * collected first, so one run tells the administrator everything there is to fix or exclude.
     */
    private List<Source> scan(BooleanSupplier cancelled) throws IOException {
        List<Source> sources = new ArrayList<>();
        PortablePaths names = new PortablePaths(2L * options.maxEntries() + 1024);
        List<String> problems = new ArrayList<>();
        int[] problemCount = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void problem(String name, String reason) {
                if (problemCount[0]++ < MAX_REPORTED_PROBLEMS) problems.add(printable(name) + ": " + reason);
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancelled);
                if (isAutomaticallyExcluded(directory)) return FileVisitResult.SKIP_SUBTREE;
                String name = relative(directory);
                if (!name.isEmpty() && denied(name, true)) return FileVisitResult.SKIP_SUBTREE;
                if (attrs.isSymbolicLink() || !attrs.isDirectory()) throw new IOException("Unsafe directory: " + directory);
                if (!name.isEmpty() && !addSource(directory, name, true, attrs)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancelled);
                if (isAutomaticallyExcluded(file)) return FileVisitResult.CONTINUE;
                String name = relative(file);
                if (denied(name, false) || !included(name)) return FileVisitResult.CONTINUE;
                if (attrs.isSymbolicLink()) problem(name, "symlink (not followed)");
                else if (!attrs.isRegularFile()) problem(name, "not a regular file");
                else if (attrs.size() > options.maxEntryBytes()) problem(name, "larger than limits.max-entry-bytes");
                else addSource(file, name, false, attrs);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                return failed(file, exception);
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                // Non-null when listing stopped early.
                return exception == null ? FileVisitResult.CONTINUE : failed(directory, exception);
            }
            /** A directory that cannot be opened arrives here without preVisitDirectory, so exclusions are applied here too. */
            private FileVisitResult failed(Path path, IOException exception) throws IOException {
                if (path.equals(root)) throw new IOException("Cannot read the server root: " + root, exception);
                if (exception instanceof NoSuchFileException) return FileVisitResult.CONTINUE; // Removed underneath the walk.
                if (isAutomaticallyExcluded(path)) return FileVisitResult.CONTINUE;
                String name = relative(path);
                if (denied(name, true) || denied(name, false)) return FileVisitResult.CONTINUE;
                problem(name, "cannot be read (" + exception.getClass().getSimpleName() + ")");
                return FileVisitResult.CONTINUE;
            }
            /** False when the entry was recorded as a problem instead. */
            private boolean addSource(Path file, String name, boolean directory, BasicFileAttributes attrs) throws IOException {
                // A name the JVM's file-name encoding cannot represent was decoded with replacement
                // characters; it does not lead back to the file, and the archive would carry a wrong name.
                boolean representable;
                try { representable = root.resolve(name).equals(file); }
                catch (InvalidPathException undecodable) { representable = false; }
                if (!representable) {
                    problem(name, "file name cannot be represented with this JVM's file-name encoding (start the server with a UTF-8 locale such as LC_ALL=C.UTF-8)");
                    return false;
                }
                try { validateEntryPath(name); }
                catch (IOException unsafe) { problem(name, "name is not portable (control character, one of \\ : < > \" | ? *, a trailing dot or space, or a reserved device name)"); return false; }
                if (name.equalsIgnoreCase(MANIFEST_PATH)) { problem(name, "reserved name"); return false; }
                try { names.add(name, directory); }
                catch (IOException collision) { problem(name, "differs from another path only by letter case or Unicode form"); return false; }
                if (sources.size() >= options.maxEntries()) throw new IOException("Backup entry count exceeds limits.max-entries");
                sources.add(new Source(file, name, directory, attrs));
                return true;
            }
        });
        if (problemCount[0] > 0) throw new SourceRefusedException(problems, problemCount[0]);
        // Parents sort before their children, so a directory entry always precedes its contents.
        sources.sort(Comparator.comparing(Source::name));
        return sources;
    }

    /** For messages: a path with control characters and the replacement character made visible. */
    private static String printable(String name) {
        StringBuilder text = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == '\uFFFD') text.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else text.append(c);
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text.toString();
    }

    private boolean isAutomaticallyExcluded(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(destination) || absolute.startsWith(state);
    }
    private String relative(Path path) { return root.relativize(path).toString().replace(File.separatorChar, '/'); }
    private boolean included(String path) { return includes.isEmpty() || includes.stream().anyMatch(p -> p.matcher(path).matches()); }
    private boolean denied(String path, boolean directory) {
        return excludes.stream().anyMatch(p -> p.matcher(path).matches() || (directory && p.matcher(path + "/").matches()));
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
        // ZipFile reads the whole central directory into memory as soon as it opens the file.
        ZipDirectory.requireWithin(record.zipPath(), limits.maxEntries() + 1L,
                MAX_MANIFEST_BYTES + (limits.maxEntries() + 1L) * DIRECTORY_RECORD_OVERHEAD);
        try (ZipFile zip = new ZipFile(record.zipPath().toFile(), StandardCharsets.UTF_8)) {
            Map<String, ZipEntry> entries = new LinkedHashMap<>();
            PortablePaths names = new PortablePaths(2L * limits.maxEntries() + 1024);
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
                if (entries.putIfAbsent(name, entry) != null) throw new IOException("Duplicate ZIP entry: " + name);
                // Also refuses case/Unicode aliases at any depth and a file that is an ancestor of another entry.
                names.add(name, entry.isDirectory());
                if (entries.size() > limits.maxEntries()) throw new IOException("Archive entry count exceeds limit");
                if (entry.getSize() < 0 || entry.getCompressedSize() < 0 || entry.getSize() > limits.maxEntryBytes()) throw new IOException("Invalid or excessive ZIP entry size: " + name);
                if (entry.isDirectory() && entry.getSize() != 0) throw new IOException("Directory contains data: " + name);
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
                if (entry == null || entry.isDirectory() != expected.directory() || entry.getSize() != expected.size()) throw new IOException("Manifest/ZIP disagreement: " + expected.path());
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
                if (copied != expected.size() || crc.getValue() != entry.getCrc()) throw new IOException("Entry size/CRC mismatch: " + expected.path());
                if (!expected.directory() && !hex(digest.digest()).equals(expected.sha256())) throw new IOException("Entry SHA-256 mismatch: " + expected.path());
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
                if (path.equalsIgnoreCase(MANIFEST_PATH) || !paths.add(PortablePaths.key(path))) throw new IOException("Reserved or duplicate manifest path");
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

    private static long writeManifest(OutputStream output, List<ManifestEntry> entries) throws IOException {
        long length = 0;
        byte[] header = (MANIFEST_HEADER + "\n").getBytes(StandardCharsets.UTF_8);
        output.write(header); length += header.length;
        for (ManifestEntry entry : entries) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(entry.path().getBytes(StandardCharsets.UTF_8));
            byte[] line = ((entry.directory() ? "D" : "F") + "\t" + entry.size() + "\t" + entry.sha256() + "\t" + encoded + "\n").getBytes(StandardCharsets.UTF_8);
            length = boundedAdd(length, line.length, MAX_MANIFEST_BYTES, "Manifest exceeds size limit");
            output.write(line);
        }
        return length;
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
        // Optional field (absent before 1.3.0): files that kept changing while they were copied.
        metadata.setProperty("unstableEntries", Integer.toString(record.unstableEntries()));
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
            int unstable = Integer.parseInt(metadata.getProperty("unstableEntries", "0"));
            if (bytes < 1 || total < 0 || count < 0 || unstable < 0 || unstable > count || label.length() > 200 || label.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid archive metadata");
            return new BackupRecord(id, Instant.parse(required(metadata, "createdAt")), label,
                    zip.toAbsolutePath().normalize(), path.toAbsolutePath().normalize(), bytes, total, count, digest, unstable);
        } catch (RuntimeException e) { throw new IOException("Malformed metadata", e); }
    }

    private static String required(Properties metadata, String key) throws IOException {
        String value = metadata.getProperty(key);
        if (value == null) throw new IOException("Missing metadata field: " + key);
        return value;
    }

    /** Strict portable archive paths; never accept absolute paths, traversal, ADS or device names. */
    public static void validateEntryPath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 4096 || path.getBytes(StandardCharsets.UTF_8).length > 4096 ||
                path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 ||
                path.chars().anyMatch(c -> Character.isISOControl(c) || c == 0)) throw new IOException("Unsafe archive path: " + path);
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..") || component.endsWith(".") || component.endsWith(" ") ||
                    component.indexOf('<') >= 0 || component.indexOf('>') >= 0 || component.indexOf('"') >= 0 ||
                    component.indexOf('|') >= 0 || component.indexOf('?') >= 0 || component.indexOf('*') >= 0) throw new IOException("Unsafe archive path: " + path);
            String base = component.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Reserved device name in archive path: " + path);
        }
    }

    public static void validateId(String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) throw new IOException("Invalid backup ID");
    }

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

    /**
     * Portable glob: a single star matches within one path segment, a double star matches any path,
     * a question mark matches one character. Everything else is literal and case-sensitive. A pattern
     * without any wildcard that names a directory also selects everything below it, and one trailing
     * slash is ignored, so "world" and "world/" mean the same as "world/**".
     */
    private static List<Pattern> compileGlobs(List<String> patterns) throws IOException {
        List<Pattern> compiled = new ArrayList<>();
        for (String original : patterns) {
            if (original == null || original.isBlank() || original.startsWith("/") || original.indexOf('\\') >= 0 ||
                    Arrays.asList(original.split("/", -1)).contains("..")) throw new IOException("Invalid relative glob: " + original);
            String glob = original.endsWith("/") && original.length() > 1 ? original.substring(0, original.length() - 1) : original;
            boolean literal = glob.indexOf('*') < 0 && glob.indexOf('?') < 0;
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
            if (literal) regex.append("(?:/.*)?");
            regex.append('$');
            compiled.add(Pattern.compile(regex.toString()));
        }
        return List.copyOf(compiled);
    }
}
