package org.koitharu.kotatsu.local.library;

import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class LocalTreeScannerTest {
    @Test public void isolatedFileRefreshDiscoversOnlyRequestedArchive() throws Exception {
        LocalTreeScanner.Node file = new LocalTreeScanner.Node("one", "file:///one.cbz",
            "one.cbz", false, 1024L, 1L);
        LocalTreeScanner.Result result = new LocalTreeScanner().scanFile(file);
        assertEquals(1, result.entries.size());
        assertEquals("one", result.entries.get(0).node.key);
        assertEquals(1, result.entries.get(0).chapters.size());
        assertEquals("one", result.entries.get(0).chapters.get(0).node.key);
    }

    @Test public void isolatedFileRefreshRejectsUnsupportedOrDirectoryNodes() {
        LocalTreeScanner scanner = new LocalTreeScanner();
        assertThrows(java.io.IOException.class, () -> scanner.scanFile(
            new LocalTreeScanner.Node("dir", "file:///dir", "dir", true, 0L, 0L)));
        assertThrows(java.io.IOException.class, () -> scanner.scanFile(
            new LocalTreeScanner.Node("txt", "file:///x.txt", "x.txt", false, 0L, 0L)));
    }

    @Test public void selectedRootCollectionContracts() throws Exception {
        assertTrue(LocalScannerScenarios.run() >= 30);
    }
}
