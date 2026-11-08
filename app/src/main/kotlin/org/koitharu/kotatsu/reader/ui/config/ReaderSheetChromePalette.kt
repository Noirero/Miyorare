package org.koitharu.kotatsu.reader.ui.config

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.shape.MaterialShapeDrawable
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.MiyorareViewPalette
import org.koitharu.kotatsu.databinding.SheetReaderConfigBinding

/**
 * The Reader settings content is already Compose-themed. Only its native sheet chrome
 * still inherits Material XML colors, so keep this bridge local to ReaderConfigSheet.
 */
internal fun applyModernReaderSheetChrome(
    binding: SheetReaderConfigBinding,
    sheet: View?,
    palette: MiyorareViewPalette,
) {
    binding.headerBar.findViewById<TextView>(R.id.sh_textView_title)
        ?.setTextColor(palette.onSurface)
    for (id in intArrayOf(R.id.sh_button_close, R.id.sh_button_collapse)) {
        binding.headerBar.findViewById<ImageButton>(id)?.imageTintList =
            ColorStateList.valueOf(palette.onSurfaceVariant)
    }
    sheet ?: return
    // Preserve Material's rounded background and its elevation/drag behavior.
    when (val drawable = sheet.background?.mutate()) {
        is MaterialShapeDrawable -> {
            drawable.fillColor = ColorStateList.valueOf(palette.surface)
            sheet.background = drawable
        }
        is ColorDrawable -> sheet.setBackgroundColor(palette.surface)
        else -> sheet.backgroundTintList = ColorStateList.valueOf(palette.surface)
    }
}
