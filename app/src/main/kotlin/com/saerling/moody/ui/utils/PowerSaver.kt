// PowerSaver.kt
// whether the phone's battery saver is on, kept current while the screen shows. the glass reads it to stop blurring
// the page live (one of the costliest things drawn every frame) and lay a calm solid panel instead, as WaveBits does

package com.saerling.moody.ui.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current.applicationContext
    val power = remember(context) { context.getSystemService<PowerManager>() }
    var saver by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                saver = power?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        saver = power?.isPowerSaveMode == true
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return saver
}
