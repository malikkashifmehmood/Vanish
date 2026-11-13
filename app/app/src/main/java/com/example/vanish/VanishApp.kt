package com.example.vanish

import android.app.Application
import com.example.vanish.data.ChatRepository

class VanishApp : Application() {
    lateinit var repo: ChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = ChatRepository(this)   // sweeps expired messages immediately, before any screen is shown
    }
}
