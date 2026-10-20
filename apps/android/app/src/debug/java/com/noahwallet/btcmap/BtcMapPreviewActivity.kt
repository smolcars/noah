package com.noahwallet.btcmap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.noahwallet.ui.NoahTheme

/** Debug-only public map entry; no wallet or wallet services are initialized. */
class BtcMapPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NoahTheme { Surface { Box(Modifier.fillMaxSize().safeDrawingPadding()) { BtcMapScreen { finish() } } } } }
    }
}
