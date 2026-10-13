// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.restore;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Strict verifier/extractor. It never follows archive or filesystem symlinks. */
public final class RestoreArchive {
    public static final String MANIFEST = ".vaultbackup-manifest";
    private static final int MAX_ENTRIES = 1_000_000;
    private static final long MAX_ENTRY = 256L << 30, MAX_TOTAL = 1L << 40;
    private static final int MAX_MANIFEST = 128 * 1024 * 1024;
    public record Entry(String path, boolean directory, long size, String sha256) {}
    public record Verified(List<Entry> entries, long totalBytes, String manifestSha256) {
        public Verified { entries = List.copyOf(entries); }
    }
    private RestoreArchive() {}
    public static Verified verify(Path archive, String expectedSha256) throws IOException {
        return extract(archive, expectedSha256, null);
    }
    public static Verified extract(Path archive, String expectedSha256, Path destination) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) throw new IOException("Invalid expected archive digest");
        if (!RestoreFiles.constantEquals(RestoreFiles.sha(archive), expectedSha256)) throw new IOException("Archive SHA-256 mismatch");
        if (destination != null) {
            RestoreFiles.directory(destination, false);
            try (var s = Files.list(destination)) { if (s.findAny().isPresent()) throw new IOException("Restore stage must be empty"); }
        }
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry manifest = zip.getEntry(MANIFEST);
            if (manifest == null || manifest.isDirectory()) throw new IOException("Missing archive manifest");
            checkRatio(manifest);
            byte[] bytes;
            try (InputStream in = zip.getInputStream(manifest)) { bytes = limited(in, MAX_MANIFEST); }
            Verified parsed = parse(bytes);
            Map<String,Entry> expected = new HashMap<>();
            for (Entry e : parsed.entries()) expected.put(e.path(), e);
            Set<String> seen = new HashSet<>(), folded = new HashSet<>();
            byte[] buffer = new byte[128 * 1024]; long actualTotal = 0; int count = 0;
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry ze = enumeration.nextElement();
                String name = ze.getName();
                if (++count > MAX_ENTRIES + 1) throw new IOException("Too many archive entries");
                if (name.equals(MANIFEST)) {
                    if (!seen.add(name)) throw new IOException("Duplicate manifest");
                    continue;
                }
                checkRatio(ze);
                boolean directory = ze.isDirectory();
                if (directory) name = name.substring(0, name.length() - 1);
                validatePath(name);
                if (!seen.add(name) || !folded.add(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT))) throw new IOException("Duplicate or case-colliding ZIP path: " + name);
                Entry e = expected.get(name);
                if (e == null || e.directory() != directory) throw new IOException("Unmanifested or type-mismatched entry: " + name);
                Path target = destination == null ? null : destination.resolve(name).normalize();
                if (target != null && !target.startsWith(destination)) throw new IOException("Unsafe target path");
                if (directory) { if (ze.getSize()!=0) throw new IOException("Nonempty directory ZIP entry"); if (target != null) Files.createDirectories(target); continue; }
                if (ze.getSize() != e.size()) throw new IOException("ZIP/manifest size mismatch: " + name);
                MessageDigest digest = RestoreFiles.digest(); long size = 0;
                if (target != null) Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(ze); OutputStream out = target == null ? OutputStream.nullOutputStream()
                        : Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int n; while ((n = in.read(buffer)) != -1) {
                        size += n; actualTotal += n;
                        if (size > e.size() || size > MAX_ENTRY || actualTotal > MAX_TOTAL) throw new IOException("Archive exceeds declared/safe size");
                        digest.update(buffer,0,n); out.write(buffer,0,n);
                    }
                }
                if (size != e.size() || !HexFormat.of().formatHex(digest.digest()).equals(e.sha256())) throw new IOException("Entry digest mismatch: " + name);
                if (target != null) try (var channel = java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) { channel.force(true); }
            }
            if (seen.size() != expected.size() + 1) throw new IOException("Manifest entries are missing from archive");
            // Recheck the whole archive after extraction to catch replacement during verification.
            if (!RestoreFiles.constantEquals(RestoreFiles.sha(archive), expectedSha256)) throw new IOException("Archive changed during verification");
            if (destination != null) verifyInstalled(destination, parsed);
            return parsed;
        }
    }
    private static void checkRatio(ZipEntry entry)throws IOException {
        if(entry.getSize()<0 || entry.getCompressedSize()<0 || entry.getSize()/(double)Math.max(1,entry.getCompressedSize())>10000)
            throw new IOException("ZIP compression ratio exceeds safety limit");
    }
    private static byte[] limited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
        while ((n = in.read(buffer)) != -1) { if ((long)out.size() + n > max) throw new IOException("Manifest too large"); out.write(buffer,0,n); }
        return out.toByteArray();
    }
    private static Verified parse(byte[] bytes) throws IOException {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("Manifest is not UTF-8",e); }
        String[] lines = text.split("\n",-1);
        if (!lines[0].equals("VAULTBACKUP-MANIFEST\t1")) throw new IOException("Unsupported manifest format");
        if (lines.length > MAX_ENTRIES + 2) throw new IOException("Manifest has too many records");
        List<Entry> entries = new ArrayList<>(); Set<String> paths = new HashSet<>(); Map<String,Boolean> types = new HashMap<>(); long total=0;
        for (int i=1;i<lines.length;i++) {
            if (i == lines.length-1 && lines[i].isEmpty()) continue;
            if(lines[i].length()>8192) throw new IOException("Manifest record exceeds safety limit");
            String[] f=lines[i].split("\t",-1);
            if(f.length!=4 || !(f[0].equals("F") || f[0].equals("D"))) throw new IOException("Malformed manifest record");
            String path; long size;
            try { path=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(f[3]))).toString(); size=Long.parseLong(f[1]); }
            catch (RuntimeException | CharacterCodingException e) { throw new IOException("Invalid manifest encoding",e); }
            validatePath(path); boolean dir=f[0].equals("D");
            if(!paths.add(java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT))) throw new IOException("Duplicate/case-colliding manifest path");
            if(size<0 || size>MAX_ENTRY || (dir && (size!=0 || !f[2].equals("-"))) || (!dir && !f[2].matches("[0-9a-f]{64}"))) throw new IOException("Invalid manifest size/hash");
            total=Math.addExact(total,size); if(total>MAX_TOTAL) throw new IOException("Archive too large");
            entries.add(new Entry(path,dir,size,f[2])); types.put(path,dir);
        }
        Map<String,String> spellings = new HashMap<>(); Set<String> fileKeys = new HashSet<>(), directoryKeys = new HashSet<>();
        for(Entry e:entries) {
            StringBuilder prefix = new StringBuilder(); String[] components=e.path().split("/");
            for(int i=0;i<components.length;i++) {
                if(i>0)prefix.append('/'); prefix.append(components[i]); String spelling=prefix.toString();
                String key=java.text.Normalizer.normalize(spelling,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                String previous=spellings.putIfAbsent(key,spelling);
                if(previous!=null && !previous.equals(spelling))throw new IOException("Case/Unicode alias in ancestor path");
                if(i<components.length-1 || e.directory()) {if(fileKeys.contains(key))throw new IOException("File/directory ancestor conflict");directoryKeys.add(key);}
                else {if(directoryKeys.contains(key))throw new IOException("File/directory ancestor conflict");fileKeys.add(key);}
            }
        }
        return new Verified(entries,total,HexFormat.of().formatHex(RestoreFiles.digest().digest(bytes)));
    }
    public static void validatePath(String path) throws IOException {
        if(path.isEmpty() || path.length()>4096 || path.startsWith("/") || path.contains("\\") || path.indexOf(':')>=0 || path.split("/",2)[0].equalsIgnoreCase(MANIFEST)) throw new IOException("Unsafe archive path: "+path);
        for(String part:path.split("/",-1)) {
            if(part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.chars().anyMatch(c->Character.isISOControl(c) || "<>\"|?*".indexOf(c)>=0)) throw new IOException("Unsafe archive path");
            String base=part.split("\\.",2)[0].toUpperCase(Locale.ROOT);
            if(base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Platform-reserved archive path");
        }
    }
    public static void verifyInstalled(Path root, Verified archive) throws IOException {
        RestoreFiles.directory(root,false); Map<String,Entry> files=new HashMap<>(); Set<String> dirs=new HashSet<>();
        for(Entry e:archive.entries()) {
            if(e.directory()) dirs.add(e.path()); else files.put(e.path(),e);
            String p=e.path(); int slash; while((slash=p.lastIndexOf('/'))>=0) {p=p.substring(0,slash); dirs.add(p);}
        }
        Set<String> foundFiles=new HashSet<>(), foundDirs=new HashSet<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                if(!dir.equals(root)) {String p=root.relativize(dir).toString().replace(File.separatorChar,'/'); if(!dirs.contains(p)) throw new IOException("Unexpected installed directory: "+p);foundDirs.add(p);}return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                String p=root.relativize(file).toString().replace(File.separatorChar,'/'); Entry e=files.get(p);
                if(e==null || !attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.size()!=e.size() || !RestoreFiles.sha(file).equals(e.sha256())) throw new IOException("Installed data did not verify: "+p);
                foundFiles.add(p); return FileVisitResult.CONTINUE;
            }
        });
        if(!foundFiles.equals(files.keySet()) || !foundDirs.equals(dirs)) throw new IOException("Installed tree is incomplete");
    }
}
