package au.edu.unimelb.floraguide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import au.edu.unimelb.floraguide.ui.FloraGuideApp
import au.edu.unimelb.floraguide.ui.FloraGuideViewModel
import au.edu.unimelb.floraguide.ui.theme.FloraGuideTheme

class MainActivity : ComponentActivity() {
    private val viewModel: FloraGuideViewModel by viewModels {
        FloraGuideViewModel.Factory((application as FloraGuideApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloraGuideTheme {
                FloraGuideApp(viewModel = viewModel)
            }
        }
    }
}
