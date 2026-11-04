package com.fgc.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgc.app.FgcApp
import com.fgc.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 환자 상세: 현재 혈당(측정 시각·신선도 포함), 그래프, 오늘 통계, 알림, 기록. 60초마다 갱신. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientScreen(app: FgcApp, pid: String, owner: Boolean, canWriteNotes: Boolean, canAck: Boolean, highlightAlert: String?,
                  openSharing: () -> Unit, openRules: () -> Unit, openEvents: () -> Unit, back: () -> Unit) {
    var latest by remember { mutableStateOf<Latest?>(null) }
    var rules by remember { mutableStateOf<AlertRules?>(null) }
    var readings by remember { mutableStateOf<List<Reading>>(emptyList()) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var alerts by remember { mutableStateOf<List<AlertItem>>(emptyList()) }
    var hours by remember { mutableIntStateOf(6) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val now = Instant.now()
            latest = app.api.latest(pid)
            rules = app.api.alertRules(pid)
            readings = app.api.readings(pid, now.minus(Duration.ofHours(hours.toLong())))
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            stats = app.api.stats(pid, todayStart)
            alerts = app.api.alerts(pid, activeOnly = true)
            error = null
        } catch (e: ApiException) {
            error = if (e.isForbidden) "이 환자의 혈당을 볼 권한이 없습니다 (공유가 해제되었을 수 있습니다)." else e.message
        } catch (e: java.io.IOException) {
            error = "서버에 연결할 수 없습니다. 마지막으로 받은 정보입니다."
        }
    }
    LaunchedEffect(pid, hours) { while (true) { load(); delay(60_000) } }

    Scaffold(topBar = { TopAppBar(title = { Text(if (owner) "내 혈당" else "가족 혈당") },
        navigationIcon = { TextButton(onClick = back) { Text("뒤로") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            latest?.let { GlucoseCard(GlucoseDisplay.present(it, rules, Instant.now())) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 6, 24).forEach { h -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("${h}시간") }) }
            }
            GlucoseChart(readings, rules, hours)

            if (alerts.isNotEmpty()) {
                Text("진행 중인 알림", fontWeight = FontWeight.Bold)
                alerts.forEach { a ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (a.id == highlightAlert) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(a.label, fontWeight = FontWeight.Bold)
                                Text("시작 ${GlucoseDisplay.ageText(Duration.between(a.openedAt, Instant.now()))}" +
                                    (a.acknowledgedBy?.let { " · ${it}님이 확인함" } ?: ""))
                            }
                            if (canAck && a.status == "OPEN") Button(onClick = { scope.launch { app.api.acknowledge(a.id); load() } }) { Text("확인") }
                        }
                    }
                }
            }

            stats?.let { s ->
                Card { Column(Modifier.padding(12.dp)) {
                    Text("오늘 통계", fontWeight = FontWeight.Bold)
                    if (!s.reliable) Text("데이터가 충분하지 않아 참고용입니다 (충족률 ${(s.coverage * 100).toInt()}%)", color = MaterialTheme.colorScheme.error)
                    Text("목표 범위(70–180) ${s.inRangePct}% · 낮음 ${s.veryLowPct + s.lowPct}% · 높음 ${s.highPct + s.veryHighPct}%")
                    Text("평균 ${s.mean ?: "-"} · 변동계수 ${s.cvPercent ?: "-"}% · 측정 ${s.readings}회")
                } }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = openEvents) { Text(if (canWriteNotes) "기록·메모" else "기록 보기") }
                OutlinedButton(onClick = openRules) { Text("알림 기준") }
                if (owner) OutlinedButton(onClick = openSharing) { Text("가족 공유") }
            }
            Text("이 앱의 알림은 늦거나 오지 않을 수 있습니다(네트워크·서버·기기 설정). 의료적 판단을 대신하지 않으며, 응급 상황에서는 119에 연락하세요.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun GlucoseCard(d: DisplayState) {
    val color = if (d.dimmed) Color.Gray else levelColor(d.level)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(d.valueText, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.width(8.dp))
                Text("${d.unit} ${d.trendArrow}", fontSize = 22.sp, color = color)
            }
            Text("${levelText(d.level)} · ${d.trendDescription}".trimEnd(' ', '·'))
            Text("측정: ${d.ageText}", fontWeight = FontWeight.Medium)
            d.banner?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** 목표 범위 띠(70–180) + 측정값 선. 측정이 끊긴 구간은 선을 잇지 않는다. */
@Composable
fun GlucoseChart(readings: List<Reading>, rules: AlertRules?, hours: Int) {
    val segments = remember(readings) { GraphSeries.segments(readings) }
    val now = Instant.now()
    val start = now.minus(Duration.ofHours(hours.toLong()))
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        fun x(t: Instant) = size.width * (Duration.between(start, t).toMillis().toFloat() / Duration.ofHours(hours.toLong()).toMillis())
        fun y(v: Int) = size.height * (1 - (v.coerceIn(40, 400) - 40) / 360f)
        drawRect(Color(0x221E8E3E), topLeft = Offset(0f, y(180)), size = androidx.compose.ui.geometry.Size(size.width, y(70) - y(180)))
        rules?.let { drawLine(Color(0x88E8710A), Offset(0f, y(it.lowMgdl)), Offset(size.width, y(it.lowMgdl)), 2f) }
        segments.forEach { seg ->
            val path = Path()
            seg.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.at), y(p.mgdl)) else path.lineTo(x(p.at), y(p.mgdl)) }
            drawPath(path, lineColor, style = Stroke(width = 4f))
            seg.filter { it.outOfRange }.forEach { drawCircle(Color.Red, 6f, Offset(x(it.at), y(it.mgdl))) }
        }
    }
}
