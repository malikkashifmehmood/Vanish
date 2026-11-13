package com.example.vanish

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.example.vanish.data.ChatRepository
import com.example.vanish.ui.ChatScreen
import com.example.vanish.ui.JoinScreen

class MainActivity : ComponentActivity() {

    private val repo: ChatRepository get() = (application as VanishApp).repo

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = this.repo
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val name by repo.name.collectAsState()
                    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        if (name == null) JoinScreen(repo) else ChatScreen(repo)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming back from the background (or a cold start): delete what expired, reconnect.
        repo.onForeground()
    }
}
