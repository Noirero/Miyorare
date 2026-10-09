package org.koitharu.kotatsu.local.library;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.util.Objects;

/** Grants from the test APK UID. Must also load without the instrumentation target's Kotlin runtime. */
public class CoverFixtureGrantActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri tree = DocumentsContract.buildTreeDocumentUri("org.noirero.miyorare.test.cover-fixtures", "root");
        if (getIntent().getBooleanExtra("revoke", false)) {
            revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            grantUriPermission(Objects.requireNonNull(getIntent().getStringExtra("recipient")), tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
