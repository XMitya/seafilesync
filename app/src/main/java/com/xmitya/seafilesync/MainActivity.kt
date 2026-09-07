package com.xmitya.seafilesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.xmitya.seafilesync.app.appContainer
import com.xmitya.seafilesync.service.SyncForegroundService
import com.xmitya.seafilesync.service.SyncWatchdogWorker
import com.xmitya.seafilesync.ui.MainScreen
import com.xmitya.seafilesync.ui.MainViewModel
import com.xmitya.seafilesync.ui.theme.SeafileSyncTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(appContainer) {
            SyncForegroundService.start(this)
            SyncWatchdogWorker.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeafileSyncTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(viewModel, Modifier.padding(innerPadding))
                }
            }
        }
    }
}
