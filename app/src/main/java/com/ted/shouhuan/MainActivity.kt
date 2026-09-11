package com.ted.shouhuan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ted.shouhuan.ui.AppRoot
import com.ted.shouhuan.ui.theme.ShouhuanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShouhuanTheme {
                AppRoot()
            }
        }
    }
}
