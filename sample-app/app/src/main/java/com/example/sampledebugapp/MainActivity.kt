package com.example.sampledebugapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.sampledebugapp.theme.SampleDebugAppTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      SampleDebugAppTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }
}
