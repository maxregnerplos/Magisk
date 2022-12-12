package com.topjohnwu.magisk.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.forEach
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDirections
import com.topjohnwu.magisk.MainDirections
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.arch.viewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.model.module.LocalModule
import com.topjohnwu.magisk.core.tasks.HideAPK
import com.topjohnwu.magisk.databinding.ActivityMainMd2Binding
import com.topjohnwu.magisk.ktx.startAnimations
import com.topjohnwu.magisk.ui.home.HomeFragmentDirections
import com.topjohnwu.magisk.utils.Utils
import com.topjohnwu.magisk.view.MagiskDialog
import com.topjohnwu.magisk.view.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel : BaseViewModel()

class MainActivity : SplashActivity<ActivityMainMd2Binding>() {

    override val layoutRes = R.layout.activity_main_md2
    override val viewModel by viewModel<MainViewModel>()
    override val navHostId: Int = R.id.main_nav_host
    override val snackbarView: View
        get() {
            val fragmentOverride = currentFragment?.snackbarView
            return fragmentOverride ?: super.snackbarView
        }
    override val snackbarAnchorView: View?
        get() {
            val fragmentAnchor = currentFragment?.snackbarAnchorView
            return when {
                fragmentAnchor?.isVisible == true -> fragmentAnchor
                binding.mainNavigation.isVisible -> return binding.mainNavigation
                else -> null
            }
        }

