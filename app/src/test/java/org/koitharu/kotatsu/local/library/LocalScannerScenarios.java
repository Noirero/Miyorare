package org.koitharu.kotatsu.local.library;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.koitharu.kotatsu.local.library.LocalTreeScanner.*;

/** Executable without Android/Gradle; also run by the standard JVM suite. */
public final class LocalScannerScenarios {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static Node n(String path, boolean dir) { return new Node(path, "file:" + path, path.substring(path.lastIndexOf('/') + 1), dir, 3, 10); }
    private static final class MemoryTree implements Access {
        final Map<String, List<Node>> children = new HashMap<>();
        final List<String> visited = new ArrayList<>();
        Node root = n("/selected", true);
        Node dir(String path) { Node node = n(path, true); add(node); children.putIfAbsent(path, new ArrayList<>()); return node; }
        Node file(String path) { Node node = n(path, false); add(node); return node; }
        private void add(Node node) {
            String parent = node.key.substring(0, node.key.lastIndexOf('/'));
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
        }
        public List<Node> children(Node directory) { visited.add(directory.key); return new ArrayList<>(children.getOrDefault(directory.key, Collections.emptyList())); }
        public boolean contains(Node root, Node child) { return child.key.equals(root.key) || child.key.startsWith(root.key + "/"); }
        public void checkCancelled() {}
        Result scan(Set<String> confirmed, Set<String> excluded) throws IOException { return new LocalTreeScanner().scan(root, this, confirmed, excluded); }
        Result scan() throws IOException { return scan(Collections.emptySet(), Collections.emptySet()); }
    }
    public static int run() throws Exception {
        checks = 0;
        MemoryTree t = new MemoryTree();
        t.dir("/selected/Naruto"); t.file("/selected/Naruto/Chapter 10.cbz"); t.file("/selected/Naruto/Chapter 2.zip");
        t.dir("/selected/Naruto/Chapter 1"); t.file("/selected/Naruto/Chapter 1/10.png"); t.file("/selected/Naruto/Chapter 1/2.webp");
        Result result = t.scan();
        check(result.entries.size() == 1, "Mixed chapter representations must form one manga without XML");
        Entry manga = result.entries.get(0);
        check(manga.chapters.size() == 3, "All supported chapters found");
        check(manga.chapters.get(0).node.name.equals("Chapter 1"), "Chapter 1 sorts first");
        check(manga.chapters.get(1).node.name.equals("Chapter 2.zip"), "Chapter 2 before 10");
        check(manga.chapters.get(0).pages.get(0).name.equals("2.webp"), "Image folder page sorting");
        check(t.visited.stream().allMatch(p -> p.equals("/selected") || p.startsWith("/selected/")), "Only selected root walked");
        t.file("/other/Leak.cbz"); check(t.scan().entries.size() == 1, "Sibling storage must not be scanned");
        Result excluded = t.scan(Collections.emptySet(), Collections.singleton("/selected/Naruto"));
        check(excluded.entries.isEmpty(), "Excluded manga must not reappear on rescan");
        check(!LocalTreeScanner.displayName("Chapter 2.zip", false, false).endsWith(".zip"), "Extension OFF");
        check(LocalTreeScanner.displayName("Chapter 2.zip", true, false).equals("Chapter 2.zip"), "Extension ON");
        check(LocalTreeScanner.displayName("Arc.1", false, true).equals("Arc.1"), "Directory periods preserved");
        check(manga.chapters.get(1).node.name.equals("Chapter 2.zip"), "Display does not mutate original name");

        MemoryTree nested = new MemoryTree();
        nested.dir("/selected/AnySource"); nested.dir("/selected/AnySource/One Piece"); nested.dir("/selected/AnySource/Berserk");
        nested.file("/selected/AnySource/One Piece/Chapter 1.cbz"); nested.file("/selected/AnySource/Berserk/Chapter 1.cbz");
        Result container = nested.scan();
        check(container.entries.size() == 2, "Multiple-title container skipped by structure, no source names");
        check(container.entries.stream().noneMatch(e -> e.node.name.equals("AnySource")), "Container is never a manga");
        nested.visited.clear();
        Result hiddenSibling = nested.scan(Collections.emptySet(), Collections.singleton("/selected/AnySource/Berserk"));
        check(hiddenSibling.entries.size() == 1 && hiddenSibling.entries.get(0).node.name.equals("One Piece"), "Hiding a title cannot reclassify its remaining sibling");
        check(!nested.visited.contains("/selected/AnySource/Berserk"), "Excluded title is not walked");
        check(nested.scan().entries.size() == 2, "Restoring a nested exclusion restores both titles");

        MemoryTree chain = new MemoryTree();
        chain.dir("/selected/Collection"); chain.dir("/selected/Collection/Language"); chain.dir("/selected/Collection/Language/One Piece");
        chain.dir("/selected/Collection/Language/One Piece/East Blue"); chain.file("/selected/Collection/Language/One Piece/East Blue/Chapter 1.cbz");
        Result review = chain.scan();
        check(review.entries.isEmpty(), "Unprovable single-child title boundaries are not guessed");
        check(review.issues.size() == 1 && review.issues.get(0).candidates.size() == 4, "Chain has explicit review candidates");
        Result resolved = chain.scan(Collections.singleton("/selected/Collection/Language/One Piece"), Collections.emptySet());
        check(resolved.entries.size() == 1 && resolved.entries.get(0).node.name.equals("One Piece"), "Confirmed manga boundary is honored through nested containers");
        check(resolved.entries.get(0).chapters.get(0).node.name.equals("Chapter 1.cbz"), "Arc chapter retained");

        MemoryTree books = new MemoryTree();
        books.file("/selected/Berserk.cbz"); books.file("/selected/Monster.pdf"); books.file("/selected/Overlord.epub");
        books.dir("/selected/Monster"); books.file("/selected/Monster/Volume 02.pdf"); books.file("/selected/Monster/Volume 01.pdf");
        check(books.scan().entries.size() == 4, "Single CBZ/PDF/EPUB and multi-PDF volume folder");
        check(books.scan().entries.stream().filter(e -> e.node.name.equals("Monster")).findFirst().get().chapters.get(0).node.name.equals("Volume 01.pdf"), "Volume title preserved");
        MemoryTree corruptMetadata = new MemoryTree();
        corruptMetadata.dir("/selected/Book"); corruptMetadata.file("/selected/Book/ComicInfo.xml"); corruptMetadata.file("/selected/Book/Chapter 1.zip");
        check(corruptMetadata.scan().entries.get(0).chapters.size() == 1, "Metadata presence does not decide chapter availability");
        MemoryTree mixed = new MemoryTree();
        mixed.dir("/selected/Ambiguous"); mixed.file("/selected/Ambiguous/1.jpg"); mixed.file("/selected/Ambiguous/Chapter 1.cbz");
        check(mixed.scan().entries.isEmpty() && mixed.scan().issues.get(0).reason.equals("review"), "Loose images mixed with chapters fail safe");
        check(mixed.scan().issues.get(0).candidates.isEmpty(), "No unapproved classification rule for mixed structure");

        List<String> sorted = new ArrayList<>(Arrays.asList("Chapter 10", "Chapter 2", "Chapter 1.5", "Chapter 1"));
        sorted.sort(LocalTreeScanner.NATURAL);
        check(sorted.equals(Arrays.asList("Chapter 1", "Chapter 1.5", "Chapter 2", "Chapter 10")), "Natural decimal sorting");
        check(LocalTreeScanner.NATURAL.compare("Ch 0002", "Ch 10") < 0, "Zero-padded natural sorting");
        check(LocalTreeScanner.NATURAL.compare("Ch 99999999999999999999999", "Ch 3") > 0, "Large numbers cannot overflow");

        MemoryTree escape = new MemoryTree();
        escape.children.put("/selected", new ArrayList<>(Collections.singletonList(n("/outside/Escape", true))));
        try { escape.scan(); throw new AssertionError("Escaped child must be rejected"); }
        catch (IOException expected) { check(!escape.visited.contains("/outside/Escape"), "Escaped directory is never listed"); }
        MemoryTree cycle = new MemoryTree(); cycle.dir("/selected/A"); cycle.children.get("/selected/A").add(cycle.root);
        try { cycle.scan(); throw new AssertionError("Cycle must be rejected"); }
        catch (IOException expected) { check(cycle.visited.size() == 2, "Cycle cannot recurse or hang"); }
        MemoryTree deep = new MemoryTree(); String path = "/selected";
        for (int i = 0; i < 1500; i++) { path += "/x"; deep.dir(path); }
        deep.file(path + "/1.cbz");
        check(deep.scan().issues.size() == 1, "Deep tree uses a stack rather than recursive calls");
        check(t.scan().entries.size() == 1, "Restoring exclusion discovers original manga again");
        MemoryTree epubs = new MemoryTree(); epubs.dir("/selected/Overlord");
        epubs.file("/selected/Overlord/Volume 02.epub"); epubs.file("/selected/Overlord/Volume 01.epub");
        check(epubs.scan().entries.size() == 1 && epubs.scan().entries.get(0).chapters.size() == 2, "EPUB volumes form a collection");
        check(epubs.scan().entries.get(0).chapters.get(0).node.name.equals("Volume 01.epub"), "EPUB volumes naturally sorted");
        epubs.file("/selected/Overlord/Chapter 3.cbz");
        check(epubs.scan().entries.isEmpty() && epubs.scan().issues.get(0).reason.equals("review"), "Conflicting text/image structure fails safe");
        check(t.scan().entries.size() + books.scan().entries.size() == 5, "Independent roots combine without scanning ancestors");
        try {
            new LocalTreeScanner().scan(n("/selected/book.cbz", false), t, Collections.emptySet(), Collections.emptySet());
            throw new AssertionError("A file is not a selected root");
        } catch (IOException expected) { check(true, "Root must be an explicit directory"); }
        return checks;
    }
    public static void main(String[] args) throws Exception { System.out.println("PASS: " + run() + " scanner assertions"); }
}
