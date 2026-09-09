package org.koitharu.kotatsu.settings.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Background + foreground tint pair used for the circular icon container on each
 * settings card. Chosen so all categories read at a glance and stay legible against
 * surfaceContainer in both light and dark themes.
 */
@Immutable
data class CategoryIconColors(
	val container: Color,
	val onContainer: Color,
)

object CategoryPalette {
	val Appearance = CategoryIconColors(Color(0xFFE9DDFF), Color(0xFF22005D))
	val Extensions = CategoryIconColors(Color(0xFFFFD8E4), Color(0xFF31111D))
	val Reader = CategoryIconColors(Color(0xFFD1E4FF), Color(0xFF001D36))
	val Storage = CategoryIconColors(Color(0xFFB8F4D6), Color(0xFF002111))
	val Downloads = CategoryIconColors(Color(0xFFFFE0B2), Color(0xFF2E1500))
	val Backup = CategoryIconColors(Color(0xFFCDE9CB), Color(0xFF002201))
	val Private = CategoryIconColors(Color(0xFFE7D9F7), Color(0xFF2D1644))
	val Tracker = CategoryIconColors(Color(0xFFFFDAD6), Color(0xFF410002))
	val Services = CategoryIconColors(Color(0xFFE0E0FF), Color(0xFF0F1761))
	val About = CategoryIconColors(Color(0xFFDDE2EB), Color(0xFF1B1B1F))
	val Sync = CategoryIconColors(Color(0xFF5F6368), Color(0xFFFFFFFF))

	private val AppearanceDark = CategoryIconColors(Color(0xFF4F378B), Color(0xFFE9DDFF))
	private val ExtensionsDark = CategoryIconColors(Color(0xFF7D5260), Color(0xFFFFD8E4))
	private val ReaderDark = CategoryIconColors(Color(0xFF00497F), Color(0xFFD1E4FF))
	private val StorageDark = CategoryIconColors(Color(0xFF005233), Color(0xFFB8F4D6))
	private val DownloadsDark = CategoryIconColors(Color(0xFF6B4100), Color(0xFFFFE0B2))
	private val BackupDark = CategoryIconColors(Color(0xFF255A24), Color(0xFFCDE9CB))
	private val PrivateDark = CategoryIconColors(Color(0xFF4A315F), Color(0xFFE7D9F7))
	private val TrackerDark = CategoryIconColors(Color(0xFF93000A), Color(0xFFFFDAD6))
	private val ServicesDark = CategoryIconColors(Color(0xFF333DB1), Color(0xFFE0E0FF))
	private val AboutDark = CategoryIconColors(Color(0xFF45464F), Color(0xFFDDE2EB))
	private val SyncDark = CategoryIconColors(Color(0xFF5F6368), Color(0xFFFFFFFF))

	@Composable
	fun forKey(key: String): CategoryIconColors {
		val dark = MaterialTheme.colorScheme.surface.let {
			(it.red * 0.299f + it.green * 0.587f + it.blue * 0.114f) < 0.5f
		}
		return when (key) {
			"appearance" -> if (dark) AppearanceDark else Appearance
			"extensions" -> if (dark) ExtensionsDark else Extensions
			"reader" -> if (dark) ReaderDark else Reader
			"storage" -> if (dark) StorageDark else Storage
			"downloads" -> if (dark) DownloadsDark else Downloads
			"backup" -> if (dark) BackupDark else Backup
			"private" -> if (dark) PrivateDark else Private
			"tracker" -> if (dark) TrackerDark else Tracker
			"services" -> if (dark) ServicesDark else Services
			"about" -> if (dark) AboutDark else About
			"sync" -> if (dark) SyncDark else Sync
			else -> if (dark) AboutDark else About
		}
	}
}
