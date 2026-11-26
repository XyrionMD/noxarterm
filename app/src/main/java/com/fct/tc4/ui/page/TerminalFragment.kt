package com.fct.tc4.ui.page

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4FragmentTerminalBinding
import com.fct.tc4.service.ShizukuShell
import com.fct.tc4.ui.misc.Global
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TerminalFragment : Fragment() {

    private var _binding: Tc4FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private enum class Mode { LINUX, ADB }
    private var currentMode: Mode = Mode.LINUX

    private var terminalTextSizePx: Int = 0

    private fun fontSizeDefaults(): IntArray {
        val dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
        val min = (4f * dipInPixels).roundToInt().coerceAtLeast(1)
        var defaultSize = (12f * dipInPixels).roundToInt()
        if (defaultSize % 2 == 1) defaultSize--
        val max = 256
        return intArrayOf(defaultSize, min, max)
    }

    private fun resolveTerminalTextSizePx(): Int {
        val sizes = fontSizeDefaults()
        val def = sizes[0]
        val min = sizes[1]
        val max = sizes[2]
        val stored = Global.terminalFontSize
        // Migrate legacy/raw default (42 from old engine) or out-of-range values.
        val base = if (stored == 42) def else stored
        return base.coerceIn(min, max)
    }

    private fun changeFontSize(increase: Boolean) {
        val sizes = fontSizeDefaults()
        val min = sizes[1]
        val max = sizes[2]
        var size = terminalTextSizePx + if (increase) 2 else -2
        size = size.coerceIn(min, max)
        if (size != terminalTextSizePx) {
            terminalTextSizePx = size
            Global.terminalFontSize = terminalTextSizePx
            binding.terminalView.setTextSize(terminalTextSizePx)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = Tc4FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLinuxTerminal()
        setupAdbConsole()
        setupControls()
        applyMode(Mode.LINUX, initial = true)
    }

    private fun setupLinuxTerminal() {
        terminalTextSizePx = resolveTerminalTextSizePx()
        val session = Global.terminalSession
        binding.terminalView.setTextSize(terminalTextSizePx)
        binding.terminalView.setBackgroundColor(0xFF000000.toInt())

        try {
            val tf = android.graphics.Typeface.createFromAsset(requireContext().assets, "fonts/font.ttf")
            binding.terminalView.setTypeface(tf)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (session != null) {
            binding.terminalView.attachSession(session)
        }

        val tv = TypedValue()
        val theme = requireContext().theme
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
        val fgColor = tv.data

        Global.terminalSession?.emulator?.mColors?.mCurrentColors?.let { colors ->
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = fgColor
        }
        binding.terminalView.onScreenUpdated()

        val ta = theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val ekColor = ta.getColor(0, Color.WHITE)
        ta.recycle()
        binding.extraKeys.setTextColor(ekColor)

        binding.terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    val increase = scale > 1f
                    changeFontSize(increase)
                    return 1.0f
                }
                return scale
            }

            override fun onSingleTapUp(e: MotionEvent) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                binding.terminalView.requestFocus()
                imm.showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = binding.extraKeys.readControlButton()
            override fun readAltKey(): Boolean = binding.extraKeys.readAltButton()
            override fun readShiftKey(): Boolean = binding.extraKeys.readShiftButton()
            override fun readFnKey(): Boolean = binding.extraKeys.readFnButton()
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) { Log.e(tag ?: "TermView", message ?: "") }
            override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "TermView", message ?: "") }
            override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "TermView", message ?: "") }
            override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "TermView", message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "TermView", message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: "TermView", message, e) }
            override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: "TermView", "stack", e) }
        })

        lifecycleScope.launch {
            Global.screenUpdateEvent.collect {
                if (_binding != null) binding.terminalView.onScreenUpdated()
            }
        }

        binding.extraKeys.attachTerminalView(binding.terminalView)
        binding.extraKeys.onToggleIme = {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val v = binding.terminalView
            val isKeyboardVisible = ViewCompat.getRootWindowInsets(requireActivity().window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (isKeyboardVisible) imm.hideSoftInputFromWindow(v.windowToken, 0)
            else {
                v.requestFocus()
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun setupAdbConsole() {
        binding.adbOutput.setBackgroundColor(0xFF000000.toInt())
        appendAdbLine("# ${getString(R.string.tc4_terminal_mode_adb_desc)}")
        if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission()) {
            appendAdbLine("# ${getString(R.string.tc4_terminal_shizuku_unavailable)}")
        }

        fun runCommand() {
            val cmd = binding.adbInput.text?.toString()?.trim().orEmpty()
            if (cmd.isEmpty()) return
            binding.adbInput.setText("")
            appendAdbLine("$ adb/sh> $cmd")

            if (!ShizukuShell.isAvailable()) {
                appendAdbLine("Shizuku unavailable. Start Shizuku and grant permission.")
                ShizukuShell.requestPermission()
                return
            }
            if (!ShizukuShell.hasPermission()) {
                appendAdbLine("Requesting Shizuku permission...")
                ShizukuShell.requestPermission()
                return
            }
            ShizukuShell.exec(cmd) { code, output ->
                val b = _binding ?: return@exec
                b.root.post {
                    if (output.isNotBlank()) appendAdbLine(output.trimEnd())
                    appendAdbLine("[exit: $code]")
                }
            }
        }

        binding.adbSend.setOnClickListener { runCommand() }
        binding.adbClear.setOnClickListener {
            binding.adbOutput.text = ""
            appendAdbLine("# cleared")
        }
        binding.adbInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runCommand(); true
            } else false
        }
    }

    private fun setupControls() {
        binding.btnHideExtraKeys.setOnClickListener { setExtraKeysVisible(false) }
        binding.btnToAdb.setOnClickListener { applyMode(Mode.ADB) }

        val onFloatingClick = View.OnClickListener {
            when (currentMode) {
                Mode.LINUX -> setExtraKeysVisible(true)
                Mode.ADB -> applyMode(Mode.LINUX)
            }
        }
        binding.floatingControls.setOnClickListener(onFloatingClick)
        binding.btnFloatingToggle.setOnClickListener(onFloatingClick)
    }

    private fun setExtraKeysVisible(visible: Boolean) {
        val b = _binding ?: return
        b.extraKeysBar.visibility = if (visible) View.VISIBLE else View.GONE
        updateFloatingVisibility()
        if (visible) b.terminalView.requestFocus()
    }

    private fun updateFloatingVisibility() {
        val b = _binding ?: return
        val showFloating = when (currentMode) {
            Mode.LINUX -> !b.extraKeysBar.isVisible
            Mode.ADB -> true
        }
        b.floatingControls.visibility = if (showFloating) View.VISIBLE else View.GONE
        if (showFloating) {
            val params = b.floatingControls.layoutParams as ViewGroup.MarginLayoutParams
            val bottomDp = if (currentMode == Mode.ADB) 180 else 96
            params.bottomMargin = dp(bottomDp)
            b.floatingControls.layoutParams = params

            b.btnFloatingToggle.setImageResource(
                when {
                    currentMode == Mode.LINUX -> R.drawable.keyboard_double_arrow_up_24dp_1f1f1f_fill0_wght400_grad0_opsz24
                    else -> R.drawable.terminal_24dp_1f1f1f_fill0_wght400_grad0_opsz24
                }
            )
            b.floatingHint.visibility = View.GONE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyMode(mode: Mode, initial: Boolean = false) {
        val b = _binding ?: return
        currentMode = mode
        when (mode) {
            Mode.LINUX -> {
                b.linuxContainer.visibility = View.VISIBLE
                b.adbContainer.visibility = View.GONE
                if (initial) b.extraKeysBar.visibility = View.VISIBLE
                updateFloatingVisibility()
                b.terminalView.post { b.terminalView.requestFocus() }
            }
            Mode.ADB -> {
                b.linuxContainer.visibility = View.GONE
                b.adbContainer.visibility = View.VISIBLE
                b.floatingControls.visibility = View.VISIBLE
                updateFloatingVisibility()
                if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission()) {
                    appendAdbLine("# ${getString(R.string.tc4_terminal_shizuku_unavailable)}")
                }
                b.adbInput.post { b.adbInput.requestFocus() }
            }
        }
    }

    private fun appendAdbLine(text: String) {
        val b = _binding ?: return
        if (b.adbOutput.text.isNullOrEmpty()) b.adbOutput.text = text else b.adbOutput.append("\n$text")
        b.adbOutputScroll.post { b.adbOutputScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    fun getBlurView(): eightbitlab.com.blurview.BlurView? = _binding?.blurView

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
