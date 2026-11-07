package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.koitharu.kotatsu.R

/** Modal UI for source/target selection. The controller still owns the saved language codes. */
internal class NovelTranslationLanguagePicker(
    private val context: Context,
    private val source: Boolean,
    private val selectedCode: String,
    private val onSelected: (String) -> Unit,
    private val onCustomLanguage: () -> Unit,
) {
    private val visuals = NovelTranslationSheetVisuals(context)
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()

    fun show() {
        val choices = (if (source) NovelTranslationLanguages.sourceChoices()
            else NovelTranslationLanguages.targetChoices()).toMutableList()
        if (choices.none { it.code.equals(selectedCode, ignoreCase = true) } && selectedCode.isNotBlank()) {
            choices.add(0, NovelTranslationLanguage(selectedCode, selectedCode))
        }
        val customActionCode = "\u0000custom_language_action"
        val allChoices = choices + NovelTranslationLanguage(
            customActionCode, context.getString(R.string.novel_translation_custom_language),
        )

        lateinit var dialog: AlertDialog
        val adapter = LanguageAdapter(allChoices, selectedCode, customActionCode) { code ->
            dialog.dismiss()
            if (code == customActionCode) onCustomLanguage() else onSelected(code)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val search = TextInputLayout(context).apply {
            hint = context.getString(R.string.novel_translation_search_language)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = visuals.languagePickerAccent
            setBoxCornerRadii(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat())
            setStartIconDrawable(R.drawable.ic_search)
            setStartIconTintList(ColorStateList.valueOf(visuals.muted))
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setEndIconTintList(ColorStateList.valueOf(visuals.languagePickerAccent))
        }
        val input = TextInputEditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            setTextColor(visuals.onSurface)
            minimumHeight = dp(48)
        }
        search.addView(input, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) })

        val listContainer = FrameLayout(context)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
        }
        val initialHeight = minOf(dp(378), (context.resources.displayMetrics.heightPixels * 0.49f).toInt())
        listContainer.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val emptyHint = TextView(context).apply {
            setText(R.string.novel_translation_no_matching_languages)
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(visuals.muted)
            visibility = View.GONE
        }
        listContainer.addView(emptyHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(listContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, initialHeight,
        ))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
                emptyHint.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                list.scrollToPosition(0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val height = if (ime > 0) minOf(dp(245), initialHeight) else initialHeight
            val params = listContainer.layoutParams
            if (params.height != height) {
                params.height = height
                listContainer.layoutParams = params
            }
            insets
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (source) R.string.novel_translation_choose_source else R.string.novel_translation_choose_target)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(visuals.panelBackground())
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                )
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(visuals.languagePickerAccent)
            val selectedIndex = allChoices.indexOfFirst { it.code.equals(selectedCode, ignoreCase = true) }
            if (selectedIndex > 0) list.scrollToPosition(selectedIndex)
        }
        dialog.show()
    }

    private inner class LanguageAdapter(
        private val all: List<NovelTranslationLanguage>,
        private val selected: String,
        private val customCode: String,
        private val onTap: (String) -> Unit,
    ) : RecyclerView.Adapter<LanguageRow>() {
        private var visible = all
        init { setHasStableIds(true) }

        fun filter(query: String) {
            visible = NovelTranslationLanguagePickerModel.filter(all, query)
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = visible[position].code.hashCode().toLong()
        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageRow {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58)
                setPadding(dp(10), dp(6), dp(8), dp(6))
                isClickable = true
                isFocusable = true
            }
            val symbol = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(visuals.languagePickerAccent)
            }
            row.addView(symbol, LinearLayout.LayoutParams(dp(44), dp(36)).apply { marginEnd = dp(8) })
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(context).apply {
                textSize = 15f
                setTextColor(visuals.onSurface)
                maxLines = 3
            }
            val code = TextView(context).apply {
                textSize = 12f
                setTextColor(visuals.muted)
                maxLines = 1
            }
            labels.addView(title)
            labels.addView(code)
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val radio = MaterialRadioButton(context).apply {
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(visuals.languagePickerAccent, visuals.languagePickerOutline),
                )
                contentDescription = null
            }
            row.addView(radio, LinearLayout.LayoutParams(dp(44), dp(48)))
            return LanguageRow(row, symbol, title, code, radio)
        }

        override fun onBindViewHolder(holder: LanguageRow, position: Int) {
            val item = visible[position]
            val custom = item.code == customCode
            val chosen = !custom && item.code.equals(selected, ignoreCase = true)
            holder.itemView.apply {
                background = if (chosen) visuals.cardBackground(selected = true) else null
                isSelected = chosen
                contentDescription = item.name +
                    (if (custom) "" else ", " + item.code) +
                    (if (chosen) ", " + context.getString(R.string.novel_translation_language_selected) else "")
                setOnClickListener { onTap(item.code) }
            }
            holder.title.text = if (item.code == NovelTranslationSettings.LANGUAGE_AUTO) {
                context.getString(R.string.novel_translation_auto_detect)
            } else item.name
            holder.title.setTypeface(null, if (chosen) Typeface.BOLD else Typeface.NORMAL)
            holder.code.text = if (custom) "" else item.code
            holder.radio.visibility = if (custom) View.INVISIBLE else View.VISIBLE
            holder.radio.isChecked = chosen
            val glyph = if (custom) NovelLanguageSymbol.CodeBadge("···")
                else NovelTranslationLanguagePickerModel.symbol(item.code)
            holder.symbol.background = null
            when (glyph) {
                NovelLanguageSymbol.Globe -> {
                    holder.symbol.text = "🌐"
                    holder.symbol.textSize = 23f
                }
                is NovelLanguageSymbol.Flag -> {
                    holder.symbol.text = glyph.emoji
                    holder.symbol.textSize = 23f
                }
                is NovelLanguageSymbol.CodeBadge -> {
                    holder.symbol.text = glyph.text
                    holder.symbol.textSize = 11f
                    holder.symbol.background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat()
                        setColor(visuals.languagePickerBadgeFill())
                    }
                }
            }
        }
    }

    private class LanguageRow(
        row: LinearLayout,
        val symbol: TextView,
        val title: TextView,
        val code: TextView,
        val radio: MaterialRadioButton,
    ) : RecyclerView.ViewHolder(row)
}
