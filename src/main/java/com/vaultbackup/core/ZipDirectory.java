// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Reads the end of a ZIP file before java.util.zip does. ZipFile loads the whole central directory
 * into memory when it opens a file, whatever size the file claims for it, so a small crafted file
 * could otherwise demand gigabytes. Only the layout this project writes is accepted: the end record
 * is the last 22 bytes of the file, with no archive comment.
 */
public final class ZipDirectory {
    private static final int END_SIGNATURE = 0x06054b50, END_BYTES = 22;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50, ZIP64_LOCATOR_BYTES = 20;
    private static final int ZIP64_END_SIGNATURE = 0x06064b50, ZIP64_END_BYTES = 56;
    private static final long MAGIC_32 = 0xFFFFFFFFL;
    private static final int MAGIC_16 = 0xFFFF;

    /** What java.util.zip will take from the file: entry count, and size and position of the central directory. */
    public record Summary(long entries, long directoryBytes, long directoryOffset) { }

    private ZipDirectory() { }

    /** @throws IOException when the file is not a ZIP in the accepted layout or exceeds either limit */
    public static Summary requireWithin(Path zip, long maxEntries, long maxDirectoryBytes) throws IOException {
        Summary summary = read(zip);
        if (summary.entries() > maxEntries) throw new IOException("ZIP entry count exceeds limit");
        if (summary.directoryBytes() > maxDirectoryBytes) throw new IOException("ZIP central directory exceeds limit");
        return summary;
    }

    public static Summary read(Path zip) throws IOException {
        try (FileChannel channel = FileChannel.open(zip, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size < END_BYTES) throw new IOException("Not a ZIP archive");
            long endPosition = size - END_BYTES;
            ByteBuffer end = read(channel, endPosition, END_BYTES);
            if (end.getInt(0) != END_SIGNATURE || unsigned16(end, 20) != 0)
                throw new IOException("Unsupported ZIP layout: the end record must be the last 22 bytes");
            long entries = unsigned16(end, 10), directoryBytes = unsigned32(end, 12), directoryOffset = unsigned32(end, 16);
            long directoryEnd = endPosition;
            // Same rule as java.util.zip: a ZIP64 end record is used when it is present and agrees
            // with every 32-bit field that is not the "see ZIP64" marker.
            if (endPosition >= ZIP64_LOCATOR_BYTES) {
                ByteBuffer locator = read(channel, endPosition - ZIP64_LOCATOR_BYTES, ZIP64_LOCATOR_BYTES);
                if (locator.getInt(0) == ZIP64_LOCATOR_SIGNATURE) {
                    long end64Position = locator.getLong(8);
                    if (end64Position >= 0 && end64Position <= size - ZIP64_END_BYTES) {
                        ByteBuffer end64 = read(channel, end64Position, ZIP64_END_BYTES);
                        if (end64.getInt(0) == ZIP64_END_SIGNATURE) {
                            long entries64 = end64.getLong(32), bytes64 = end64.getLong(40), offset64 = end64.getLong(48);
                            boolean agrees = (bytes64 == directoryBytes || directoryBytes == MAGIC_32)
                                    && (offset64 == directoryOffset || directoryOffset == MAGIC_32)
                                    && (entries64 == entries || entries == MAGIC_16);
                            if (agrees) {
                                entries = entries64; directoryBytes = bytes64; directoryOffset = offset64;
                                directoryEnd = end64Position;
                            }
                        }
                    }
                }
            }
            if (entries < 0 || directoryBytes < 0 || directoryOffset < 0 || directoryBytes > directoryEnd
                    || directoryOffset > directoryEnd - directoryBytes)
                throw new IOException("Invalid ZIP central directory bounds");
            return new Summary(entries, directoryBytes, directoryOffset);
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) throw new IOException("Truncated ZIP archive");
        }
        return buffer;
    }

    private static int unsigned16(ByteBuffer buffer, int index) { return buffer.getShort(index) & 0xFFFF; }
    private static long unsigned32(ByteBuffer buffer, int index) { return buffer.getInt(index) & 0xFFFFFFFFL; }
}
