package com.jianji.app.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub

/** 全屏锁门：指纹 / PIN 验证通过后进入 App */
@Composable
fun LockGate(onUnlock: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text("🔒", fontSize = 52.sp)
        Spacer(Modifier.height(18.dp))
        Text("简记已锁定", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("点击下方按钮验证身份", fontSize = 13.sp, color = Sub)
        Spacer(Modifier.height(30.dp))
        Button(onClick = onUnlock) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Text("解锁")
        }
    }
}
