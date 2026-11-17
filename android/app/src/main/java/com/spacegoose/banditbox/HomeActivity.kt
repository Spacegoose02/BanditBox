package com.spacegoose.banditbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import com.spacegoose.banditbox.ui.HomeScreen
import com.spacegoose.banditbox.ui.theme.BanditBoxTheme

/**
 * The single activity. Registered as HOME, so pressing Home lands here.
 * Startup must never wait on the network: everything drawn here comes from local data.
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher has nowhere to go back to, so Back on the home screen does nothing.
        onBackPressedDispatcher.addCallback(this) {}
        setContent {
            BanditBoxTheme {
                HomeScreen()
            }
        }
    }
}
