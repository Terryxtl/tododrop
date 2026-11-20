package com.terryxtl.tododrop

import android.app.Application

class TodoDropApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
