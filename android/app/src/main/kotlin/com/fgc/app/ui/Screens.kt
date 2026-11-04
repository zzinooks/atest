package com.fgc.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.fgc.app.FgcApp
import com.fgc.app.hc.HcSyncWorker
import com.fgc.app.hc.HealthConnectReader
import com.fgc.core.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: FgcApp, me: Me, openPatient: (String) -> Unit, openSettings: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("${me.displayName}님") }, actions = { TextButton(onClick = openSettings) { Text("설정") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (me.isPatient) PatientSummary(app, me.patientId!!, "내 혈당") { openPatient(me.patientId!!) }
            if (me.familyOf.isNotEmpty()) Text("가족", fontWeight = FontWeight.Bold)
            me.familyOf.forEach { f ->
                if (f.viewGlucose) PatientSummary(app, f.patientId, "${f.displayName} (${f.relation})") { openPatient(f.patientId) }
                else Card { Text("${f.displayName}: 혈당 조회 권한 없음 (메모만 가능)", Modifier.padding(12.dp)) }
            }
            if (!me.isPatient && me.familyOf.isEmpty()) {
                Text("혈당을 직접 관리하려면 설정에서 '환자로 시작하기'를, 가족의 혈당을 보려면 초대 코드를 입력하세요.")
            }
            HorizontalDivider()
            Text("가족 초대 코드 입력", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.uppercase() }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("10자리 코드") })
                Button(onClick = { scope.launch {
                    msg = try { app.api.acceptInvitation(code.trim()); code = ""; onChanged(); "연결되었습니다." }
                    catch (e: ApiException) { if (e.status == 404) "코드를 찾을 수 없습니다." else if (e.status == 409) "만료되었거나 이미 사용된 코드입니다." else e.message }
                } }, enabled = code.length >= 10) { Text("연결") }
            }
            msg?.let { Text(it) }
        }
    }
}

