package com.fgc.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fgc.app.FgcApp
import com.fgc.core.ApiException
import com.fgc.core.Me
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** 로그인 상태에 따라 화면 분기. 역할(환자/가족)은 /me 의 isPatient, familyOf 로 결정. */
@Composable
fun AppRoot(app: FgcApp, initialAlertId: String?) {
    var me by remember { mutableStateOf<Me?>(null) }
    var loggedIn by remember { mutableStateOf(app.tokens.refreshToken != null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshMe() {
        try {
            me = app.api.me()
            app.settings.patientId = me?.patientId
            runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()?.let { app.api.registerDevice(it) }
        } catch (e: ApiException) {
            if (e.isUnauthorized) loggedIn = false else error = e.message
        } catch (e: java.io.IOException) {
            error = "서버에 연결할 수 없습니다. 네트워크를 확인하세요."
        }
    }

    LaunchedEffect(loggedIn) { if (loggedIn) refreshMe() }

    if (!loggedIn) {
        LoginScreen(onLogin = { email, pw, name, signup ->
            scope.launch {
                try {
                    if (signup) app.api.signup(email, pw, name) else app.api.login(email, pw)
                    loggedIn = true
                    error = null
                    refreshMe()
                } catch (e: ApiException) {
                    error = when (e.status) { 401 -> "이메일 또는 비밀번호가 맞지 않습니다."; 409 -> "이미 가입된 이메일입니다."
                        429 -> "로그인 시도가 많습니다. 잠시 후 다시 시도하세요."; else -> e.message }
                } catch (e: java.io.IOException) { error = "서버에 연결할 수 없습니다." }
            }
        }, error = error)
        return
    }
    val current = me ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) Text(error!!) else CircularProgressIndicator()
        }
        return
    }

    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(app, current,
                openPatient = { nav.navigate("patient/$it") },
                openSettings = { nav.navigate("settings") },
                onChanged = { scope.launch { refreshMe() } })
        }
        composable("patient/{pid}") { b ->
            val pid = b.arguments!!.getString("pid")!!
            val owner = current.patientId == pid
            val perms = current.familyOf.firstOrNull { it.patientId == pid }
            PatientScreen(app, pid, owner, canWriteNotes = owner || perms?.writeNotes == true,
                canAck = owner || perms?.receiveAlerts == true, highlightAlert = initialAlertId,
                openSharing = { nav.navigate("sharing/$pid") }, openRules = { nav.navigate("rules/$pid") },
                openEvents = { nav.navigate("events/$pid") }, back = { nav.popBackStack() })
        }
        composable("sharing/{pid}") { b -> SharingScreen(app, b.arguments!!.getString("pid")!!) { nav.popBackStack() } }
        composable("rules/{pid}") { b ->
            val pid = b.arguments!!.getString("pid")!!
            RulesScreen(app, pid, editable = current.patientId == pid) { nav.popBackStack() }
        }
        composable("events/{pid}") { b -> EventsScreen(app, b.arguments!!.getString("pid")!!) { nav.popBackStack() } }
        composable("settings") {
            SettingsScreen(app, current, onLoggedOut = { loggedIn = false; me = null },
                onChanged = { scope.launch { refreshMe() } }, back = { nav.popBackStack() })
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String, String, String, Boolean) -> Unit, error: String?) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var signup by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("가족 혈당 케어", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it }, label = { Text("이메일") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pw, { pw = it }, label = { Text("비밀번호 (8자 이상)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (signup) OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onLogin(email.trim(), pw, name.trim(), signup) }, modifier = Modifier.fillMaxWidth(),
            enabled = email.isNotBlank() && pw.length >= 8 && (!signup || name.isNotBlank())) {
            Text(if (signup) "가입하기" else "로그인")
        }
        TextButton(onClick = { signup = !signup }) { Text(if (signup) "이미 계정이 있어요" else "처음이에요 (가입)") }
    }
}
