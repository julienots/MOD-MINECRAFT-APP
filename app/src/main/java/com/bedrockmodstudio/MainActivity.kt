package com.bedrockmodstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bedrockmodstudio.ui.AppNavigation
import com.bedrockmodstudio.ui.theme.BmsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BmsTheme { AppNavigation() }
        }
    }
}
