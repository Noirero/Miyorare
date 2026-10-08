package org.koitharu.kotatsu.local.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract

/** Grants from the provider-owning test APK UID, just as a document picker would. */
class CoverFixtureGrantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tree = DocumentsContract.buildTreeDocumentUri("org.noirero.miyorare.test.cover-fixtures", "root")
        if (intent.getBooleanExtra("revoke", false)) {
            revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            grantUriPermission(requireNotNull(intent.getStringExtra("recipient")), tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        finish()
    }
}
