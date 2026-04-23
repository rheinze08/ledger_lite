package com.voiceledger.lite

import android.app.Application
import androidx.work.Configuration

class VoiceLedgerApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
