package com.fct.tc4.ui.misc

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.termux.view.TerminalView

/**
 * Minimal extra-keys bar compatible with NoxarTerm's TerminalFragment wiring,
 * backed by Termux terminal engine (TerminalView/TerminalViewClient modifiers).
 */
class TermuxExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onToggleIme: (() -> Unit)? = null

    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false
    private var fnActive = false

    private val buttons = mutableListOf<Button>()
    private var ctrlButton: Button? = null
    private var altButton: Button? = null
    private var shiftButton: Button? = null
    private var fnButton: Button? = null

    init {
        orientation = VERTICAL
        buildDefaultKeys()
    }

    fun attachTerminalView(@Suppress("UNUSED_PARAMETER") terminalView: TerminalView) {
        // Termux engine queries modifier state via TerminalViewClient; no direct attach needed.
    }

    fun setTextColor(color: Int) {
        buttons.forEach { it.setTextColor(color) }
        updateModifierStyles()
    }

    fun readControlButton(): Boolean = consume(ctrlButton) { ctrlActive = false }
    fun readAltButton(): Boolean = consume(altButton) { altActive = false }
    fun readShiftButton(): Boolean = consume(shiftButton) { shiftActive = false }
    fun readFnButton(): Boolean = consume(fnButton) { fnActive = false }

    fun peekControl(): Boolean = ctrlActive
    fun peekAlt(): Boolean = altActive
    fun peekShift(): Boolean = shiftActive
    fun peekFn(): Boolean = fnActive

    private fun consume(btn: Button?, clear: () -> Unit): Boolean {
        val v = when (btn) {
            ctrlButton -> ctrlActive
            altButton -> altActive
            shiftButton -> shiftActive
            fnButton -> fnActive
            else -> false
        }
        if (v) {
            clear()
            updateModifierStyles()
        }
        return v
    }

    private fun buildDefaultKeys() {
        removeAllViews()
        buttons.clear()

        val row1 = newRow()
        val row2 = newRow()
        addView(row1)
        addView(row2)

        addKey(row1, "ESC") { sendKey(android.view.KeyEvent.KEYCODE_ESCAPE) }
        addKey(row1, "TAB") { sendKey(android.view.KeyEvent.KEYCODE_TAB) }
        ctrlButton = addToggle(row1, "CTRL") { ctrlActive = !ctrlActive; updateModifierStyles() }
        altButton = addToggle(row1, "ALT") { altActive = !altActive; updateModifierStyles() }
        addKey(row1, "END") { sendKey(android.view.KeyEvent.KEYCODE_MOVE_END) }
        addKey(row1, "/") { sendKey(android.view.KeyEvent.KEYCODE_SLASH) }

        addKey(row2, "←") { sendKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT) }
        addKey(row2, "↑") { sendKey(android.view.KeyEvent.KEYCODE_DPAD_UP) }
        addKey(row2, "↓") { sendKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        addKey(row2, "→") { sendKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT) }
        addKey(row2, "HOME") { sendKey(android.view.KeyEvent.KEYCODE_MOVE_HOME) }
        addKey(row2, "IME") { onToggleIme?.invoke() }

        updateModifierStyles()
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun addKey(row: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = makeButton(label)
        b.setOnClickListener { onClick() }
        row.addView(b)
        buttons.add(b)
        return b
    }

    private fun addToggle(row: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = makeButton(label)
        b.setOnClickListener { onClick() }
        row.addView(b)
        buttons.add(b)
        return b
    }

    private fun addSpacer(row: LinearLayout) {
        val v = View(context)
        v.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        v.visibility = View.INVISIBLE
        row.addView(v)
    }

    private fun makeButton(label: String): Button = Button(context, null, android.R.attr.buttonStyleSmall).apply {
        text = label
        isAllCaps = false
        minWidth = dp(40)
        minimumWidth = dp(40)
        minHeight = dp(38)
        minimumHeight = dp(38)
        textSize = 13f
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(3)
            topMargin = dp(1)
            bottomMargin = dp(1)
        }
    }

    private fun sendKey(keyCode: Int) {
        val tv = findTerminalView() ?: return
        tv.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        tv.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    private fun findTerminalView(): TerminalView? {
        val root = rootView ?: return null
        val id = resources.getIdentifier("terminal_view", "id", context.packageName)
        return if (id != 0) root.findViewById(id) else null
    }

    private fun updateModifierStyles() {
        fun style(btn: Button?, active: Boolean) {
            btn ?: return
            btn.alpha = if (active) 1.0f else 0.75f
            btn.isSelected = active
        }
        style(ctrlButton, ctrlActive)
        style(altButton, altActive)
        style(shiftButton, shiftActive)
        style(fnButton, fnActive)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
