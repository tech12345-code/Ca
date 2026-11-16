package com.smartexpense.manager.domain.upi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.smartexpense.manager.domain.model.CompatibleUpiApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers compatible installed UPI applications dynamically using Android's
 * official <queries> intent visibility (`upi://pay`) and package lookups.
 * Never claims persistent ledger connections and never requests UPI PINs or OTPs.
 */
@Singleton
class UpiAppDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferredOrder = listOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM UPI",
        "in.amazon.mShop.android.shopping" to "Amazon Pay"
    )

    fun detectCompatibleUpiApps(): List<CompatibleUpiApp> {
        val pm = context.packageManager
        val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                probeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        val discovered = linkedMapOf<String, CompatibleUpiApp>()
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            val knownLabel = preferredOrder.firstOrNull { it.first == pkg }?.second
            val label = knownLabel ?: info.loadLabel(pm).toString().ifBlank { pkg }
            discovered[pkg] = CompatibleUpiApp(appName = label, packageName = pkg)
        }

        // Also include known installed packages from <queries> if MATCH_DEFAULT_ONLY missed them
        for ((pkg, name) in preferredOrder) {
            if (!discovered.containsKey(pkg) && isPackageInstalled(pkg)) {
                discovered[pkg] = CompatibleUpiApp(appName = name, packageName = pkg)
            }
        }

        val rankMap = preferredOrder.mapIndexed { idx, pair -> pair.first to idx }.toMap()
        return discovered.values.sortedWith(
            compareBy<CompatibleUpiApp> { rankMap[it.packageName] ?: Int.MAX_VALUE }
                .thenBy { it.appName }
        )
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun buildTargetedUpiPaymentIntent(upiUri: String, targetPackageName: String? = null): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(upiUri)).apply {
            if (!targetPackageName.isNullOrBlank()) {
                setPackage(targetPackageName)
            }
        }
    }
}
