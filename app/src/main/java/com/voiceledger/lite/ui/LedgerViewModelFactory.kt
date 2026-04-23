package com.voiceledger.lite.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import com.voiceledger.lite.semantic.LocalAggregationCoordinator

class LedgerViewModelFactory(
    private val appContext: Context,
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val coordinator: LocalAggregationCoordinator,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LedgerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LedgerViewModel(appContext, repository, settingsStore, coordinator) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
