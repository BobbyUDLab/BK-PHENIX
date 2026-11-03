package ru.bkphenix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.bkphenix.app.ui.coordinate.CoordinateScreen
import ru.bkphenix.app.ui.theme.BkPhenixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BkPhenixTheme {
                CoordinateScreen()
            }
        }
    }
}
