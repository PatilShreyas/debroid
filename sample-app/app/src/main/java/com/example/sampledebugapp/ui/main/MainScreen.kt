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

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
    val orderStatus by viewModel.orderStatus.collectAsStateWithLifecycle()

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
