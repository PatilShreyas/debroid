package com.example.sampledebugapp.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sampledebugapp.data.DefaultDataRepository

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
    val orderStatus by viewModel.orderStatus.collectAsStateWithLifecycle()
    var clickCount by remember { mutableIntStateOf(0) }
    var userNote by remember { mutableStateOf("Initial Compose Note") }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Agentic Debugger Demo",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = orderStatus,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        ComposeStateWidget(
            clickCount = clickCount,
            userNote = userNote,
            onIncrement = {
                clickCount++
                userNote = "Updated Note #$clickCount"
            }
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { viewModel.onProcessOrderClicked("GOLD", 100.0) }) {
            Text("Process Gold Order ($100)")
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = { viewModel.onProcessOrderClicked("PLATINUM", 200.0) }) {
            Text("Process Platinum Order ($200)")
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = { viewModel.onProcessOrderClicked("UNKNOWN_TYPE", 50.0) }) {
            Text("Trigger Exception Order")
        }
    }
}

@Composable
fun ComposeStateWidget(
    clickCount: Int,
    userNote: String,
    onIncrement: () -> Unit
) {
    val displayText = "Compose Clicks: $clickCount | Note: $userNote"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onIncrement) {
            Text("Increment Compose State")
        }
    }
}
