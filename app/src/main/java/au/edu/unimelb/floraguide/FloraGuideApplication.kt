package au.edu.unimelb.floraguide

import android.app.Application
import au.edu.unimelb.floraguide.di.AppContainer

class FloraGuideApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }
}
