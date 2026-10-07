package org.koitharu.kotatsu.local.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class LocalTreeScanner {

    public static final class Node {
        public final String uri;
        public final String name;
        public final boolean directory;
        public final long size;
        public final long modifiedAt;
        public final List<Node> children;

        public Node(String uri, String name, boolean directory, long size, long modifiedAt, List<Node> children) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.directory = directory;
            this.size = size;
            this.modifiedAt = modifiedAt;
            this.children = children == null ? List.of() : children;
        }
    }

    public enum Kind {
        DIRECTORY, CBZ, PDF, EPUB, IMAGE
    }

    public static final class Book {
        public final Node node;
        public final Kind kind;
        public final List<Node> pages;

        Book(Node node, Kind kind, List<Node> pages) {
            this.node = node;
            this.kind = kind;
            this.pages = pages;
        }
    }

    public static final class ScanResult {
        public final List<Book> books;
        public final List<Node> review;

        ScanResult(List<Book> books, List<Node> review) {
            this.books = books;
            this.review = review;
        }
    }

    private static final int MAX_DEPTH = 64;
    private static final Comparator<Node> NATURAL = (a, b) -> naturalCompare(a.name, b.name);

    private LocalTreeScanner() { }

    public static ScanResult scan(Collection<Node> roots, Collection<String> excludedUris, BooleanSupplier cancelled) {
        final List<Book> books = new ArrayList<>();
        final List<Node> review = new ArrayList<>();
        final Collection<String> excluded = excludedUris == null ? List.of() : excludedUris;
        for (Node root : roots) {
            checkCancelled(cancelled);
            walk(root, excluded, cancelled, books, review, 0);
        }
        books.sort((a, b) -> NATURAL.compare(a.node, b.node));
        review.sort(NATURAL);
        return new ScanResult(books, review);
    }

    private static void walk(
        Node node,
        Collection<String> excluded,
        BooleanSupplier cancelled,
        List<Book> books,
        List<Node> review,
        int depth
    ) {
        checkCancelled(cancelled);
        if (node == null || excluded.contains(node.uri)) return;
        if (depth > MAX_DEPTH) {
            review.add(node);
            return;
        }
        if (!node.directory) {
            final Kind kind = kindOf(node.name);
            if (kind != null && kind != Kind.IMAGE) books.add(new Book(node, kind, List.of()));
            return;
        }
        final List<Node> children = new ArrayList<>(node.children);
        children.sort(NATURAL);
        final List<Node> images = new ArrayList<>();
        final List<Node> containers = new ArrayList<>();
        final List<Node> directories = new ArrayList<>();
        for (Node child : children) {
            checkCancelled(cancelled);
            if (excluded.contains(child.uri)) continue;
            if (child.directory) {
                directories.add(child);
            } else {
                final Kind kind = kindOf(child.name);
                if (kind == Kind.IMAGE) images.add(child);
                else if (kind != null) containers.add(child);
            }
        }
        if (!images.isEmpty() && containers.isEmpty()) {
            books.add(new Book(node, Kind.DIRECTORY, images));
            return;
        }
        if (!images.isEmpty() && !containers.isEmpty()) {
            review.add(node);
            return;
        }
        for (Node container : containers) {
            final Kind kind = kindOf(container.name);
            if (kind != null) books.add(new Book(container, kind, List.of()));
        }
        for (Node directory : directories) {
            walk(directory, excluded, cancelled, books, review, depth + 1);
        }
    }

    private static Kind kindOf(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cbz") || lower.endsWith(".zip")) return Kind.CBZ;
        if (lower.endsWith(".pdf")) return Kind.PDF;
        if (lower.endsWith(".epub")) return Kind.EPUB;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".avif")) return Kind.IMAGE;
        return null;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException();
    }

    static int naturalCompare(String left, String right) {
        int i = 0, j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i), b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                long an = 0, bn = 0;
                while (i < left.length() && Character.isDigit(left.charAt(i))) an = an * 10 + (left.charAt(i++) - '0');
                while (j < right.length() && Character.isDigit(right.charAt(j))) bn = bn * 10 + (right.charAt(j++) - '0');
                if (an != bn) return Long.compare(an, bn);
            } else {
                int cmp = Character.compare(Character.toLowerCase(a), Character.toLowerCase(b));
                if (cmp != 0) return cmp;
                i++; j++;
            }
        }
        return Integer.compare(left.length(), right.length());
    }
}
