package org.koitharu.kotatsu.local.library;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Storage-independent discovery. Every edge is checked before listing; no depth/name heuristics. */
public final class LocalTreeScanner {
    public interface Access {
        List<Node> children(Node directory) throws IOException;
        boolean contains(Node root, Node child) throws IOException;
        void checkCancelled() throws IOException;
    }
    public static final class Node {
        public final String key, uri, name;
        public final boolean directory;
        public final long size, modified;
        public Node(String key, String uri, String name, boolean directory, long size, long modified) {
            this.key = key; this.uri = uri; this.name = name; this.directory = directory;
            this.size = size; this.modified = modified;
        }
    }
    public static final class Chapter {
        public final Node node;
        public final List<Node> pages;
        Chapter(Node node, List<Node> pages) { this.node = node; this.pages = pages; }
    }
    public static final class Entry {
        public final Node node;
        public final List<Chapter> chapters;
        public final List<Node> sidecars;
        public final int ignored;
        Entry(Node node, List<Chapter> chapters, List<Node> sidecars, int ignored) {
            this.node = node; this.chapters = chapters; this.sidecars = sidecars; this.ignored = ignored;
        }
    }
    public static final class Issue {
        public final Node node;
        public final String reason;
        public final List<Node> candidates;
        Issue(Node node, String reason, List<Node> candidates) {
            this.node = node; this.reason = reason; this.candidates = candidates;
        }
    }
    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        public final List<Issue> issues = new ArrayList<>();
    }
    private enum Kind { IMAGE, MANGA, CONTAINER, REVIEW, EMPTY }
    private static final class Tree {
        final Node node;
        final List<Tree> children = new ArrayList<>();
        Kind kind = Kind.EMPTY;
        List<Chapter> chapters = new ArrayList<>();
        List<Node> sidecars = new ArrayList<>();
        int ignored;
        boolean explicitBoundary;
        Tree(Node node) { this.node = node; }
    }

    public Result scan(Node root, Access access, Set<String> confirmedManga, Set<String> excluded) throws IOException {
        if (!root.directory) throw new IOException("Local root must be a directory");
        Tree top = new Tree(root);
        List<Tree> order = new ArrayList<>();
        ArrayDeque<Tree> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        visited.add(root.key); pending.add(top);
        Result result = new Result();
        while (!pending.isEmpty()) {
            access.checkCancelled();
            Tree t = pending.removeLast(); order.add(t);
            List<Node> children = access.children(t.node);
            children.sort((a, b) -> NATURAL.compare(a.name, b.name));
            for (Node n : children) {
                access.checkCancelled();
                if (n.name.startsWith(".")) continue;
                if (!access.contains(root, n)) throw new IOException("Document outside selected root: " + n.name);
                if (!visited.add(n.key)) throw new IOException("Duplicate or cyclic document: " + n.name);
                if (excluded.contains(n.key)) continue;
                Tree child = new Tree(n); t.children.add(child);
                if (n.directory) pending.add(child);
            }
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            access.checkCancelled();
            classify(order.get(i), confirmedManga);
        }
        // A selected root is a collection boundary; individual files directly in it are single books.
        if (confirmedManga.contains(root.key)) {
            if (!top.chapters.isEmpty()) result.entries.add(entry(top));
            else result.issues.add(new Issue(root, "review", Collections.emptyList()));
        } else {
            for (Tree t : top.children) emit(t, result);
            if (top.children.isEmpty()) result.issues.add(new Issue(root, "empty", Collections.emptyList()));
        }
        result.entries.sort((a, b) -> NATURAL.compare(a.node.name, b.node.name));
        return result;
    }

    private void classify(Tree t, Set<String> confirmed) {
        List<Tree> dirs = new ArrayList<>();
        List<Node> images = new ArrayList<>();
        List<Chapter> direct = new ArrayList<>();
        for (Tree c : t.children) {
            if (c.node.directory) dirs.add(c);
            else if (isSidecar(c.node.name)) t.sidecars.add(c.node);
            else if (isImage(c.node.name)) images.add(c.node);
            else if (isBook(c.node.name)) direct.add(new Chapter(c.node, Collections.emptyList()));
            else t.ignored++;
        }
        if (!images.isEmpty() && direct.isEmpty() && dirs.isEmpty()) {
            t.kind = Kind.IMAGE;
            t.chapters.add(new Chapter(t.node, images));
            return;
        }
        if (!images.isEmpty()) {
            t.kind = Kind.REVIEW; return; // loose pages mixed with books/subdirectories
        }
        List<Chapter> nested = new ArrayList<>();
        boolean deep = false, bad = false;
        for (Tree d : dirs) {
            if (d.kind == Kind.IMAGE) nested.addAll(d.chapters);
            else if (d.kind == Kind.MANGA || d.kind == Kind.CONTAINER) {
                nested.addAll(d.chapters); deep = true;
            } else if (d.kind == Kind.REVIEW) bad = true;
            t.ignored += d.ignored;
        }
        if (confirmed.contains(t.node.key)) {
            List<Chapter> grouped = confirmedChapters(t);
            if (grouped == null) { t.kind = Kind.REVIEW; return; }
            t.chapters.addAll(grouped);
            t.kind = t.chapters.isEmpty() ? Kind.EMPTY : Kind.MANGA;
            t.explicitBoundary = true;
        } else if (bad || (!direct.isEmpty() && deep)) {
            t.kind = Kind.REVIEW;
        } else if (!direct.isEmpty() || (!nested.isEmpty() && !deep)) {
            t.chapters.addAll(direct); t.chapters.addAll(nested); t.kind = Kind.MANGA;
        } else if (deep) {
            t.chapters.addAll(nested);
            long meaningful = dirs.stream().filter(d -> d.kind != Kind.EMPTY).count();
            if (meaningful == 1 && dirs.stream().anyMatch(d -> d.kind == Kind.MANGA) && dirs.stream().noneMatch(d -> d.explicitBoundary)) t.kind = Kind.REVIEW;
            else t.kind = Kind.CONTAINER;
        }
        t.explicitBoundary |= dirs.stream().anyMatch(d -> d.explicitBoundary);
        t.chapters.sort((a, b) -> NATURAL.compare(a.node.name, b.node.name));
    }

    private List<Chapter> confirmedChapters(Tree start) {
        List<Chapter> result = new ArrayList<>();
        ArrayDeque<Tree> pending = new ArrayDeque<>(); pending.add(start);
        while (!pending.isEmpty()) {
            Tree t = pending.removeFirst();
            if (t.kind == Kind.IMAGE) { result.addAll(t.chapters); continue; }
            boolean looseImages = t.children.stream().anyMatch(c -> !c.node.directory && isImage(c.node.name) && !isSidecar(c.node.name));
            if (looseImages) return null;
            for (Tree c : t.children) {
                if (c.node.directory) pending.add(c);
                else if (isBook(c.node.name)) result.add(new Chapter(c.node, Collections.emptyList()));
            }
        }
        return result;
    }
    private Entry entry(Tree t) { return new Entry(t.node, t.chapters, t.sidecars, t.ignored); }
    private void emit(Tree start, Result result) {
        ArrayDeque<Tree> pending = new ArrayDeque<>(); pending.add(start);
        while (!pending.isEmpty()) {
            Tree t = pending.removeFirst();
            if (!t.node.directory) {
                if (isBook(t.node.name)) result.entries.add(new Entry(t.node,
                    Collections.singletonList(new Chapter(t.node, Collections.emptyList())), Collections.emptyList(), 0));
                else if (!isSidecar(t.node.name)) result.issues.add(new Issue(t.node, "unsupported", Collections.emptyList()));
            } else if (t.kind == Kind.MANGA || t.kind == Kind.IMAGE) result.entries.add(entry(t));
            else if (t.kind == Kind.CONTAINER) pending.addAll(t.children);
            else if (t.kind == Kind.REVIEW) result.issues.add(new Issue(t.node, "review", chain(t)));
            else result.issues.add(new Issue(t.node, "unsupported", Collections.emptyList()));
        }
    }
    private List<Node> chain(Tree start) {
        List<Node> result = new ArrayList<>(); Tree t = start;
        while (true) {
            List<Tree> dirs = new ArrayList<>();
            for (Tree c : t.children) if (c.node.directory && c.kind != Kind.EMPTY) dirs.add(c);
            // Offer only a metadata-free single-container chain, not mixed or competing structures.
            boolean content = t.children.stream().anyMatch(c -> !c.node.directory && (isBook(c.node.name) || isImage(c.node.name)));
            if (content || dirs.size() != 1) return Collections.emptyList();
            result.add(t.node); t = dirs.get(0);
            if (t.kind == Kind.MANGA) { result.add(t.node); return result; }
            if (t.kind != Kind.REVIEW && t.kind != Kind.CONTAINER) return Collections.emptyList();
        }
    }
    public static boolean isBook(String name) {
        return Arrays.asList("cbz", "zip", "pdf", "epub").contains(extension(name));
    }
    public static boolean isImage(String name) {
        return Arrays.asList("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heic", "heif", "jxl").contains(extension(name));
    }
    public static boolean isSidecar(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("comicinfo.xml") || n.equals("metadata.xml") || n.equals("index.json")
            || (isImage(n) && Arrays.asList("cover", "folder", "thumbnail").contains(n.substring(0, n.lastIndexOf('.'))));
    }
    public static String extension(String name) {
        int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    public static String displayName(String name, boolean showExtension, boolean directory) {
        if (showExtension || directory || !isBook(name)) return name;
        return name.substring(0, name.lastIndexOf('.'));
    }
    /** Decimal-aware, zero-padding-independent natural ordering; no integer overflow. */
    public static final Comparator<String> NATURAL = (left, right) -> {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            char x = left.charAt(a), y = right.charAt(b);
            if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
                int endA = numberEnd(left, a), endB = numberEnd(right, b);
                int cmp = new BigDecimal(left.substring(a, endA)).compareTo(new BigDecimal(right.substring(b, endB)));
                if (cmp != 0) return cmp;
                a = endA; b = endB;
            } else {
                int cmp = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (cmp != 0) return cmp;
                a++; b++;
            }
        }
        int remaining = Integer.compare(left.length() - a, right.length() - b);
        return remaining != 0 ? remaining : left.compareTo(right);
    };
    private static int numberEnd(String s, int start) {
        int i = start; boolean decimal = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' && !decimal && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) { decimal = true; i++; }
            else break;
        }
        return i;
    }
}
