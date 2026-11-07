package com.jianji.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jianji.app.data.csv.XlsxTemplate
import com.jianji.app.ui.lock.LockGate
import com.jianji.app.ui.nav.AppRoot
import com.jianji.app.ui.theme.JianJiTheme
import com.jianji.app.widget.QuickWidgetProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 应用锁逻辑：
 *  - 冷启动：若设置里开了应用锁 → 直接进入锁门
 *  - ON_STOP：置锁（回到前台需重新验证），避免账单在任务切换页暴露
 *  - 验证：指纹 / 面部；未录入生物识别则回退系统 PIN/图案
 *  - 注意：锁门显示期间 AppRoot 不组合（导航回到首页），锁 = 一次全新会话
 */
class MainActivity : FragmentActivity() {

    private val locked = mutableStateOf(false)
    private val openRecordRequest = mutableStateOf(false)
    private val incomingImportUri = mutableStateOf<Uri?>(null)

    @Volatile
    private var appLockEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            incomingImportUri.value = savedInstanceState.getString("incoming_import_uri")?.let(Uri::parse)
        }

        val container = (application as JianJiApp).container

        // 订阅应用锁开关（供 ON_STOP 即时读取）
        container.scope.launch {
            container.settings.settings.collect { appLockEnabled = it.appLock }
        }
        // 冷启动上锁
        container.scope.launch {
            if (container.settings.settings.first().appLock) locked.value = true
        }
        // 退到后台即置锁
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && appLockEnabled) locked.value = true
        })

        setContent {
            JianJiTheme {
                if (locked.value) {
                    LockGate(onUnlock = ::tryUnlock)
                } else {
                    AppRoot(
                        openRecordRequest = openRecordRequest.value,
                        onOpenRecordConsumed = { openRecordRequest.value = false },
                        incomingImportUri = incomingImportUri.value,
                        onIncomingImportConsumed = { incomingImportUri.value = null },
                        onPickImportFile = ::openImportPicker,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("incoming_import_uri", incomingImportUri.value?.toString())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // FragmentActivity 要求请求码只使用低 16 位，不能用 ActivityResultRegistry 的随机请求码
    @Suppress("DEPRECATION")
    fun openImportPicker() {
        val primaryError = runCatching {
            val intent = ActivityResultContracts.OpenDocument().createIntent(
                this,
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "application/vnd.ms-excel",
                    XlsxTemplate.MIME,
                ),
            )
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
        }.exceptionOrNull() ?: return
        Log.w("MainActivity", "系统文档选择器不可用，尝试其他文件应用", primaryError)

        val fallbackError = runCatching {
            val intent = ActivityResultContracts.GetContent().createIntent(this, "*/*")
            startActivityForResult(intent, REQUEST_GET_CONTENT)
        }.exceptionOrNull() ?: return
        Log.e("MainActivity", "备用文件选择器也不可用", fallbackError)
        val reason = fallbackError.message?.take(100)?.let { "：$it" } ?: ""
        Toast.makeText(
            this,
            "无法打开文件选择器$reason\n也可在微信中用简记打开文件",
            Toast.LENGTH_LONG,
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK &&
            (requestCode == REQUEST_OPEN_DOCUMENT || requestCode == REQUEST_GET_CONTENT)
        ) {
            data?.data?.let { incomingImportUri.value = it }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(QuickWidgetProvider.EXTRA_OPEN) == "record") {
            openRecordRequest.value = true
        }
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
        }
        if (uri != null) incomingImportUri.value = uri
    }

    private fun tryUnlock() {
        val canAuth = BiometricManager.from(this).canAuthenticate()
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // 设备没设任何锁屏：应用锁无意义，放行并说明（个人 App 诚实优先）
            Toast.makeText(this, "设备未设置锁屏，应用锁暂不可用，已放行", Toast.LENGTH_LONG).show()
            locked.value = false
            return
        }
        val prompt = BiometricPrompt(
            this,
            Executors.newSingleThreadExecutor(),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    runOnUiThread { locked.value = false }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "未解锁：$errString", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁简记")
            .setSubtitle("验证以查看账单")
            .setDeviceCredentialAllowed(true)   // 无生物识别时回退 PIN/图案
            .build()
        prompt.authenticate(info)
    }

    companion object {
        private const val REQUEST_OPEN_DOCUMENT = 4_101
        private const val REQUEST_GET_CONTENT = 4_102
    }
}
