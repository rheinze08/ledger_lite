package com.voiceledger.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import com.voiceledger.lite.semantic.AggregationScheduler
import com.voiceledger.lite.semantic.LedgerDatabaseFactory
import com.voiceledger.lite.semantic.LocalAggregationCoordinator
import com.voiceledger.lite.ui.LedgerMiniApp
import com.voiceledger.lite.ui.LedgerTheme
import com.voiceledger.lite.ui.LedgerViewModel
import com.voiceledger.lite.ui.LedgerViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = LedgerDatabaseFactory.open(applicationContext)
        val repository = LedgerRepository(database)
        val settingsStore = SettingsStore(applicationContext)
        val coordinator = LocalAggregationCoordinator(applicationContext, repository, settingsStore)
        val factory = LedgerViewModelFactory(
            appContext = applicationContext,
            repository = repository,
            settingsStore = settingsStore,
            coordinator = coordinator,
        )

        val settings = settingsStore.load()
        if (settings.backgroundProcessingEnabled) {
            AggregationScheduler.scheduleDaily(applicationContext, settings)
        }

        setContent {
            LedgerTheme {
                val viewModel: LedgerViewModel = viewModel(factory = factory)
                LedgerMiniApp(viewModel = viewModel)
            }
        }
    }
}
