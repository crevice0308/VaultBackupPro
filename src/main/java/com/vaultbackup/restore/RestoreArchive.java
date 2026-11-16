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
    /** Windows-only name rules apply where the archive is materialized on Windows. */
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final java.util.regex.Pattern DEVICE = java.util.regex.Pattern.compile(
            "CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9\u00b9\u00b2\u00b3]|LPT[0-9\u00b9\u00b2\u00b3]");
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
                if (!seen.add(name) || !folded.add(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT))) throw new IOException("Duplicate or case-colliding ZIP path: " + printable(name));
                Entry e = expected.get(name);
                if (e == null || e.directory() != directory) throw new IOException("Unmanifested or type-mismatched entry: " + printable(name));
                // Every name must be representable on this host before the server is ever stopped:
                // reservation-time verification runs this same check without a destination.
                Path target;
                try {
                    Path probe = archive.getFileSystem().getPath(name);
                    target = destination == null ? null : destination.resolve(probe).normalize();
                } catch (InvalidPathException unrepresentable) {
                    throw new IOException("Archive path cannot be created on this host (file-name encoding " + System.getProperty("sun.jnu.encoding") + "): " + printable(name), unrepresentable);
                }
                if (target != null && !target.startsWith(destination)) throw new IOException("Unsafe target path");
                if (directory) { if (ze.getSize()!=0) throw new IOException("Nonempty directory ZIP entry"); if (target != null) Files.createDirectories(target); continue; }
                if (ze.getSize() != e.size()) throw new IOException("ZIP/manifest size mismatch: " + printable(name));
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
                if (size != e.size() || !HexFormat.of().formatHex(digest.digest()).equals(e.sha256())) throw new IOException("Entry digest mismatch: " + printable(name));
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
        // One node per distinct (parent, portable component): full ancestor prefixes would make
        // memory quadratic in path depth for a small crafted manifest of deep paths.
        Map<String,Integer> nodes = new HashMap<>(); List<String> spellings = new ArrayList<>(); BitSet fileNodes = new BitSet(), directoryNodes = new BitSet();
        for(Entry e:entries) {
            String[] components=e.path().split("/"); int parent=0;
            for(int i=0;i<components.length;i++) {
                String key=parent+"/"+java.text.Normalizer.normalize(components[i],java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                Integer node=nodes.get(key);
                if(node==null){spellings.add(components[i]);node=spellings.size();nodes.put(key,node);}
                else if(!spellings.get(node-1).equals(components[i]))throw new IOException("Case/Unicode alias in ancestor path");
                if(i<components.length-1 || e.directory()) {if(fileNodes.get(node))throw new IOException("File/directory ancestor conflict");directoryNodes.set(node);}
                else {if(directoryNodes.get(node))throw new IOException("File/directory ancestor conflict");fileNodes.set(node);}
                parent=node;
            }
        }
        return new Verified(entries,total,HexFormat.of().formatHex(RestoreFiles.digest().digest(bytes)));
    }
    /** Identical to BackupEngine.validateEntryPath; kept separate so the helper stays standalone. */
    public static void validatePath(String path) throws IOException { validatePath(path, WINDOWS); }
    public static void validatePath(String path, boolean windowsRules) throws IOException {
        if(path.isEmpty() || path.length()>4096 || path.startsWith("/") || path.contains("\\") || path.split("/",2)[0].equalsIgnoreCase(MANIFEST)
                || path.chars().anyMatch(Character::isISOControl)) throw new IOException("Unsafe archive path: "+printable(path));
        for(int i=0;i<path.length();i++) {
            char c=path.charAt(i);
            if(Character.isHighSurrogate(c) && i+1<path.length() && Character.isLowSurrogate(path.charAt(i+1))){i++;continue;}
            if(Character.isSurrogate(c))throw new IOException("Archive path is not valid Unicode: "+printable(path));
        }
        // Names that were already lossy when archived (U+FFFD) would restore as different files.
        if(path.indexOf('\uFFFD')>=0) throw new IOException("Archive path contains a file name that was not stored losslessly: "+printable(path));
        for(String part:path.split("/",-1)) {
            if(part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe archive path: "+printable(path));
            if(!windowsRules) continue;
            if(part.endsWith(".") || part.endsWith(" ") || part.chars().anyMatch(c->"<>:\"|?*".indexOf(c)>=0)) throw new IOException("Name is not valid on Windows: "+printable(path));
            String base=part.split("\\.",2)[0].replaceAll(" +$","").toUpperCase(Locale.ROOT);
            if(DEVICE.matcher(base).matches()) throw new IOException("Platform-reserved archive path: "+printable(path));
        }
    }
    /** Control, format and line-separator characters become visible escapes in messages. */
    static String printable(String value) {
        StringBuilder out=new StringBuilder(); int count=0;
        for(int i=0;i<value.length();) {
            int c=value.codePointAt(i); i+=Character.charCount(c);
            if(++count>512){out.append("...");break;}
            int type=Character.getType(c);
            if(c=='\u00a7' || Character.isISOControl(c) || type==Character.LINE_SEPARATOR || type==Character.PARAGRAPH_SEPARATOR
                    || type==Character.FORMAT || type==Character.SURROGATE) out.append(String.format(Locale.ROOT,"\\u%04X",c));
            else out.appendCodePoint(c);
        }
        return out.toString();
    }
    /** One expected node per archive path component; memory stays linear in the manifest size. */
    private static final class Node {
        final Map<String,Node> children=new HashMap<>(); Entry file; boolean found;
    }
    public static void verifyInstalled(Path root, Verified archive) throws IOException {
        RestoreFiles.directory(root,false); Node top=new Node(); List<Node> all=new ArrayList<>();
        for(Entry e:archive.entries()) {
            String[] components=e.path().split("/"); Node node=top;
            for(int i=0;i<components.length;i++) {
                Node child=node.children.get(components[i]);
                if(child==null){child=new Node();node.children.put(components[i],child);all.add(child);}
                node=child;
            }
            if(!e.directory()) node.file=e;
        }
        Deque<Node> stack=new ArrayDeque<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                if(dir.equals(root)){stack.push(top);return FileVisitResult.CONTINUE;}
                Node node=stack.peek().children.get(dir.getFileName().toString());
                if(node==null || node.file!=null || attrs.isSymbolicLink()) throw new IOException("Unexpected installed directory: "+printable(root.relativize(dir).toString()));
                node.found=true; stack.push(node); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException exception)throws IOException {
                if(exception!=null) throw exception;
                stack.pop(); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                String p=root.relativize(file).toString().replace(File.separatorChar,'/');
                Node node=stack.peek().children.get(file.getFileName().toString()); Entry e=node==null?null:node.file;
                if(e==null || !attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.size()!=e.size() || !RestoreFiles.sha(file).equals(e.sha256())) throw new IOException("Installed data did not verify: "+printable(p));
                node.found=true; return FileVisitResult.CONTINUE;
            }
        });
        for(Node node:all) if(!node.found) throw new IOException("Installed tree is incomplete");
    }
}