@Composable
private fun PatientSummary(app: FgcApp, pid: String, title: String, onClick: () -> Unit) {
    var latest by remember { mutableStateOf<Latest?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(pid) { runCatching { app.api.latest(pid) }.onSuccess { latest = it }.onFailure { failed = true } }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            when {
                latest != null -> {
                    val d = GlucoseDisplay.present(latest!!, null, Instant.now())
                    Text("${d.valueText} ${d.unit} ${d.trendArrow} · ${d.ageText}", color = if (d.dimmed) androidx.compose.ui.graphics.Color.Gray else levelColor(d.level))
                    d.banner?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                failed -> Text("불러오지 못했습니다", color = MaterialTheme.colorScheme.error)
                else -> Text("불러오는 중…")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(app: FgcApp, pid: String, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var relation by remember { mutableStateOf("자녀") }
    var view by remember { mutableStateOf(true) }
    var alerts by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf(true) }
    var invitation by remember { mutableStateOf<Invitation?>(null) }
    suspend fun load() { members = app.api.members(pid) }
    LaunchedEffect(pid) { load() }
    Scaffold(topBar = { TopAppBar(title = { Text("가족 공유") }, navigationIcon = { TextButton(onClick = back) { Text("뒤로") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("초대 코드를 만들어 가족에게 전달하세요. 코드를 입력한 가족에게 아래 권한으로 공유됩니다. (48시간 유효, 1회용)")
            OutlinedTextField(relation, { relation = it }, label = { Text("관계") }, singleLine = true)
            Toggle("혈당 보기", view) { view = it; if (!it) alerts = false }
            Toggle("알림 받기", alerts, enabled = view) { alerts = it }
            Toggle("메모·기록 쓰기", notes) { notes = it }
            Button(onClick = { scope.launch { invitation = app.api.invite(pid, InviteRequest(relation, Permissions(view, alerts, notes))) } }) { Text("초대 코드 만들기") }
            invitation?.let { Card { Column(Modifier.padding(12.dp)) {
                Text(it.code, style = MaterialTheme.typography.headlineMedium)
                Text("만료: ${TIME.format(it.expiresAt)}")
            } } }
            HorizontalDivider()
            Text("연결된 가족", fontWeight = FontWeight.Bold)
            members.forEach { m ->
                Card { Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${m.displayName} (${m.relation})", fontWeight = FontWeight.Bold)
                        Text(if (m.status == "ACTIVE") listOfNotNull(if (m.permissions.viewGlucose) "보기" else null,
                            if (m.permissions.receiveAlerts) "알림" else null, if (m.permissions.writeNotes) "기록" else null).joinToString(" · ")
                            else "공유 해제됨")
                    }
                    if (m.status == "ACTIVE") OutlinedButton(onClick = { scope.launch { app.api.revokeMember(pid, m.memberId); load() } }) { Text("공유 해제") }
                } }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Switch(value, onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(app: FgcApp, pid: String, editable: Boolean, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<AlertRules?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pid) { rules = app.api.alertRules(pid) }
    Scaffold(topBar = { TopAppBar(title = { Text("알림 기준") }, navigationIcon = { TextButton(onClick = back) { Text("뒤로") } }) }) { pad ->
        val r = rules ?: return@Scaffold
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기준값은 의료진과 상의해 정하세요. 앱은 권장값을 계산하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            NumberField("매우 낮음 (mg/dL)", r.urgentLowMgdl, editable) { rules = r.copy(urgentLowMgdl = it) }
            NumberField("낮음 (mg/dL)", r.lowMgdl, editable) { rules = r.copy(lowMgdl = it) }
            NumberField("높음 (mg/dL)", r.highMgdl, editable) { rules = r.copy(highMgdl = it) }
            NumberField("미수신 알림 (분)", r.noDataMinutes, editable) { rules = r.copy(noDataMinutes = it) }
            NumberField("재알림 간격 (분)", r.repeatMinutes, editable) { rules = r.copy(repeatMinutes = it) }
            Toggle("저혈당 알림", r.lowEnabled, editable) { rules = r.copy(lowEnabled = it) }
            Toggle("고혈당 알림", r.highEnabled, editable) { rules = r.copy(highEnabled = it) }
            Toggle("급변동 알림", r.rapidEnabled, editable) { rules = r.copy(rapidEnabled = it) }
            Toggle("미수신 알림", r.noDataEnabled, editable) { rules = r.copy(noDataEnabled = it) }
            if (editable) Button(onClick = { scope.launch {
                msg = try { rules = app.api.saveAlertRules(pid, r); "저장했습니다." } catch (e: ApiException) { "값을 확인하세요 (매우 낮음 < 낮음 < 높음)." }
            } }) { Text("저장") }
            msg?.let { Text(it) }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3); text.toIntOrNull()?.let(onChange) }, label = { Text(label) },
        enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(app: FgcApp, pid: String, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<HealthEvent>>(emptyList()) }
    var type by remember { mutableStateOf("NOTE") }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() { events = app.api.events(pid, Instant.now().minus(Duration.ofDays(7))).reversed() }
    LaunchedEffect(pid) { load() }
    Scaffold(topBar = { TopAppBar(title = { Text("기록·메모") }, navigationIcon = { TextButton(onClick = back) { Text("뒤로") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("MEAL" to "식사", "EXERCISE" to "운동", "MEDICATION" to "복약", "NOTE" to "메모").forEach { (k, v) ->
                    FilterChip(selected = type == k, onClick = { type = k }, label = { Text(v) })
                }
            }
            OutlinedTextField(title, { title = it.take(100) }, label = { Text("내용") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch {
                try { app.api.addEvent(pid, NewHealthEvent(type, Instant.now().minusSeconds(1), title)); title = ""; error = null; load() }
                catch (e: ApiException) { error = if (e.isForbidden) "기록 권한이 없습니다." else e.message }
            } }, enabled = title.isNotBlank()) { Text("추가") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            events.forEach { e -> Text("${TIME.format(e.occurredAt)} [${e.type}] ${e.title} — ${e.authorName ?: ""}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: FgcApp, me: Me, onLoggedOut: () -> Unit, onChanged: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var origins by remember { mutableStateOf<Set<String>>(emptySet()) }
    var emuUrl by remember { mutableStateOf("http://10.0.2.2:8090") }
    var emuUser by remember { mutableStateOf("patient01") }
    var emuPass by remember { mutableStateOf("") }
    var withdrawPw by remember { mutableStateOf("") }
    val reader = remember { HealthConnectReader(ctx) }
    val hcPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.contains(HealthConnectReader.READ_GLUCOSE)) {
            app.settings.hcEnabled = true
            HcSyncWorker.schedule(ctx)
            scope.launch { origins = reader.origins(Instant.now().minus(Duration.ofDays(3)), Instant.now()) }
            msg = "Health Connect 연결됨. 혈당을 기록하는 앱을 선택하세요."
        } else msg = "Health Connect 권한이 거부되었습니다."
    }

    Scaffold(topBar = { TopAppBar(title = { Text("설정") }, navigationIcon = { TextButton(onClick = back) { Text("뒤로") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${me.displayName} · ${me.email}")
            if (!me.isPatient) Button(onClick = { scope.launch { app.api.becomePatient(ZoneId.systemDefault().id); onChanged() } }) { Text("환자로 시작하기") }

            if (me.isPatient) {
                Text("데이터 연결", fontWeight = FontWeight.Bold)
                val sdk = reader.availability()
                if (sdk == HealthConnectClient.SDK_AVAILABLE) {
                    Button(onClick = { hcPermission.launch(HealthConnectReader.ALL) }) { Text("Health Connect 연결") }
                    origins.forEach { o -> Row { RadioButton(app.settings.hcOrigin == o, { app.settings.hcOrigin = o; HcSyncWorker.runNow(ctx) }); Text(o) } }
                    Text("※ 바로잰 Fit 이 Health Connect 에 혈당을 기록하는지는 실기기에서 확인되지 않았습니다.", style = MaterialTheme.typography.bodySmall)
                } else Text("이 기기에서 Health Connect 를 사용할 수 없습니다.")

                Text("연습용 가상 CGM 에뮬레이터 연결", fontWeight = FontWeight.Bold)
                OutlinedTextField(emuUrl, { emuUrl = it }, label = { Text("에뮬레이터 주소 (서버에서 접근 가능한 주소)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(emuUser, { emuUser = it }, label = { Text("사용자") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(emuPass, { emuPass = it }, label = { Text("비밀번호") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch {
                    msg = try { app.api.addDataSource(me.patientId!!, NewDataSource("EMULATOR", emuUrl, emuUser, emuPass)); emuPass = ""; "에뮬레이터 연결됨 (1분 내 수집 시작)" }
                    catch (e: ApiException) { e.message }
                } }, enabled = emuPass.isNotBlank()) { Text("에뮬레이터 연결") }

                Text("Notion 요약 기록", fontWeight = FontWeight.Bold)
                Text("일별·주간 혈당 요약을 Notion 에 기록합니다. 건강정보가 외부 서비스로 전송됩니다.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { app.api.setNotion(me.patientId!!, true); msg = "Notion 기록에 동의했습니다." } }) { Text("동의") }
                    OutlinedButton(onClick = { scope.launch { app.api.setNotion(me.patientId!!, false); msg = "Notion 기록을 중단했습니다." } }) { Text("중단") }
                }
            }
            msg?.let { Text(it) }
            HorizontalDivider()
            Button(onClick = { scope.launch { app.api.logout(); HcSyncWorker.cancel(ctx); onLoggedOut() } }) { Text("로그아웃") }
            Text("회원 탈퇴 — 환자 데이터(혈당·알림·기록)가 모두 삭제됩니다.", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(withdrawPw, { withdrawPw = it }, label = { Text("비밀번호 확인") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { scope.launch {
                try { app.api.withdraw(withdrawPw); HcSyncWorker.cancel(ctx); onLoggedOut() } catch (e: ApiException) { msg = "비밀번호가 맞지 않습니다." }
            } }, enabled = withdrawPw.length >= 8) { Text("탈퇴하기") }
        }
    }
}