            private fun setContentView() {
        binding = ActivityMainMd2Binding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    private var isRootFragment = true

    @SuppressLint("InlinedApi")
    override fun showMainUI(savedInstanceState: Bundle?) {
        setContentView()
        showUnsupportedMessage()
        askForHomeShortcut()
        checkStubComponent()

        // Ask permission to post notifications for background update check
        if (Config.checkUpdate) {
            withPermission(Manifest.permission.POST_NOTIFICATIONS) {
                Config.checkUpdate = it
            }
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        navigation.addOnDestinationChangedListener { _, destination, _ ->
            isRootFragment = when (destination.id) {
                R.id.homeFragment,
                R.id.modulesFragment,
                R.id.superuserFragment,
                R.id.logFragment -> true
                else -> false
            }

            setDisplayHomeAsUpEnabled(!isRootFragment)
            requestNavigationHidden(!isRootFragment)

            binding.mainNavigation.menu.forEach {
                if (it.itemId == destination.id) {
                    it.isChecked = true
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filesDir, HideAPK.HIDE_MARKER)
            if (file.exists()) {
                file.delete()
                packageManager.setApplicationEnabledSetting(
                    packageName,
                    ApplicationInfo.ENABLED_STATE_ENABLED,
                    0
                )
            }
        }

        binding.mainToolbar.title = getString(R.string.app_name)

        setSupportActionBar(binding.mainToolbar)

        binding.mainNavigation.setOnItemSelectedListener {
            getScreen(it.itemId)?.navigate()
            true
        }
        binding.mainNavigation.setOnItemReselectedListener {
            // https://issuetracker.google.com/issues/124538620
        }
        binding.mainNavigation.menu.apply {
            findItem(R.id.superuserFragment)?.isEnabled = Utils.showSuperUser()
            findItem(R.id.modulesFragment)?.isEnabled = Info.env.isActive && LocalModule.loaded()
        }

        val section =
            if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES)
                Const.Nav.SETTINGS
            else
                intent.getStringExtra(Const.Key.OPEN_SECTION)

        getScreen(section)?.navigate()

        if (!isRootFragment) {
            requestNavigationHidden(requiresAnimation = savedInstanceState == null)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onBackPressed() {
        if (isRootFragment) {
            super.onBackPressed()
        } else {
            navigation.popBackStack()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            getScreen(Const.Nav.SETTINGS)?.navigate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateToolbar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            invalidateToolbar()
        }
    }

    private fun askForHomeShortcut() {
        if (Config.showHomeShortcut) {
            MagiskDialog(this)
                .setTitle(R.string.home_shortcut_title)
                .setMessage(R.string.home_shortcut_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { _, _ ->
                    Shortcuts(this).createHomeShortcut()
                }
                .setNegativeButton(R.string.no_thanks, null)
                .show()
        }
    }

    private fun checkStubComponent() {
        val activityInfo = packageManager.getActivityInfo(componentName, 0)
        if (activityInfo.exported) {
            MagiskDialog(this)
                .setTitle(R.string.stub_component_title)
                .setMessage(R.string.stub_component_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                .setNegativeButton(R.string.no_thanks, null)
                .show()
        }
    }

    private fun showUnsupportedMessage() {
        if (!Info.env.isActive) {
            MagiskDialog(this)
                .setTitle(R.string.unsupported_title)
                .setMessage(R.string.unsupported_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { _, _ ->
                    finish()
                }
                .show()
        }
    }

    private

    fun setDisplayHomeAsUpEnabled(isEnabled: Boolean) {
        binding.mainToolbar.startAnimations()
        when {
            isEnabled -> binding.mainToolbar.setNavigationIcon(R.drawable.ic_back_md2)
            else -> binding.mainToolbar.navigationIcon = null
        }
    }

    internal fun requestNavigationHidden(hide: Boolean = true, requiresAnimation: Boolean = true) {
        val bottomView = binding.mainNavigation
        if (requiresAnimation) {
            bottomView.isVisible = true
            bottomView.isHidden = hide
        } else {
            bottomView.isGone = hide
        }
    }

    fun invalidateToolbar() {
        //binding.mainToolbar.startAnimations()
        binding.mainToolbar.invalidate()
    }

    private fun getScreen(name: String?): NavDirections? {
        return when (name) {
            Const.Nav.SUPERUSER -> MainDirections.actionSuperuserFragment()
            Const.Nav.MODULES -> MainDirections.actionModuleFragment()
            Const.Nav.SETTINGS -> HomeFragmentDirections.actionHomeFragmentToSettingsFragment()
            else -> null
        }
    }

    private fun getScreen(id: Int): NavDirections? {
        return when (id) {
            R.id.homeFragment -> MainDirections.actionHomeFragment()
            R.id.modulesFragment -> MainDirections.actionModuleFragment()
            R.id.superuserFragment -> MainDirections.actionSuperuserFragment()
            R.id.logFragment -> MainDirections.actionLogFragment()
            else -> null
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val screen = getScreen(item.itemId) ?: return false
        item.isChecked = true
        screen.navigate()
        return true
    }

    private fun NavDirections.navigate() {
        navigation.navigate(this)
    }

    private fun showUnsupportedMessage() {
        if (Info.env.isUnsupported) {
            MagiskDialog(this).apply {
                setTitle(R.string.unsupport_magisk_title)
                setMessage(R.string.unsupport_magisk_msg, Const.Version.MIN_VERSION)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        if (!Info.isEmulator && Info.env.isActive && System.getenv("PATH")
                ?.split(':')
                ?.filterNot { File("$it/magisk").exists() }
                ?.any { File("$it/su").exists() } == true) {
            MagiskDialog(this).apply {
                setTitle(R.string.unsupport_general_title)
                setMessage(R.string.unsupport_other_su_msg)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

    private fun showUnsupportedMessage() {
        if (Info.env.isUnsupported) {
            MagiskDialog(this).apply {
                setTitle(R.string.unsupport_magisk_title)
                setMessage(R.string.unsupport_magisk_msg, Const.Version.MIN_VERSION)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        if (!Info.isEmulator && Info.env.isActive && System.getenv("PATH")
                ?.split(':')
                ?.filterNot { File("$it/magisk").exists() }
                ?.any { File("$it/su").exists() } == true) {
            MagiskDialog(this).apply {
                setTitle(R.string.unsupport_general_title)
                setMessage(R.string.unsupport_other_su_msg)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        if (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
            MagiskDialog(this).apply {
                setTitle(R.string.unsupport_general_title)
                setMessage(R.string.unsupport_system_app_msg)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }

        if (applicationInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE != 0) {
            MagiskDialog(this).apply {
                setTitle(R.string.unsupport_general_title)
                setMessage(R.string.unsupport_external_storage_msg)
                setButton(MagiskDialog.ButtonType.POSITIVE) { text = android.R.string.ok }
                setCancelable(false)
            }.show()
        }
    }

    private fun askForHomeShortcut() {
        if (isRunningAsStub && !Config.askedHome &&
            ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            // Ask and show dialog
            Config.askedHome = true
            MagiskDialog(this).apply {
                setTitle(R.string.add_shortcut_title)
                setMessage(R.string.add_shortcut_msg)
                setButton(MagiskDialog.ButtonType.NEGATIVE) {
                    text = android.R.string.cancel
                }
                setButton(MagiskDialog.ButtonType.POSITIVE) {
                    text = android.R.string.ok
                    onClick {
                        Shortcuts.addHomeIcon(this@MainActivity)
                    }
                }
                setCancelable(true)
            }.show()
        }
    }

    private fun checkStubComponent() {
        if (intent.component?.className?.contains(HideAPK.PLACEHOLDER) == true) {
            // The stub APK was not properly patched, re-apply our changes
            withPermission(Manifest.permission.REQUEST_INSTALL_PACKAGES) { granted ->
                if (granted) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val apk = File(applicationInfo.sourceDir)
                        HideAPK.upgrade(this@MainActivity, apk)?.let {
                            startActivity(it)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

    @SuppressLint("InlinedApi")
    private fun checkStubComponent() {
        if (intent.component?.className?.contains(HideAPK.PLACEHOLDER) == true) {
            // The stub APK was not properly patched, re-apply our changes
            withPermission(Manifest.permission.REQUEST_INSTALL_PACKAGES) { granted ->
                if (granted) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val apk = File(applicationInfo.sourceDir)
                        HideAPK.upgrade(this@MainActivity, apk)?.let {
                            startActivity(it)
                        }
                    }
                }
            }
        }
    }

}
