package au.edu.unimelb.bioscout

import android.app.Application
import au.edu.unimelb.bioscout.di.AppContainer

class BioScoutApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }
}
