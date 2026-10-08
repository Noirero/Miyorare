package org.koitharu.kotatsu.local.library;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real seekable SAF fixtures in the test APK only. Counts source opens and metadata queries.
 * Android starts this APK in its own process, outside the instrumentation target's classloader.
 * Keep this component Android/JDK-only: shared Kotlin dependencies live in the target APK.
 */
public class CoverFixtureDocumentsProvider extends DocumentsProvider {
    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicInteger opens = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> queriedDocuments = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> queriedChildren = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> openedDocuments = new ConcurrentLinkedQueue<>();
    private long revision = System.currentTimeMillis();

    private File directory() {
        File directory = new File(Objects.requireNonNull(getContext()).getFilesDir(), "cover-fixtures");
        directory.mkdirs();
        return directory;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
        });
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Root.COLUMN_ROOT_ID:
                case Root.COLUMN_DOCUMENT_ID: row.add("root"); break;
                case Root.COLUMN_TITLE: row.add("Cover fixtures"); break;
                case Root.COLUMN_FLAGS: row.add(Root.FLAG_SUPPORTS_IS_CHILD); break;
                default: row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        queries.incrementAndGet();
        queriedDocuments.add(documentId);
        MatrixCursor cursor = cursor(projection);
        File file = file(documentId);
        if (file.exists()) add(cursor, file, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        queries.incrementAndGet();
        queriedChildren.add(parentDocumentId);
        MatrixCursor cursor = cursor(projection);
        File[] children = file(parentDocumentId).listFiles();
        if (children != null) {
            Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
            File directory = directory();
            for (File child : children) {
                String id = child.getAbsolutePath().substring(directory.getAbsolutePath().length() + 1)
                    .replace(File.separatorChar, '/');
                add(cursor, child, id);
            }
        }
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return canonicalPath(file(documentId)).startsWith(canonicalPath(file(parentDocumentId)) + File.separator);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
        throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read-only fixture");
        opens.incrementAndGet();
        openedDocuments.add(documentId);
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "fixture-reset":
                deleteRecursively(directory());
                // Reset storage and operation evidence together; metric-only resets keep files.
            case "fixture-metrics-reset":
                queries.set(0);
                opens.set(0);
                queriedDocuments.clear();
                queriedChildren.clear();
                openedDocuments.clear();
                return new Bundle();
            case "fixture-counts":
                Bundle counts = new Bundle();
                counts.putInt("queries", queries.get());
                counts.putInt("opens", opens.get());
                counts.putStringArrayList("queried-documents", new ArrayList<>(queriedDocuments));
                counts.putStringArrayList("queried-children", new ArrayList<>(queriedChildren));
                counts.putStringArrayList("opened-documents", new ArrayList<>(openedDocuments));
                return counts;
            case "fixture-put":
                put(Objects.requireNonNull(arg), Objects.requireNonNull(extras));
                return new Bundle();
            default:
                return super.call(method, arg, extras);
        }
    }

    private void put(String id, Bundle data) {
        File file = file(id);
        Objects.requireNonNull(file.getParentFile()).mkdirs();
        byte[] bytes = Objects.requireNonNull(data.getByteArray("bytes"));
        int remaining = data.getInt("padding");
        try (FileOutputStream output = new FileOutputStream(file)) {
            // Grow a valid PDF comment before its footer, preserving existing xref offsets.
            // Appending megabytes after %%EOF would instead create a malformed fixture.
            int footer = remaining > 0 ? new String(bytes, StandardCharsets.ISO_8859_1).lastIndexOf("startxref") : -1;
            if (footer >= 0) {
                output.write(bytes, 0, footer);
                output.write('\n');
                output.write('%');
                byte[] padding = new byte[8192];
                Arrays.fill(padding, (byte) 'x');
                while (remaining > 0) {
                    int count = Math.min(padding.length, remaining);
                    output.write(padding, 0, count);
                    remaining -= count;
                }
                output.write('\n');
                output.write(bytes, footer, bytes.length - footer);
            } else {
                output.write(bytes);
            }
            // Build large valid image fixtures in the provider process, avoiding Binder's
            // transaction limit and multi-megabyte committed assets. GIF comment sub-blocks
            // and WebP JUNK chunks are supplied by the test; no image pixels are rewritten.
            byte[] repeated = data.getByteArray("repeat-block");
            if (repeated != null) {
                for (int i = 0; i < data.getInt("repeat-count"); i++) output.write(repeated);
                byte[] suffix = data.getByteArray("suffix");
                if (suffix != null) output.write(suffix);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write cover fixture", error);
        }
        file.setLastModified(++revision);
    }

    private File file(String id) {
        File directory = directory();
        File file = "root".equals(id) ? directory : new File(directory, id);
        String path = canonicalPath(file);
        String root = canonicalPath(directory);
        if (!path.equals(root) && !path.startsWith(root + File.separator)) {
            throw new IllegalStateException("Fixture outside root");
        }
        return file;
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot resolve cover fixture", error);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private static MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        });
    }

    private static void add(MatrixCursor cursor, File file, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(file.getName()); break;
                case Document.COLUMN_MIME_TYPE:
                    String name = file.getName();
                    row.add(file.isDirectory() ? Document.MIME_TYPE_DIR : name.endsWith(".pdf") ? "application/pdf" :
                        name.endsWith(".epub") ? "application/epub+zip" : "application/octet-stream");
                    break;
                case Document.COLUMN_SIZE: row.add(file.isFile() ? file.length() : 0L); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(file.lastModified()); break;
                case Document.COLUMN_FLAGS: row.add(0); break;
                default: row.add(null);
            }
        }
    }
}
