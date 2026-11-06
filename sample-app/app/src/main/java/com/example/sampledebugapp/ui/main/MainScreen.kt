package com.example.sampledebugapp.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sampledebugapp.data.DefaultDataRepository

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) }
) {
    val orderStatus by viewModel.orderStatus.collectAsStateWithLifecycle()
    var clickCount by remember { mutableIntStateOf(0) }
    var composeNote by remember { mutableStateOf("Initial Compose Note") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF060913),
                        Color(0xFF0F172A),
                        Color(0xFF1E1B4B)
                    )
                )
            )
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.24f,
            animationSpec = infiniteRepeatable(
                animation = tween(850, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        val animatedProgress by animateFloatAsState(
            targetValue = ((clickCount % 6) + 1) / 6f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "progress"
        )
        val accentColor by animateColorAsState(
            targetValue = if (clickCount >= 3) Color(0xFF10B981) else Color(0xFF6366F1),
            animationSpec = tween(500),
            label = "accentColor"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Surface(
                    color = Color(0xFF064E3B),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color(0xFF10B981))
                ) {
                    Text(
                        text = "STAGE 5 • LIVE COMPOSE ANIMATIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFA7F3D0),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }

            Text(
                text = "Stage 5: Live Animations",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.testTag("text_title")
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                accentColor,
                                Color(0xFF8B5CF6),
                                Color(0xFFEC4899)
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ANIMATED TELEMETRY STREAM",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE0E7FF),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.3.sp
                    )
                    Text(
                        text = orderStatus,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.testTag("text_order_status")
                    )
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50)),
                        color = Color(0xFF34D399),
                        trackColor = Color(0x44FFFFFF)
                    )
                }
            }

            AnimatedVisibility(
                visible = clickCount >= 2,
                enter = fadeIn(tween(400)) + expandVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF065F46),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF34D399))
                ) {
                    Text(
                        text = "⚡ STREAK UNLOCKED • $clickCount clicks preserved across 5 hot-reload stages!",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFECFDF5),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            ComposeStateWidget(
                clickCount = clickCount,
                note = composeNote,
                accentColor = accentColor,
                onIncrement = {
                    clickCount++
                    composeNote = "Spring Pulse #$clickCount"
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.onProcessOrderClicked("GOLD", 100.0) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    modifier = Modifier.weight(1f).testTag("btn_gold_order")
                ) {
                    Text("Gold Pulse ($100)", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.onProcessOrderClicked("REGULAR", 50.0) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9)),
                    modifier = Modifier.weight(1f).testTag("btn_regular_order")
                ) {
                    Text("Regular Pulse ($50)", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ComposeStateWidget(
    clickCount: Int,
    note: String,
    accentColor: Color,
    onIncrement: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, accentColor),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "SPRING ANIMATED STATE ENGINE",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF34D399),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Text(
                text = "Clicks: $clickCount • $note",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("text_compose_state")
            )
            Button(
                onClick = onIncrement,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                modifier = Modifier.testTag("btn_increment_state")
            ) {
                Text("+1 Animate Spring", fontWeight = FontWeight.Bold)
            }
        }
    }
}
