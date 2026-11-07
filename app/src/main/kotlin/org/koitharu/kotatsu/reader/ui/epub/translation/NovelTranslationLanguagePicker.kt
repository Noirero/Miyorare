package org.koitharu.kotatsu.reader.ui.epub.translation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
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

    fun show(): AlertDialog {
        val choices = (if (source) NovelTranslationLanguages.sourceChoices()
            else NovelTranslationLanguages.targetChoices()).map { language ->
            if (language.code.equals(NovelTranslationSettings.LANGUAGE_AUTO, ignoreCase = true)) {
                language.copy(name = context.getString(R.string.novel_translation_auto_detect))
            } else language
        }.toMutableList()
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
            background = visuals.languagePickerDialogBackground()
            setPadding(dp(14), dp(14), dp(14), dp(10))
            isFocusableInTouchMode = true
        }
        root.addView(TextView(context).apply {
            setText(if (source) R.string.novel_translation_choose_source else R.string.novel_translation_choose_target)
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(visuals.onSurface)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
            bottomMargin = dp(11)
        })

        // Explicit capsule surface: never inherit the standard text-field underline.
        val search = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = visuals.languagePickerSearchBackground(focused = false)
        }
        val searchIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(visuals.muted)
            contentDescription = null
        }
        search.addView(searchIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
            marginStart = dp(14)
        })
        val input = AppCompatEditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            setTextColor(visuals.onSurface)
            setHintTextColor(visuals.muted)
            setHint(R.string.novel_translation_search_language)
            background = null
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            minimumHeight = dp(48)
        }
        search.addView(input, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        val clearSearch = TextView(context).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(visuals.languagePickerAccent)
            contentDescription = context.getString(R.string.novel_translation_clear_search)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { input.text?.clear() }
        }
        search.addView(clearSearch, LinearLayout.LayoutParams(dp(44), dp(48)))
        input.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            search.background = visuals.languagePickerSearchBackground(focused = hasFocus)
            searchIcon.imageTintList = ColorStateList.valueOf(
                if (hasFocus) visuals.languagePickerAccent else visuals.muted,
            )
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) })

        // Wrap short search results; cap long lists so only the list scrolls.
        val normalListHeight = minOf(dp(590), (context.resources.displayMetrics.heightPixels * 0.60f).toInt())
        var maxListHeight = normalListHeight
        val listContainer = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                    maxListHeight
                } else {
                    minOf(maxListHeight, View.MeasureSpec.getSize(heightMeasureSpec))
                }
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
                )
            }
        }.apply { minimumHeight = dp(55) }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
        }
        listContainer.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
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
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
                clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                emptyHint.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                list.scrollToPosition(0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val height = if (ime > 0) minOf(dp(245), normalListHeight) else normalListHeight
            if (maxListHeight != height) {
                maxListHeight = height
                listContainer.requestLayout()
            }
            insets
        }

        // The title and footer belong to this component, not Material's default alert chrome.
        val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        footer.addView(View(context).apply {
            setBackgroundColor(visuals.languagePickerDivider)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1),
        ))
        val footerActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        footerActions.addView(TextView(context).apply {
            setText(android.R.string.cancel)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(visuals.languagePickerAccent)
            minimumWidth = dp(64)
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
        ))
        footer.addView(footerActions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        dialog = MaterialAlertDialogBuilder(context)
            .setView(root)
            .create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    minOf(context.resources.displayMetrics.widthPixels - dp(28), dp(460)),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                )
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                )
            }
            val selectedIndex = allChoices.indexOfFirst { it.code.equals(selectedCode, ignoreCase = true) }
            if (selectedIndex > 0) {
                (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(selectedIndex, 0)
            }
        }
        dialog.show()
        return dialog
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
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(10), dp(4), dp(6), dp(4))
                isClickable = true
                isFocusable = true
            }
            val symbol = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(visuals.languagePickerAccent)
                maxLines = 1
            }
            row.addView(symbol, LinearLayout.LayoutParams(dp(36), dp(30)).apply {
                marginEnd = dp(9)
            })
            val title = TextView(context).apply {
                textSize = 14f
                setTextColor(visuals.onSurface)
                maxLines = 2 // Long names may wrap; code stays inline.
                includeFontPadding = false
            }
            row.addView(title, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ))
            val radio = MaterialRadioButton(context).apply {
                isClickable = false
                isFocusable = false
                gravity = Gravity.CENTER_VERTICAL
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(visuals.languagePickerAccent, visuals.languagePickerIdleRadio),
                )
                contentDescription = null
            }
            row.addView(radio, LinearLayout.LayoutParams(dp(40), dp(44)))
            return LanguageRow(row, symbol, title, radio)
        }

        override fun onBindViewHolder(holder: LanguageRow, position: Int) {
            val item = visible[position]
            val custom = item.code == customCode
            val chosen = !custom && item.code.equals(selected, ignoreCase = true)
            holder.itemView.apply {
                background = if (chosen) visuals.languagePickerSelectedBackground() else null
                elevation = if (chosen) dp(2).toFloat() else 0f
                isSelected = chosen
                contentDescription = item.name +
                    (if (custom) "" else ", " + item.code) +
                    (if (chosen) ", " + context.getString(R.string.novel_translation_language_selected) else "")
                setOnClickListener { onTap(item.code) }
            }
            holder.title.text = if (custom) item.name else item.name + " (" + item.code + ")"
            holder.title.setTypeface(null, if (chosen) Typeface.BOLD else Typeface.NORMAL)
            holder.radio.visibility = if (custom) View.INVISIBLE else View.VISIBLE
            holder.radio.isChecked = chosen
            val glyph = if (custom) NovelLanguageSymbol.CodeBadge("···")
                else NovelTranslationLanguagePickerModel.symbol(item.code)
            holder.symbol.background = null
            when (glyph) {
                NovelLanguageSymbol.Globe -> {
                    holder.symbol.text = "🌐"
                    holder.symbol.textSize = 22f
                }
                is NovelLanguageSymbol.Flag -> {
                    holder.symbol.text = glyph.emoji
                    holder.symbol.textSize = 22f
                }
                is NovelLanguageSymbol.CodeBadge -> {
                    holder.symbol.text = glyph.text
                    holder.symbol.textSize = if (glyph.text.length > 3) 9f else 11f
                    holder.symbol.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
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
        val radio: MaterialRadioButton,
    ) : RecyclerView.ViewHolder(row)
}
