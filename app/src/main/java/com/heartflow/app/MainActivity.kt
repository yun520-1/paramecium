package com.heartflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.heartflow.tool.builtin.GeckoEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 初始化 GeckoView 浏览器引擎（首次使用时懒加载）
        GeckoEngine.init(this)
        setContent {
            HeartFlowApp()
        }
    }
}

/**
 * 本地 BackPressedDispatcher 提供者
 */
val LocalBackDispatcher = staticCompositionLocalOf<OnBackPressedDispatcher> {
    error("No OnBackPressedDispatcher provided")
}
