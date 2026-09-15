package org.koitharu.kotatsu.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import java.util.Locale

class MihonBackupOrderingTest {

  @Test
  fun dateAddedTieRanksFollowMihonTitleComparatorInsteadOfBackupOrder() {
    val manga = listOf(
      MihonBackupManga(source = 1L, url = "/z", title = "Zulu"),
      MihonBackupManga(source = 1L, url = "/a", title = "Alpha"),
      MihonBackupManga(source = 1L, url = "/m", title = "Mike"),
    )

    assertArrayEquals(
      intArrayOf(2, 0, 1),
      buildMihonDateAddedTieRanks(manga, Locale.US),
    )
  }

  @Test
  fun primaryCollatorKeepsBackupOrderWhenTitlesCompareEqual() {
    val manga = listOf(
      MihonBackupManga(source = 1L, url = "/accent", title = "Éclair"),
      MihonBackupManga(source = 1L, url = "/plain", title = "eclair"),
      MihonBackupManga(source = 1L, url = "/z", title = "Zulu"),
    )

    assertArrayEquals(
      intArrayOf(0, 1, 2),
      buildMihonDateAddedTieRanks(manga, Locale.US),
    )
  }
}
