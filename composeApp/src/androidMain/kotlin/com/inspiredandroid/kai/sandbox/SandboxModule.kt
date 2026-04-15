package com.inspiredandroid.kai.sandbox

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.setup.FirstRunSetupManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val sandboxModule = module {
    single<LinuxSandboxManager> { LinuxSandboxManager(androidContext()) }
    single<OllamaServerManager> { OllamaServerManager(androidContext(), get()) }
    single<FirstRunSetupManager> {
        FirstRunSetupManager(
            context = androidContext(),
            appSettings = get<AppSettings>(),
            sandboxManager = get<LinuxSandboxManager>(),
            ollamaManager = get<OllamaServerManager>(),
        )
    }
}
