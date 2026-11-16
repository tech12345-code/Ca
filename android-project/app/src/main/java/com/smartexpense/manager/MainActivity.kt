package com.smartexpense.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartexpense.manager.domain.security.AppLockManager
import com.smartexpense.manager.domain.security.PinSecurityEngine
import com.smartexpense.manager.ui.navigation.AppNavHost
import com.smartexpense.manager.ui.theme.SmartExpenseTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLockManager: AppLockManager

    private var sharedReceiptUri by mutableStateOf<Uri?>(null)
    private var deepLinkRoute by mutableStateOf<String?>(null)

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (!isChangingConfigurations) {
                appLockManager.onAppBackgrounded()
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            appLockManager.onAppForegrounded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPostNotificationsIfNeeded()
        handleIncomingIntent(intent)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        setContent {
            val securitySettings by appLockManager.settings.collectAsStateWithLifecycle()

            LaunchedEffect(securitySettings.appLockEnabled, securitySettings.method) {
                val enableFlagSecure = PinSecurityEngine.shouldEnableFlagSecure(
                    appLockEnabled = securitySettings.appLockEnabled,
                    method = securitySettings.method
                )
                if (enableFlagSecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            SmartExpenseTheme {
                AppNavHost(
                    sharedReceiptUri = sharedReceiptUri,
                    deepLinkRoute = deepLinkRoute,
                    onConsumeSharedUri = { sharedReceiptUri = null },
                    onConsumeDeepLink = { deepLinkRoute = null }
                )
            }
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    sharedReceiptUri = uri
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data?.scheme == "smartexpense") {
                    deepLinkRoute = data.host ?: "dashboard"
                }
            }
        }
        intent.getStringExtra("deep_link_route")?.let { route ->
            deepLinkRoute = route
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
