package com.terryxtl.tododrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.terryxtl.tododrop.ui.navigation.TodoDropNavHost
import com.terryxtl.tododrop.ui.theme.TodoDropTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TodoDropApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                TodoDropTheme {
                    TodoDropNavHost()
                }
            }
        }
    }
}
