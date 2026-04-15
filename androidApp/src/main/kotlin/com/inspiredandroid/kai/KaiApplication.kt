package com.inspiredandroid.kai

import android.app.Application
import com.inspiredandroid.kai.sandbox.sandboxModule
import com.inspiredandroid.kai.setup.FirstRunSetupManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get

class KaiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KaiApplication)
            modules(appModule, sandboxModule)
        }
        // Trigger silent first-run setup (installs sandbox, Python AI stack, Ollama)
        get<FirstRunSetupManager>(FirstRunSetupManager::class.java).checkAndRun()
    }
}
