package com.smartexpense.manager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.smartexpense.manager.notifications.NotificationHelper
import com.smartexpense.manager.workers.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SmartExpenseApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var notificationHelper: NotificationHelper

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId("river-host-cv0ns")
                .setApplicationId("1:68128444479:android:8a2eb17437224159")
                .setApiKey("AIzaSyAe6gFQQzOlSXbLRIV5aQGjGWBWjszOA6s")
                .setGcmSenderId("68128444479")
                .setStorageBucket("river-host-cv0ns.firebasestorage.app")
                .build()
            FirebaseApp.initializeApp(this, options)
        }
        notificationHelper.createAllChannels()
        WorkerScheduler.schedulePeriodicTasks(this)
    }
}
