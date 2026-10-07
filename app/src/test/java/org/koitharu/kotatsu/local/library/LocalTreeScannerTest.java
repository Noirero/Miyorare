package org.koitharu.kotatsu.local.library;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public final class LocalTreeScannerTest {
    @Test public void selectedRootCollectionContracts() throws Exception {
        assertTrue(LocalScannerScenarios.run() >= 30);
    }
}
