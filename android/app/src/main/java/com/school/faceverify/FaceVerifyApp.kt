package com.school.faceverify

import android.app.Application
import com.school.faceverify.data.AppSettings

class FaceVerifyApp : Application() {
    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
    }

    companion object {
        lateinit var instance: FaceVerifyApp
            private set
    }
}
