package com.example.spotads

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.spotads.ui.theme.SpotAdsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpotAdsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(AdMutePrefs.isEnabled(context)) }
    var listenerGranted by remember { mutableStateOf(isListenerGranted(context)) }
    var state by remember { mutableStateOf(AdMuteListenerService.currentState) }
    var event by remember { mutableStateOf(AdMuteListenerService.lastEvent ?: "Waiting for Spotify…") }
    var todayCount by remember { mutableIntStateOf(AdMutePrefs.todayMutedCount(context)) }
    var lifetimeCount by remember { mutableIntStateOf(AdMutePrefs.lifetimeMutedCount(context)) }

    DisposableEffect(Unit) {
        listenerGranted = isListenerGranted(context)
        todayCount = AdMutePrefs.todayMutedCount(context)
        lifetimeCount = AdMutePrefs.lifetimeMutedCount(context)

        AdMuteListenerService.setStatusListener { newState, newEvent ->
            state = newState
            event = newEvent
            todayCount = AdMuteListenerService.todayAdCount
            lifetimeCount = AdMuteListenerService.lifetimeAdCount
        }
        onDispose { AdMuteListenerService.setStatusListener(null) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopAppBar(title = { Text("Melos") })

        Spacer(Modifier.height(8.dp))

        if (!listenerGranted) {
            PermissionCard(onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            Spacer(Modifier.height(16.dp))
        }

        StatusCard(state = state, event = event)

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CountCard(
                modifier = Modifier.weight(1f),
                label = "Today",
                count = todayCount
            )
            CountCard(
                modifier = Modifier.weight(1f),
                label = "All time",
                count = lifetimeCount
            )
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Mute ads", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Silences music while a Spotify ad plays",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        AdMutePrefs.setEnabled(context, it)
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Notification access needed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "spotAds needs notification access to detect Spotify ads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) {
                Text("Open settings")
            }
        }
    }
}

@Composable
private fun StatusCard(state: AdMuteState, event: String) {
    val (label, color) = when (state) {
        AdMuteState.MUTED -> "Muted" to MaterialTheme.colorScheme.primary
        AdMuteState.PENDING_RESTORE -> "Restoring…" to MaterialTheme.colorScheme.tertiary
        AdMuteState.NORMAL -> "Listening" to MaterialTheme.colorScheme.secondary
        AdMuteState.NO_SESSION -> "No Spotify session" to MaterialTheme.colorScheme.outline
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape)
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    event,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CountCard(modifier: Modifier = Modifier, label: String, count: Int) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun isListenerGranted(context: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    SpotAdsTheme {
        HomeScreen()
    }
}
