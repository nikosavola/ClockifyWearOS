package fi.nikosavola.clockifywear

import android.app.Application
import fi.nikosavola.clockifywear.di.AppContainer

class ClockifyApp : Application() {
  // by lazy instead of lateinit-in-onCreate: Application's Context is valid before onCreate()
  // runs (attachBaseContext already completed), and MainActivity is always created after
  // onCreate(), so deferring construction to first access needs no manual init-order bookkeeping.
  val appContainer: AppContainer by lazy { AppContainer(this) }
}
