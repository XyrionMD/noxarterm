// SettingsViewModel.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.page

import android.app.Application
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fct.tc4.R
import com.fct.tc4.ui.misc.ConfigManager
import com.fct.tc4.ui.misc.Global
import com.termux.x11.CmdEntryPointService
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ===================== Data Models =====================

sealed class SettingsItem {
    abstract val id: String

    @get:StringRes
    abstract val titleRes: Int

    @get:StringRes
    abstract val descriptionRes: Int
}

/** 开关型设置项 */
data class SwitchSetting(
    override val id: String,
    @StringRes override val titleRes: Int,
    @StringRes override val descriptionRes: Int,
    val isChecked: Boolean
) : SettingsItem()

/** 动作型设置项（点击执行跳转/请求，无开关） */
data class ActionSetting(
    override val id: String,
    @StringRes override val titleRes: Int,
    @StringRes override val descriptionRes: Int
) : SettingsItem()

/** 服务器状态控制项（显示运行状态，提供启动/停止按钮） */
data class ServerControlSetting(
    override val id: String,
    @StringRes override val titleRes: Int,
    val isRunning: Boolean
) : SettingsItem() {
    override val descriptionRes: Int = 0
}

// ===================== ViewModel =====================

class SettingsViewModel(
    application: Application,
    private val code: String
) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<SettingsItem>>(emptyList())
    val items: StateFlow<List<SettingsItem>> = _items.asStateFlow()

    init { refresh() }

    /** 切换开关状态并持久化 */
    fun toggle(item: SwitchSetting) {
        val newChecked = !item.isChecked
        when (item.id) {
            "auto_launch" -> Global.autoLaunch = if (newChecked) code else ""
            "auto_launch_gui" -> Global.autoLaunchGui = newChecked
            "reset_bootstrap" -> Global.shouldResetBootstrap = newChecked
            "use_legacy_proot" -> Global.useLegacyProot = newChecked
            "auto_check_update" -> Global.autoCheckUpdate = newChecked
        }
        refresh()
    }

    fun isX11Running(): Boolean {
        val dir = File(getApplication<Application>().filesDir, "tmp/.X11-unix")
        val sockets = dir.listFiles()?.filter { it.name.startsWith("X") } ?: return false
        return sockets.any { sockFile ->
            try {
                LocalSocket().use { s ->
                    s.connect(LocalSocketAddress(sockFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    fun startX11Server() {
        val app = getApplication<Application>()
        val config = ConfigManager.load(code) ?: emptyMap()
        val features = config["feature"] as? List<Map<String, Any>> ?: emptyList()
        val x11Feature = features.firstOrNull { it["name"] == "x11" }
        val args = (x11Feature?.get("args") as? List<*>)?.filterIsInstance<String>() ?: listOf(":0")
        val envKeys = arrayOf(
            "TMPDIR", "XKB_CONFIG_ROOT",
            "TERMUX_X11_DEBUG", "TERMUX_X11_OVERRIDE_PACKAGE"
        )
        val envVals = arrayOf(
            "${app.filesDir.absolutePath}/tmp",
            "${app.dataDir.absolutePath}/$code/usr/share/X11/xkb",
            "1",
            app.packageName
        )
        app.startService(Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_START
            putExtra(CmdEntryPointService.EXTRA_ARGS, args.toTypedArray())
            putExtra(CmdEntryPointService.EXTRA_ENV_KEYS, envKeys)
            putExtra(CmdEntryPointService.EXTRA_ENV_VALUES, envVals)
        })
        refresh()
    }

    fun stopX11Server() {
        val app = getApplication<Application>()
        app.startService(Intent(app, CmdEntryPointService::class.java).apply {
            action = CmdEntryPointService.ACTION_STOP
        })
        refresh()
    }

    fun refresh() {
        _items.value = listOf(
            ServerControlSetting(
                id = "x11_server",
                titleRes = R.string.tc4_settings_x11_server_title,
                isRunning = isX11Running()
            ),
            ActionSetting(
                id = "launcher_shortcut",
                titleRes = R.string.tc4_settings_shortcut_title,
                descriptionRes = R.string.tc4_settings_shortcut_desc
            ),
            SwitchSetting(
                id = "auto_launch",
                titleRes = R.string.tc4_settings_auto_launch_title,
                descriptionRes = R.string.tc4_settings_auto_launch_desc,
                isChecked = code == Global.autoLaunch
            ),
            SwitchSetting(
                id = "auto_launch_gui",
                titleRes = R.string.tc4_settings_auto_launch_gui_title,
                descriptionRes = R.string.tc4_settings_auto_launch_desc,
                isChecked = Global.autoLaunchGui
            ),
            SwitchSetting(
                id = "use_legacy_proot",
                titleRes = R.string.tc4_settings_legacy_proot_title,
                descriptionRes = R.string.tc4_settings_legacy_proot_desc,
                isChecked = Global.useLegacyProot
            ),
            SwitchSetting(
                id = "reset_bootstrap",
                titleRes = R.string.tc4_settings_reset_bootstrap_title,
                descriptionRes = R.string.tc4_settings_reset_bootstrap_desc,
                isChecked = Global.shouldResetBootstrap
            ),
            SwitchSetting(
                id = "auto_check_update",
                titleRes = R.string.tc4_settings_auto_check_update_title,
                descriptionRes = R.string.tc4_settings_auto_check_update_desc,
                isChecked = Global.autoCheckUpdate
            ),
            ActionSetting(
                id = "manage_files",
                titleRes = R.string.tc4_settings_manage_files_title,
                descriptionRes = R.string.tc4_settings_manage_files_desc
            ),
            ActionSetting(
                id = "battery_unlimited",
                titleRes = R.string.tc4_settings_battery_title,
                descriptionRes = R.string.tc4_settings_battery_desc
            ),
            ActionSetting(
                id = "restart_audio",
                titleRes = R.string.tc4_settings_restart_audio_title,
                descriptionRes = R.string.tc4_settings_restart_audio_desc
            )
        )
    }

    class Factory(
        private val application: Application,
        private val code: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(application, code) as T
        }
    }
}
