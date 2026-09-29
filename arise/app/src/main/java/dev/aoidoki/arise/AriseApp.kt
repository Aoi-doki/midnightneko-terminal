package dev.aoidoki.arise

import android.app.Application
import androidx.work.Configuration

class AriseApp : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}

val android.content.Context.graph: AppGraph get() = (applicationContext as AriseApp).graph
