// AccountAutoSyncHost.kt
// the web page the automatic sync signs in through: the same one the import shows, laid off screen where it can't be
// seen or touched, and only while a pass runs. it reuses the session you signed in with, it never types anything.

package com.saerling.moody.ui.screens.settings.importsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saerling.moody.importer.account.AccountAutoSyncViewModel

@Composable
fun AccountAutoSyncHost(viewModel: AccountAutoSyncViewModel = hiltViewModel()) {
    val service by viewModel.running.collectAsState()
    val running = service ?: return
    val url = running.loginUrl ?: return
    key(running) {
        // a phone-sized page far past the left edge: laid out and loading like a real one, never on screen
        Box(Modifier.offset(x = (-4000).dp).requiredSize(width = 400.dp, height = 800.dp)) {
            CaptureWebContent(
                startUrl = url,
                playerOrigins = running.playerOrigins,
                desktopSite = running.desktopSite,
                onHeader = { name, value, from -> viewModel.onHeader(name, value, from) },
                onTick = { view -> running.onSignInTick(view, viewModel::onApplePageProbe, viewModel::onCookies) },
                onExit = {},
            )
        }
    }
}
