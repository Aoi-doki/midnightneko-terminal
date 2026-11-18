package dev.aoidoki.yeoldekeys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Sets the keyboard up, and lets you try the translation before (or without) switching to it. */
class MainActivity : Activity() {

    private lateinit var enableStatus: TextView
    private lateinit var pickStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
            addView(column)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }

        column.addView(text(getString(R.string.app_name), 30f, Palette.ACCENT, bold = true))
        column.addView(
            text("Whatever thou writest turns to Ye Olde English the moment a word is finished.", 15f, Palette.DIM),
            below(6),
        )

        enableStatus = text("", 14f, Palette.DIM)
        column.addView(
            card(
                "1 · Enable the keyboard",
                "Turn on “Ye Olde Keys” in your keyboard settings.",
                enableStatus,
                "Open keyboard settings",
            ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            below(22),
        )

        pickStatus = text("", 14f, Palette.DIM)
        column.addView(
            card(
                "2 · Switch to it",
                "Pick it from the list, or tap the globe key on any keyboard.",
                pickStatus,
                "Choose keyboard",
            ) { inputMethods().showInputMethodPicker() },
            below(14),
        )

        val toggle = Switch(this).apply {
            text = "Translate while I type"
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = Prefs.isTranslating(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setTranslating(this@MainActivity, on) }
        }
        column.addView(toggle, below(22))
        column.addView(
            text("Also on the keyboard’s top strip. Passwords, e-mail, web addresses and terminals are never touched.", 13f, Palette.DIM),
            below(4),
        )

        column.addView(text("Try it with the keyboard", 16f, Palette.FG, bold = true), below(26))
        column.addView(
            field("Type here…", multiLine = true),
            below(8),
        )

        column.addView(text("Or preview without it", 16f, Palette.FG, bold = true), below(26))
        val input = field("Do you know where you are going?", multiLine = true)
        val output = text("", 18f, Palette.ACCENT).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Palette.SURFACE, 12)
            setTextIsSelectable(true)
        }
        column.addView(input, below(8))
        column.addView(output, below(10))
        // Until something is typed, the example in the hint is what gets translated.
        fun refresh() {
            val source = input.text.toString().ifBlank { input.hint.toString() }
            output.text = OldeEnglish.translate(source, atEnd = true)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val enabled = inputMethods().enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        enableStatus.text = if (enabled) "✓ Enabled" else "Not enabled yet"
        enableStatus.setTextColor(if (enabled) Palette.ACCENT else Palette.DIM)
        val picked = current.startsWith("$packageName/")
        pickStatus.text = if (picked) "✓ In use" else "Not selected yet"
        pickStatus.setTextColor(if (picked) Palette.ACCENT else Palette.DIM)
    }

    // ----------------------------------------------------------------------------------------

    private fun inputMethods() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun card(title: String, body: String, status: TextView, button: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Palette.SURFACE, 14)
            addView(text(title, 17f, Palette.FG, bold = true))
            addView(text(body, 14f, Palette.DIM), below(4))
            addView(status, below(8))
            addView(
                Button(this@MainActivity).apply {
                    text = button
                    isAllCaps = false
                    setTextColor(Palette.FG)
                    background = rounded(Palette.ACCENT, 10)
                    setOnClickListener { onClick() }
                },
                below(10),
            )
        }

    private fun field(hint: String, multiLine: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(Palette.FG)
        setHintTextColor(Palette.DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Palette.SURFACE, 12)
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun below(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(topDp) }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
