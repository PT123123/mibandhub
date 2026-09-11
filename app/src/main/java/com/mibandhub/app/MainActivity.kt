package com.mibandhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mibandhub.app.ui.AppRoot
import com.mibandhub.app.ui.theme.ShouhuanTheme

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
