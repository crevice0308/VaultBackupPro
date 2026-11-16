// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.io.IOException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The set of paths in one archive, checked for what would collide on a case-insensitive or
 * Unicode-normalising filesystem: two spellings of one name, a file where a directory is needed,
 * and duplicates. Paths are held as a tree, so memory grows with the number of path components,
 * not with the combined length of every ancestor prefix, and the tree itself is bounded.
 */
public final class PortablePaths {
    private static final class Node {
        final String spelling;
        final boolean directory;
        boolean listed;
        Map<String, Node> children;
        Node(String spelling, boolean directory) { this.spelling = spelling; this.directory = directory; }
    }

    private final Node root = new Node("", true);
    private final long maxNodes;
    private long nodes;

    /** @param maxNodes most distinct files and directories (listed or implied as an ancestor) accepted */
    public PortablePaths(long maxNodes) { this.maxNodes = maxNodes; }

    /** Key under which names that a case-insensitive, normalising filesystem treats as equal are the same. */
    public static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /**
     * Adds a '/'-separated path whose components are already validated.
     *
     * @throws IOException on a duplicate, a case/Unicode alias of an existing name, a file used as a
     *                     directory (or the reverse), or when the tree would exceed its bound
     */
    public void add(String path, boolean directory) throws IOException {
        Node node = root;
        int start = 0;
        while (true) {
            int slash = path.indexOf('/', start);
            boolean last = slash < 0;
            String component = last ? path.substring(start) : path.substring(start, slash);
            boolean asDirectory = !last || directory;
            if (node.children == null) node.children = new HashMap<>(4);
            String key = key(component);
            Node child = node.children.get(key);
            if (child == null) {
                if (++nodes > maxNodes) throw new IOException("Archive directory structure exceeds limits");
                child = new Node(component, asDirectory);
                node.children.put(key, child);
            } else {
                if (!child.spelling.equals(component)) throw new IOException("Case/Unicode-colliding archive paths: " + path);
                if (child.directory != asDirectory) throw new IOException("File/directory path conflict: " + path);
            }
            if (last) {
                if (child.listed) throw new IOException("Duplicate archive path: " + path);
                child.listed = true;
                return;
            }
            node = child;
            start = slash + 1;
        }
    }
}
