package app.sweeper

import android.app.Application
import app.sweeper.service.Notifier
import app.sweeper.service.WatchdogWorker

class SweeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannels(this)
        WatchdogWorker.schedule(this)
    }
}
