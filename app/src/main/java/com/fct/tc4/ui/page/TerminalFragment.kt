package com.fct.tc4.ui.page

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
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
import com.offsec.nhterm.backend.TextStyle
import com.offsec.nhterm.frontend.session.terminal.BasicViewClient
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.launch
import kotlin.math.abs

class TerminalFragment : Fragment() {

    private var _binding: Tc4FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private enum class Mode { LINUX, ADB }
    private var currentMode: Mode = Mode.LINUX

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
        val session = Global.terminalSession
        if (session != null) {
            binding.terminalView.setTextSize(Global.terminalFontSize)
            binding.terminalView.attachSession(session)
        }

        val tv = TypedValue()
        val theme = requireContext().theme
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
        val fgColor = tv.data
        binding.terminalView.setBackgroundColor(0xFF000000.toInt())

        try {
            val tf = android.graphics.Typeface.createFromAsset(requireContext().assets, "fonts/font.ttf")
            binding.terminalView.setTypeface(tf)
        } catch (e: Exception) { e.printStackTrace() }

        Global.terminalSession?.emulator?.mColors?.mCurrentColors?.let { colors ->
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = fgColor
        }
        binding.terminalView.onScreenUpdated()

        val ta = theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val ekColor = ta.getColor(0, Color.WHITE)
        ta.recycle()
        binding.extraKeys.setTextColor(ekColor)
        simplifyExtraKeys()

        binding.terminalView.setTerminalViewClient(object : BasicViewClient(binding.terminalView) {
            override fun onScale(scale: Float): Float {
                val result = super.onScale(scale)
                Global.terminalFontSize = binding.terminalView.textSize
                return result
            }
            override fun readControlKey(): Boolean = binding.extraKeys.readControlButton()
            override fun readAltKey(): Boolean = binding.extraKeys.readAltButton()
            override fun readShiftKey(): Boolean = binding.extraKeys.readShiftButton()
            override fun readFnKey(): Boolean = binding.extraKeys.readFnButton()
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
            else { v.requestFocus(); imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun simplifyExtraKeys() {
        try {
            val view = binding.extraKeys
            val cls = view.javaClass
            val builtin = cls.getDeclaredField("builtinKeys").apply { isAccessible = true }.get(view) as MutableList<Any?>
            val ctrl = cls.getDeclaredField("CTRL").apply { isAccessible = true }.get(view)
            val alt = cls.getDeclaredField("ALT").apply { isAccessible = true }.get(view)

            fun staticBtn(name: String): Any? = runCatching {
                cls.getDeclaredField(name).apply { isAccessible = true }.get(null)
            }.getOrNull()

            // 2 rows layout: row1 = Esc,Tab,Ctrl,Alt ; row2 = arrows,Home,End
            runCatching {
                cls.getDeclaredField("MAX_BUTTONS_PER_LINE").apply { isAccessible = true }.setInt(null, 6)
            }

            val textBtnCls = runCatching { Class.forName("com.offsec.nhterm.frontend.session.view.extrakey.TextButton") }.getOrNull()
            val spacer1 = runCatching {
                textBtnCls?.getConstructor(String::class.java, Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE)
                    ?.newInstance(" ", false)
            }.getOrNull()
            val spacer2 = runCatching {
                textBtnCls?.getConstructor(String::class.java, Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE)
                    ?.newInstance(" ", false)
            }.getOrNull()

            builtin.clear()
            listOfNotNull(
                staticBtn("ESC"),
                staticBtn("TAB"),
                ctrl,
                alt,
                staticBtn("END"),
                spacer1,
                staticBtn("ARROW_LEFT"),
                staticBtn("ARROW_UP"),
                staticBtn("ARROW_DOWN"),
                staticBtn("ARROW_RIGHT"),
                staticBtn("HOME"),
                spacer2
            ).forEach { builtin.add(it) }

            cls.getMethod("updateButtons").invoke(view)

            // Hide spacer placeholders so row 1 keeps "Esc Tab Ctrl Alt" layout
            for (i in 0 until view.childCount) {
                val bar = view.getChildAt(i) as? android.widget.LinearLayout ?: continue
                for (j in 0 until bar.childCount) {
                    val btn = bar.getChildAt(j) as? android.widget.Button ?: continue
                    val t = btn.text?.toString().orEmpty()
                    if (t.isBlank()) {
                        btn.visibility = View.INVISIBLE
                        btn.isEnabled = false
                        btn.isClickable = false
                    }
                }
            }
        } catch (_: Throwable) {
            // Fallback: keep default keys if reflection fails
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
        // In extra keys bar (Linux mode, keys visible)
        binding.btnHideExtraKeys.setOnClickListener { setExtraKeysVisible(false) }
        binding.btnToAdb.setOnClickListener { applyMode(Mode.ADB) }

        // Floating controls (when keys hidden or in ADB mode)
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) > 80 && abs(dx) > abs(velocityY)) {
                    if (dx < 0) applyMode(Mode.ADB) else applyMode(Mode.LINUX)
                    return true
                }
                return false
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (currentMode == Mode.LINUX) setExtraKeysVisible(true)
                return true
            }
        })
        val touchListener = View.OnTouchListener { v, ev ->
            val handled = gestureDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            handled
        }
        binding.floatingControls.setOnTouchListener(touchListener)
        binding.btnFloatingToggle.setOnTouchListener(touchListener)
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
            b.floatingHint.text = when (currentMode) {
                Mode.LINUX -> getString(R.string.tc4_terminal_swipe_hint)
                Mode.ADB -> getString(R.string.tc4_terminal_swipe_hint_adb)
            }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun getBlurView(): BlurView? = _binding?.blurView
}
