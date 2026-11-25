package com.ilyemir.antena

import android.app.Application
import com.ilyemir.antena.server.AppContainer

class AntenaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this).also { it.start() }
    }
}
