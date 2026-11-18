package dev.aoidoki.yeoldekeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

/** midnight-neko palette, shared with the rest of the repo. */
internal object Palette {
    const val BG = 0xFF0A0A0F.toInt()
    const val SURFACE = 0xFF1A1A24.toInt()
    const val MUTED = 0xFF2A2A3A.toInt()
    const val PRESSED = 0xFF3A3A52.toInt()
    const val ACCENT = 0xFF8B5CF6.toInt()
    const val ACCENT_PRESSED = 0xFF7240E0.toInt()
    const val FG = 0xFFE4E2F0.toInt()
    const val DIM = 0xFF9A95B5.toInt()
}

internal object Prefs {
    private const val FILE = "yeolde"
    private const val KEY_ENABLED = "translate"

    fun isTranslating(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setTranslating(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}

/**
 * A small QWERTY keyboard that rewrites what you type into Ye Olde English. Each time a word is
 * finished (space, punctuation, Enter) the sentence being written is run through [OldeEnglish] and
 * the text field is updated in place.
 */
class OldeKeyboardService : InputMethodService() {

    private enum class Shift { OFF, ONCE, LOCK }
    private enum class Look { LETTER, SPECIAL, ACCENT }

    private val handler = Handler(Looper.getMainLooper())
    private var translating = true
    private var translatable = true
    private var symbols = false
    private var shift = Shift.ONCE
    private var lastShiftTap = 0L

    private lateinit var toggle: TextView
    private lateinit var keyArea: LinearLayout

    // ----------------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------------

    override fun onCreateInputView(): View {
        translating = Prefs.isTranslating(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(
                dp(4) + insets.systemWindowInsetLeft,
                dp(4),
                dp(4) + insets.systemWindowInsetRight,
                dp(6) + insets.systemWindowInsetBottom,
            )
            insets
        }
        root.setPadding(dp(4), dp(4), dp(4), dp(6))

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), dp(2))
        }
        strip.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                setTextColor(Palette.DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        toggle = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener {
                translating = !translating
                Prefs.setTranslating(this@OldeKeyboardService, translating)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                refreshToggle()
            }
        }
        strip.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        root.addView(strip)

        keyArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyArea)

        refreshToggle()
        renderKeys()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        translating = Prefs.isTranslating(this)
        translatable = isTextField(info)
        symbols = false
        shift = Shift.OFF
        if (::keyArea.isInitialized) {
            refreshToggle()
            autoCapitalise()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRepeat()
        super.onFinishInputView(finishingInput)
    }

    /** Passwords, e-mail, URLs, numbers and raw terminals are typed exactly as written. */
    private fun isTextField(info: EditorInfo): Boolean {
        val type = info.inputType
        if (type and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_CLASS_TEXT) return false
        return when (type and android.text.InputType.TYPE_MASK_VARIATION) {
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            android.text.InputType.TYPE_TEXT_VARIATION_URI,
            android.text.InputType.TYPE_TEXT_VARIATION_FILTER,
            -> false
            else -> true
        }
    }

    // ----------------------------------------------------------------------------------------
    // Typing
    // ----------------------------------------------------------------------------------------

    private fun type(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        if (text.isNotEmpty() && LiveTranslator.isBoundary(text.last())) translateNow(atEnd = false)
    }

    private fun typeLetter(c: Char) {
        type(if (shift == Shift.OFF) c.toString() else c.uppercaseChar().toString())
        if (shift == Shift.ONCE) {
            shift = Shift.OFF
            renderKeys()
        }
    }

    private fun typeBoundary(text: String) {
        type(text)
        autoCapitalise()
    }

    private fun translateNow(atEnd: Boolean) {
        if (!translating || !translatable) return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            LiveTranslator.apply(ConnectionTarget(ic), atEnd)
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) else ic.commitText("", 1)
        autoCapitalise()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        translateNow(atEnd = true)
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val multiLine = info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!multiLine &&
            info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        autoCapitalise()
    }

    private fun autoCapitalise() {
        if (shift == Shift.LOCK || symbols) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        val want = if (info.inputType != 0 && ic.getCursorCapsMode(info.inputType) != 0) Shift.ONCE else Shift.OFF
        if (want != shift) {
            shift = want
            renderKeys()
        }
    }

    private fun tapShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            now - lastShiftTap < 350 -> Shift.LOCK
            shift == Shift.ONCE -> Shift.OFF
            else -> Shift.ONCE
        }
        lastShiftTap = now
        renderKeys()
    }

    private fun pickKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // ----------------------------------------------------------------------------------------
    // Layout
    // ----------------------------------------------------------------------------------------

    private fun refreshToggle() {
        toggle.text = getString(if (translating) R.string.toggle_on else R.string.toggle_off)
        toggle.setTextColor(if (translating) Palette.FG else Palette.DIM)
        toggle.background = roundRect(if (translating) Palette.ACCENT else Palette.MUTED, 17)
    }

    private fun renderKeys() {
        keyArea.removeAllViews()
        if (symbols) {
            row("1234567890".map { char(it) })
            row("@#$%&-+()/".map { char(it) })
            row(
                "*\"':;!?".map { char(it) } +
                    key("⌫", 1.5f, Look.SPECIAL, repeat = true, description = "Delete") { backspace() },
            )
        } else {
            row("qwertyuiop".map { letter(it) })
            row("asdfghjkl".map { letter(it) }, sidePadding = 0.5f)
            row(
                listOf(
                    key(if (shift == Shift.LOCK) "⇪" else "⇧", 1.5f, if (shift == Shift.OFF) Look.SPECIAL else Look.ACCENT, description = "Shift") { tapShift() },
                ) + "zxcvbnm".map { letter(it) } +
                    key("⌫", 1.5f, Look.SPECIAL, repeat = true, description = "Delete") { backspace() },
            )
        }
        row(
            listOf(
                key(if (symbols) "ABC" else "?123", 1.5f, Look.SPECIAL) {
                    symbols = !symbols
                    renderKeys()
                },
                key("🌐", 1.2f, Look.SPECIAL, description = "Switch keyboard") { pickKeyboard() },
                char(','),
                key(" ", 4f, Look.LETTER, description = "Space") { typeBoundary(" ") },
                char('.'),
                key("↵", 1.5f, Look.ACCENT, description = "Enter") { enter() },
            ),
        )
    }

    private fun row(keys: List<View>, sidePadding: Float = 0f) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (sidePadding > 0f) row.addView(spacer(sidePadding))
        keys.forEach { row.addView(it) }
        if (sidePadding > 0f) row.addView(spacer(sidePadding))
        keyArea.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun spacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(1), weight)
    }

    private fun letter(c: Char): View {
        val shown = if (shift == Shift.OFF) c.toString() else c.uppercaseChar().toString()
        return key(shown, 1f, Look.LETTER) { typeLetter(c) }
    }

    private fun char(c: Char): View =
        key(c.toString(), 1f, Look.LETTER) { if (c.isLetter()) typeLetter(c) else typeBoundary(c.toString()) }

    @SuppressLint("ClickableViewAccessibility")
    private fun key(
        label: String,
        weight: Float,
        look: Look,
        repeat: Boolean = false,
        description: String? = null,
        onPress: () -> Unit,
    ): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Palette.FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 2) 14f else 20f)
        contentDescription = description
        background = keyBackground(look)
        layoutParams = LinearLayout.LayoutParams(0, dp(50), weight).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPress()
                    if (repeat) startRepeat(onPress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopRepeat()
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun keyBackground(look: Look) = StateListDrawable().apply {
        val (rest, down) = when (look) {
            Look.LETTER -> Palette.SURFACE to Palette.PRESSED
            Look.SPECIAL -> Palette.MUTED to Palette.PRESSED
            Look.ACCENT -> Palette.ACCENT to Palette.ACCENT_PRESSED
        }
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(down, 8))
        addState(intArrayOf(), roundRect(rest, 8))
    }

    private fun roundRect(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private var repeatTask: Runnable? = null

    private fun startRepeat(action: () -> Unit) {
        stopRepeat()
        val task = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 45)
            }
        }
        repeatTask = task
        handler.postDelayed(task, 400)
    }

    private fun stopRepeat() {
        repeatTask?.let { handler.removeCallbacks(it) }
        repeatTask = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** Lets [LiveTranslator] edit the focused text field. */
    private class ConnectionTarget(private val ic: InputConnection) : TextTarget {
        override fun textBeforeCursor(max: Int) = ic.getTextBeforeCursor(max, 0)?.toString().orEmpty()
        override fun deleteBefore(count: Int) {
            ic.deleteSurroundingText(count, 0)
        }
        override fun insert(text: String) {
            ic.commitText(text, 1)
        }
    }
}
