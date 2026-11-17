package com.example.sshcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.sshcheck.ui.navigation.ServerMonitorApp
import com.example.sshcheck.ui.theme.SSHcheckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SSHcheckTheme { ServerMonitorApp() } }
    }
}
