package au.edu.unimelb.bioscout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import au.edu.unimelb.bioscout.ui.BioScoutApp
import au.edu.unimelb.bioscout.ui.BioScoutViewModel
import au.edu.unimelb.bioscout.ui.theme.BioScoutTheme

class MainActivity : ComponentActivity() {
    private val viewModel: BioScoutViewModel by viewModels {
        BioScoutViewModel.Factory((application as BioScoutApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BioScoutTheme {
                BioScoutApp(viewModel = viewModel)
            }
        }
    }
}
