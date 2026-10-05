package com.gouge.xbot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun XbotAccountScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var serverUrl by rememberSaveable { mutableStateOf(state.serverUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    // The password is deliberately never saved to a Bundle or persisted.
    var password by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(state.serverUrl) { serverUrl = state.serverUrl }
    LaunchedEffect(state.isAuthenticated) { if (state.isAuthenticated) password = "" }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text("返回") }
                Text("XBot 账户", style = MaterialTheme.typography.headlineSmall)
                if (state.isAuthenticated) {
                    Text("已登录", style = MaterialTheme.typography.titleMedium)
                    Text("账户与信号服务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.serverUrl, style = MaterialTheme.typography.bodyMedium)
                    Text("退出后可以登录其他账户或切换服务地址。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmLogout = true },
                        enabled = !state.isChangingAlerts && !state.isSavingSettings,
                        modifier = Modifier.testTag("xbot-logout")) { Text("退出登录") }
                } else {
                    Text("登录后可管理信号和 TradingView 警报。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it },
                        label = { Text("账户与信号服务地址") }, singleLine = true,
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("xbot-login-server"))
                    OutlinedTextField(value = username, onValueChange = { username = it },
                        label = { Text("用户名") }, singleLine = true, enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth().testTag("xbot-login-username"))
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("密码") }, singleLine = true, enabled = !state.isLoading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("xbot-login-password"))
                    Button(onClick = { viewModel.login(serverUrl, username, password) },
                        enabled = !state.isLoading, modifier = Modifier.fillMaxWidth().testTag("xbot-login-submit")) {
                        if (state.isLoading) CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        else Text("登录")
                    }
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("退出 XBot 账户？") },
        text = { Text("账户信号和警报缓存将清除。行情分析仍可继续使用。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout() },
            modifier = Modifier.testTag("xbot-logout-confirm")) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
    )
}
