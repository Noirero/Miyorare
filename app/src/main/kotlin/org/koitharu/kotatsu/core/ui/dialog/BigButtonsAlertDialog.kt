package org.koitharu.kotatsu.core.ui.dialog

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.databinding.DialogTwoButtonsBinding

class BigButtonsAlertDialog private constructor(
	private val delegate: AlertDialog
) : DialogInterface by delegate {

	fun show() = delegate.show()

	class Builder(context: Context) {

		private val binding = DialogTwoButtonsBinding.inflate(LayoutInflater.from(context))

		private val delegate = MaterialAlertDialogBuilder(context)
			.setView(binding.root)

		init {
			// MaterialButton and XML drawable inflation bypass the semantic View colour bridge.
			// Rebind only the positive/tonal actions and icon: the outlined negative button
			// deliberately retains its existing appearance.
			context.miyorareViewPaletteFromPreferences()?.let { palette ->
				binding.icon.imageTintList = ColorStateList.valueOf(palette.primary)
				binding.icon.backgroundTintList = ColorStateList.valueOf(palette.primaryContainer)
				binding.title.setTextColor(palette.onSurface)
				binding.button1.backgroundTintList = ColorStateList.valueOf(palette.primary)
				binding.button1.setTextColor(palette.onPrimary)
				binding.button2.backgroundTintList = ColorStateList.valueOf(palette.primaryContainer)
				binding.button2.setTextColor(palette.onPrimaryContainer)
				// Preserve Material's existing dialog shape, elevation and window insets.
				(delegate.background as? MaterialShapeDrawable)
					?.setFillColor(ColorStateList.valueOf(palette.surface))
			}
		}

		fun setTitle(@StringRes titleResId: Int): Builder {
			binding.title.setText(titleResId)
			return this
		}

		fun setTitle(title: CharSequence): Builder {
			binding.title.text = title
			return this
		}

		fun setIcon(@DrawableRes iconId: Int): Builder {
			binding.icon.setImageResource(iconId)
			return this
		}

		fun setPositiveButton(
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener,
		): Builder {
			initButton(binding.button1, DialogInterface.BUTTON_POSITIVE, textId, listener)
			return this
		}

		fun setNegativeButton(
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener? = null
		): Builder {
			initButton(binding.button3, DialogInterface.BUTTON_NEGATIVE, textId, listener)
			return this
		}

		fun setNeutralButton(
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener? = null
		): Builder {
			initButton(binding.button2, DialogInterface.BUTTON_NEUTRAL, textId, listener)
			return this
		}

		fun create(): BigButtonsAlertDialog {
			val dialog = delegate.create()
			binding.root.tag = dialog
			return BigButtonsAlertDialog(dialog)
		}

		private fun initButton(
			button: MaterialButton,
			which: Int,
			@StringRes textId: Int,
			listener: DialogInterface.OnClickListener?,
		) {
			button.setText(textId)
			button.isVisible = true
			button.setOnClickListener {
				val dialog = binding.root.tag as DialogInterface
				listener?.onClick(dialog, which)
				dialog.dismiss()
			}
		}
	}
}
