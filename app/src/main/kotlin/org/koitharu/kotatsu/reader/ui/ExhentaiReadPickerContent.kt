package org.koitharu.kotatsu.reader.ui

import android.content.Context
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.radiobutton.MaterialRadioButton
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.local.domain.DownloadedMangaResolver
import java.io.File

/** Request-local presentation only. Selection returns the original immutable candidate snapshot. */
internal class ExhentaiReadPickerContent(
    context: Context,
    private val choices: List<DownloadedMangaResolver.ExhentaiReadChoice>,
    private val canInteract: () -> Boolean,
) : ScrollView(context) {
    private val group = RadioGroup(context).apply {
        orientation = RadioGroup.VERTICAL
        val padding = resources.getDimensionPixelSize(R.dimen.screen_padding)
        setPadding(padding, 0, padding, padding)
    }
    private val radioIds = ArrayList<Int>(choices.size)
    var onSelectionChanged: () -> Unit = {}
    val selectedChoice: DownloadedMangaResolver.ExhentaiReadChoice?
        get() = choices.getOrNull(radioIds.indexOf(group.checkedRadioButtonId))
            ?.takeIf { it.readable && canInteract() }

    init {
        isFillViewport = false
        addView(group, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        choices.forEachIndexed { index, candidate ->
            val file = File(candidate.path)
            val galleryId = candidate.galleryId?.let {
                context.getString(R.string.exhentai_choose_local_known_id, it)
            } ?: context.getString(R.string.exhentai_choose_local_unknown_id)
            val size = Formatter.formatFileSize(context, candidate.length)
            val folder = file.parentFile?.name.orEmpty()
            val shortFolder = if (folder.length > 64) folder.take(36) + "…" + folder.takeLast(24) else folder
            val radio = MaterialRadioButton(context).apply {
                id = View.generateViewId()
                tag = "exhentai-choice-$index"
                text = buildString {
                    append(file.name).append('\n')
                    append(galleryId).append(" · ").append(size).append('\n')
                    append(shortFolder)
                    if (!candidate.readable) append('\n').append(context.getString(R.string.exhentai_picker_unreadable))
                }
                maxLines = if (candidate.readable) 4 else 5
                ellipsize = TextUtils.TruncateAt.END
                isEnabled = candidate.readable
            }
            radioIds += radio.id
            group.addView(radio, RadioGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val details = AppCompatTextView(context).apply {
                tag = "exhentai-path-$index"
                visibility = View.GONE
                setTextIsSelectable(false)
                setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.margin_small))
            }
            val expand = AppCompatButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                tag = "exhentai-details-$index"
                setText(R.string.exhentai_picker_show_details)
                setAllCaps(false)
                setOnClickListener {
                    if (!canInteract()) return@setOnClickListener
                    val opening = details.visibility != View.VISIBLE
                    details.text = if (opening) context.getString(
                        R.string.exhentai_picker_full_path, candidate.path,
                    ) else ""
                    details.visibility = if (opening) View.VISIBLE else View.GONE
                    setText(if (opening) R.string.exhentai_picker_hide_details else R.string.exhentai_picker_show_details)
                }
            }
            group.addView(expand, RadioGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            group.addView(details, RadioGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != View.NO_ID && selectedChoice == null) group.clearCheck()
            onSelectionChanged()
        }
    }
}
