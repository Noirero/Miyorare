package org.koitharu.kotatsu.readerjourney.ui

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.readerjourney.domain.ReaderRank

@get:StringRes
val ReaderRank.titleRes: Int
	get() = when (this) {
		ReaderRank.NEWCOMER -> R.string.reader_rank_newcomer
		ReaderRank.READER -> R.string.reader_rank_reader
		ReaderRank.BOOKWORM -> R.string.reader_rank_bookworm
		ReaderRank.EXPLORER -> R.string.reader_rank_explorer
		ReaderRank.COLLECTOR -> R.string.reader_rank_collector
		ReaderRank.SCHOLAR -> R.string.reader_rank_scholar
		ReaderRank.ARCHIVIST -> R.string.reader_rank_archivist
		ReaderRank.BIBLIOPHILE -> R.string.reader_rank_bibliophile
		ReaderRank.VETERAN_READER -> R.string.reader_rank_veteran_reader
		ReaderRank.MASTER_READER -> R.string.reader_rank_master_reader
		ReaderRank.GRAND_READER -> R.string.reader_rank_grand_reader
		ReaderRank.LEGEND -> R.string.reader_rank_legend
	}
