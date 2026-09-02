package com.example.tiledplayer

import android.app.Application
import kotlin.concurrent.thread

class TiledPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // An import that was killed mid-copy (process death, an OEM's
        // aggressive background-process freezing) leaves a `.part` file that
        // nothing references. Clear it off the main thread on the way in.
        thread(isDaemon = true) { VaultStore.sweepPartials(this) }
    }
}
