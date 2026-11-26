package com.fct.tc4.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4ActivityMainBinding
import com.fct.tc4.service.KeepAliveService
import com.fct.tc4.ui.misc.Global
import com.fct.tc4.ui.misc.WipeLayout
import com.fct.tc4.ui.page.ContainerInstallFragment
import com.fct.tc4.ui.page.ContainerMainFragment
import com.fct.tc4.ui.page.ContainerManageFragment
import com.fct.tc4.ui.page.ContainerManageViewModel
import com.fct.tc4.ui.page.TerminalFragment
import com.google.android.material.snackbar.Snackbar
import com.termux.x11.CmdEntryPointService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: Tc4ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = Tc4ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Start keep-alive (wake lock + foreground service) like Termux while app is active
        KeepAliveService.start(this)

        // Handle Window Insets (Edge-to-Edge & Keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            if (viewModel.isTerminalOpen.value) {
                // Keep terminal fullscreen but reserve space for IME when visible
                v.setPadding(0, 0, 0, 0)
            } else {
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            }

            // Lift terminal overlay above keyboard (avoid being covered)
            val bottomInset = if (imeVisible) imeInsets.bottom else 0
            binding.wiper.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = 0
                bottomMargin = bottomInset
            }
            ViewCompat.dispatchApplyWindowInsets(binding.wiper, insets)
            insets
        }

        // Lifecycle Observation
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Screen State
                launch {
                    viewModel.screen.collect { screen ->
                        showFragmentForScreen(screen)
                        invalidateOptionsMenu()
                    }
                }
                // Observe Terminal State
                launch {
                    viewModel.isTerminalOpen.collect { open ->
                        if (open) {
                            showTerminal()
                        } else {
                            hideTerminal()
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            resolveIntentAction(intent)
        }

        // Intercept Back Press
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.isTerminalOpen.value) {
                viewModel.closeTerminal()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    private fun showTerminal() {
        enterTerminalFullscreen()

        val existing = supportFragmentManager.findFragmentById(R.id.terminal_overlay)
        if (existing != null && existing.view != null) {
            binding.root.post {
                (existing as? TerminalFragment)?.getBlurView()?.setupWith(binding.blurTarget)
                    ?.setBlurRadius(2f)
            }
            return
        }
        val fragment = TerminalFragment()
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            addToBackStack("terminal")
            replace(R.id.terminal_overlay, fragment, "Terminal")
        }
        binding.root.post {
            val addedFragment = supportFragmentManager.findFragmentByTag("Terminal") as? TerminalFragment
            addedFragment?.let {
                it.getBlurView()?.setupWith(binding.blurTarget)?.setBlurRadius(2f)
                binding.wiper.startWipeAnimation(
                    isIn = true,
                    direction = WipeLayout.Direction.TOP_TO_BOTTOM,
                    durationMs = 300
                )
            }
        }
    }

    private fun hideTerminal() {
        val fragment = supportFragmentManager.findFragmentByTag("Terminal")
        if (fragment != null) {
            binding.wiper.startWipeAnimation(
                isIn = false,
                direction = WipeLayout.Direction.BOTTOM_TO_TOP,
                durationMs = 300
            ) {
                supportFragmentManager.popBackStack()
                exitTerminalFullscreen()
            }
        } else {
            exitTerminalFullscreen()
        }
    }

    private fun enterTerminalFullscreen() {
        supportActionBar?.hide()
        binding.toolbar.visibility = android.view.View.GONE
        binding.wiper.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToBottom = ConstraintLayout.LayoutParams.UNSET
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        }
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(binding.main)
    }

    private fun exitTerminalFullscreen() {
        binding.toolbar.visibility = android.view.View.VISIBLE
        supportActionBar?.show()
        binding.wiper.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = ConstraintLayout.LayoutParams.UNSET
            topToBottom = R.id.toolbar
        }
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(binding.main)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.terminalToggle -> {
                viewModel.toggleTerminal()
                true
            }
            R.id.containerImport -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerManage") as? ContainerManageFragment
                fragment?.onImportAction()
                true
            }
            R.id.enterGui -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerMain") as? ContainerMainFragment
                fragment?.onEnterGui()
                true
            }
            R.id.file -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerInstall") as? ContainerInstallFragment
                fragment?.onFileAccessAction()
                true
            }
            R.id.battery -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerInstall") as? ContainerInstallFragment
                fragment?.onBatteryAction()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resolveIntentAction(intent)
    }

    private fun showFragmentForScreen(screen: MainViewModel.Screen) {
        val fragment: Fragment
        val tag: String
        when (screen) {
            is MainViewModel.Screen.Init -> return
            is MainViewModel.Screen.ContainerManage -> {
                fragment = ContainerManageFragment()
                tag = "ContainerManage"
            }
            is MainViewModel.Screen.ContainerInstall -> {
                fragment = ContainerInstallFragment().apply {
                    arguments = Bundle().apply {
                        putString("code", screen.code)
                        putString("webpage", screen.webpage)
                    }
                }
                tag = "ContainerInstall"
            }
            is MainViewModel.Screen.ContainerMain -> {
                fragment = ContainerMainFragment().apply {
                    arguments = Bundle().apply {
                        putString("code", screen.code)
                    }
                }
                tag = "ContainerMain"
            }
        }
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment, tag)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        return when (viewModel.screen.value) {
            is MainViewModel.Screen.ContainerManage -> {
                menuInflater.inflate(R.menu.tc4_fragment_container_manage, menu)
                true
            }
            is MainViewModel.Screen.ContainerMain -> {
                menuInflater.inflate(R.menu.tc4_fragment_container_main, menu)
                true
            }
            is MainViewModel.Screen.ContainerInstall -> {
                menuInflater.inflate(R.menu.tc4_fragment_container_install, menu)
                true
            }
            else -> false
        }
    }

    // ─── Intent Parsing ──────────────────────────────────────────

    private fun resolveIntentAction(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_MAIN -> onNormalLaunch()
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> onImportFile(intent)
            ACTION_SHORTCUT -> onShortcutLaunch(intent)
            else -> onNormalLaunch()
        }
    }

    private fun onNormalLaunch() {
        if (viewModel.screen.value !is MainViewModel.Screen.Init) return

        if (!Global.isFirstLaunchDone && Global.hasBuiltInRootfs() && Global.installedContainers.isEmpty()) {
            val containerViewModel: ContainerManageViewModel by viewModels()
            containerViewModel.autoInstallBuiltInContainer()
            viewModel.navigateTo(MainViewModel.Screen.ContainerManage)
            return
        }

        val autoCode = Global.autoLaunch
        if (autoCode.isNotEmpty() && autoCode in Global.installedContainers) {
            viewModel.navigateTo(MainViewModel.Screen.ContainerMain(autoCode))
        } else {
            if (autoCode.isNotEmpty()) {
                Global.autoLaunch = ""
            }
            viewModel.navigateTo(MainViewModel.Screen.ContainerManage)
        }
    }

    private fun onImportFile(intent: Intent) {
        val uris = mutableListOf<Uri>()
        
        // Modern Intent Parsing (Android 13+ Support)
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                uri?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uriList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                uriList?.let { uris.addAll(it) }
            }
        }

        if (uris.isEmpty()) {
            if (viewModel.screen.value is MainViewModel.Screen.Init) {
                onNormalLaunch()
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val app = application
            val publicDir = File(app.filesDir, "public")
            publicDir.mkdirs()

            var copiedCount = 0
            uris.forEach { uri ->
                try {
                    val fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        File(publicDir, fileName).outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }
                    copiedCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                Snackbar.make(binding.root,
                    getString(R.string.tc4_import_file_result, copiedCount, uris.size),
                    Snackbar.LENGTH_SHORT).show()

                if (viewModel.screen.value is MainViewModel.Screen.Init) {
                    onNormalLaunch()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment
        return name?.substringAfterLast('/')?.substringAfterLast('\\')
    }

    private fun onShortcutLaunch(intent: Intent) {
        val code = intent.getStringExtra("shortcut_code") ?: return
        val command = intent.getStringExtra("shortcut_command") ?: return

        if (code !in Global.installedContainers) {
            Snackbar.make(binding.root, getString(R.string.tc4_shortcut_container_not_found, code), Snackbar.LENGTH_LONG).show()
            if (viewModel.screen.value is MainViewModel.Screen.Init) {
                onNormalLaunch()
            }
            return
        }

        val screen = viewModel.screen.value

        when (screen) {
            is MainViewModel.Screen.Init,
            is MainViewModel.Screen.ContainerManage -> {
                MainViewModel.pendingCommandCode = code
                MainViewModel.pendingCommandText = command
                viewModel.navigateTo(MainViewModel.Screen.ContainerMain(code))
            }
            is MainViewModel.Screen.ContainerMain -> {
                if (screen.code == code) {
                    Global.sendCommand(command)
                    lifecycleScope.launch {
                        delay(32)
                        (supportFragmentManager.findFragmentByTag("ContainerMain") as? ContainerMainFragment)?.onEnterGui()
                    }
                } else {
                    Snackbar.make(binding.root, R.string.tc4_shortcut_another_running, Snackbar.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            KeepAliveService.stop(this)
            startService(Intent(
                this, CmdEntryPointService::class.java
            ).apply { action = CmdEntryPointService.ACTION_STOP })
        }
    }

    companion object {
        const val ACTION_SHORTCUT = "com.fct.tc4.action.SHORTCUT"
    }
}

