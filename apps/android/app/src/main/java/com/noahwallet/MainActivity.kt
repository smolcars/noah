package com.noahwallet

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.noahwallet.ui.LaunchGateView
import com.noahwallet.ui.NoahTheme

class MainActivity : FragmentActivity() {
    override fun onResume() {
        super.onResume()
        com.noahwallet.services.AppServices.shared(this).reconcileBackupTriggers(foreground = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoahTheme {
                LaunchGateView()
            }
        }
    }
}
