package io.github.revtale

import android.app.Application

/** Process start (app, bike service, widget, jobs): keep crashes for "Report a problem" and keep update checks scheduled. */
class RevTaleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BugReport.install(this)
        runCatching { UpdateJob.schedule(this) }
    }
}
