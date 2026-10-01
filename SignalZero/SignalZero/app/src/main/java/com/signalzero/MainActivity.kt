package com.signalzero

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.signalzero.ui.AppViewModel
import com.signalzero.ui.SignalZeroNav
import com.signalzero.ui.theme.SignalZeroTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SignalZeroTheme { SignalZeroNav(vm) } }
    }
}
