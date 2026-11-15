package com.chaewon.safedrive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.chaewon.safedrive.SafeDriveApp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaewon.safedrive.app.DriveController
import com.chaewon.safedrive.app.UiState
import com.chaewon.safedrive.core.*
import com.chaewon.safedrive.signal.DemoScenario
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 화면 원칙: 전문용어(기준값·분위수·m/s²)는 화면에 쓰지 않는다. '자세히 보기'에서만 숫자 노출.

private enum class Tab(val label: String) { HOME("홈"), RECORDS("기록"), SUMMARY("주행 요약"), SETTINGS("설정") }

private val Warn = Color(0xFFFF8A65)
private val Calm = Color(0xFF81C784)
private val CarColor = Color(0xFF90A4AE)

@Composable
fun AppRoot(controller: DriveController, onMic: () -> Unit = {}) {
    val state by controller.state.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.active == null) ProfileScreen(state, controller) else MainScreen(state, controller, onMic)
    }
}

// ---------------------------------------------------------------- 프로필

@Composable
private fun ProfileScreen(state: UiState, c: DriveController) {
    var name by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("누가 운전하나요?", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("운전자마다 습관이 달라서, 기준도 사람마다 따로 배워요.", fontSize = 18.sp, color = Color.LightGray)

        state.profiles.forEach { p ->
            Button(onClick = { c.selectProfile(p.id) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(p.name, fontSize = 22.sp)
            }
        }

        HorizontalDivider()
        Text("새 운전자 추가", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text(
                "주행 중 차량 신호(브레이크·크루즈·위치)를 읽어 이 차 안에만 기록하는 데 동의해요. " +
                    "이 앱은 차량을 조작하지 않아요.",
                fontSize = 15.sp,
            )
        }
        Button(onClick = { c.selectProfile(c.createProfile(name)); name = "" }, enabled = agreed && name.isNotBlank()) {
            Text("추가하고 시작하기", fontSize = 18.sp)
        }
    }
}

// ---------------------------------------------------------------- 메인

@Composable
private fun MainScreen(state: UiState, c: DriveController, onMic: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    // 주행 중에는 홈 화면만 (음성 중심). 정차 중에만 다른 화면 조작 가능.
    LaunchedEffect(state.driving) { if (state.driving) tab = Tab.HOME }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("👤 ${state.active?.name}", fontSize = 18.sp, modifier = Modifier.padding(end = 16.dp))
            Tab.entries.forEach { t ->
                val enabled = !state.driving || t == Tab.HOME
                TextButton(onClick = { tab = t }, enabled = enabled) {
                    Text(
                        t.label, fontSize = 18.sp, maxLines = 1, softWrap = false,
                        fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal,
                        color = if (tab == t) MaterialTheme.colorScheme.primary else if (enabled) Color.LightGray else Color.DarkGray,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.driving) Text("🔒 주행 중엔 음성으로 안내해요", fontSize = 15.sp, color = Color.Gray)
        }
        Box(Modifier.fillMaxSize().padding(20.dp)) {
            when (tab) {
                Tab.HOME -> HomeScreen(state, c, onMic)
                Tab.RECORDS -> RecordsScreen(state)
                Tab.SUMMARY -> SummaryScreen(state)
                Tab.SETTINGS -> SettingsScreen(state, c)
            }
        }
    }
}

// ---------------------------------------------------------------- 홈

@Composable
private fun HomeScreen(state: UiState, c: DriveController, onMic: () -> Unit) {
    val scope = (LocalContext.current.applicationContext as SafeDriveApp).appScope
    var step by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                if (state.driving) "안전 주행 중" else "정차 중",
                fontSize = 40.sp, fontWeight = FontWeight.Bold,
                color = if (state.driving) Calm else Color.White,
            )
            if (state.driving) Text("시속 ${state.speedKmh}km", fontSize = 28.sp)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.learning) {
                        Text("내 운전 습관 배우는 중 ${(state.learningProgress * 100).toInt()}%", fontSize = 22.sp)
                        LinearProgressIndicator(progress = { state.learningProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text("다 배우기 전에는 기본 기준으로 알려 드려요.", fontSize = 15.sp, color = Color.Gray)
                    } else {
                        Text("✅ 내 기준 준비 완료", fontSize = 22.sp)
                        Text("평소의 나보다 센 감속만 알려 드려요 · 민감도 ${state.sensitivity.label}", fontSize = 15.sp, color = Color.Gray)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Stat("이번 주행 내 급감속", "${state.tripMyCount}회", Warn)
                    Stat("차량이 스스로 감속", "${state.tripCarCount}회", CarColor)
                }
            }

            state.lastSpoken?.let { Text("🔊 \"$it\"", fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary) }

            // 음성 기록 (Pleos SpeechToText) — 큰 버튼 하나, 누르고 말하면 '직접 기록'
            if (state.availability.speech) {
                Button(
                    onClick = onMic,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.listening != null) Warn else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        when {
                            state.listening == null -> "🎤 말로 기록하기"
                            state.listening.isEmpty() -> "듣고 있어요… (다시 누르면 멈춤)"
                            else -> "🗣 \"${state.listening}\""
                        },
                        fontSize = 22.sp,
                    )
                }
            }

            // 지금 어떤 신호로 동작 중인지 정직하게 표시
            val a = state.availability
            val connected = listOfNotNull(
                "위치".takeIf { a.location }, "브레이크".takeIf { a.brake }, "크루즈".takeIf { a.cruise },
                "에어백".takeIf { a.airbag }, "음성인식".takeIf { a.speech },
            )
            Text(
                if (connected.isEmpty()) "차량 신호 미연결 · 데모 모드로 확인할 수 있어요"
                else "연결된 차량 신호: " + connected.joinToString(" · "),
                fontSize = 14.sp, color = Color.Gray,
            )
        }

        // 데모 모드 패널 (시연 영상용)
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("데모 모드", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("에뮬레이터 신호 대신 가상 주행을 재생해요.", fontSize = 14.sp, color = Color.Gray)
                OutlinedButton(
                    onClick = { c.quiet = true; DemoScenario().seedHistory(c, System.currentTimeMillis()); c.quiet = false },
                    enabled = !state.demoRunning && !state.driving, modifier = Modifier.fillMaxWidth(),
                ) { Text("① 평소 주행 5회 기록 (학습)") }
                Button(
                    onClick = {
                        c.setDemoRunning(true)
                        job = DemoScenario().runDemoDrive(
                            scope, c, { System.currentTimeMillis() },
                            onStep = { step = it },
                            onDone = { c.setDemoRunning(false) },
                        )
                    },
                    enabled = !state.demoRunning, modifier = Modifier.fillMaxWidth(),
                ) { Text("② 시연 주행 시작") }
                if (state.demoRunning) {
                    TextButton(onClick = { job?.cancel(); c.endTripNow(); c.setDemoRunning(false); step = "중단됨" }) { Text("중단") }
                }
                step?.let { Text(it, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 15.sp, color = Color.Gray)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ---------------------------------------------------------------- 기록

private enum class Filter(val label: String, val types: Set<EventType>?) {
    ALL("전체", null),
    MINE("내 급감속", setOf(EventType.MY_HARD_DECEL)),
    CAR("차량 감속", setOf(EventType.CAR_DECEL)),
    MANUAL("직접 기록", setOf(EventType.MANUAL)),
    SAFETY("사고·경고", setOf(EventType.CRASH, EventType.VEHICLE_WARNING)),
}

@Composable
private fun RecordsScreen(state: UiState) {
    var filter by remember { mutableStateOf(Filter.ALL) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val shown = state.events.filter { filter.types == null || it.type in filter.types!! }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label, fontSize = 16.sp, maxLines = 1, softWrap = false) })
            }
        }
        if (shown.isEmpty()) Text("아직 기록이 없어요.", fontSize = 18.sp, color = Color.Gray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown) { e ->
                EventCard(e, expanded == e.id) { expanded = if (expanded == e.id) null else e.id }
            }
        }
    }
}

@Composable
private fun EventCard(e: DriveEvent, open: Boolean, onToggle: () -> Unit) {
    val (icon, color) = when (e.type) {
        EventType.MY_HARD_DECEL -> "⚠️" to Warn
        EventType.CAR_DECEL -> "🚗" to CarColor
        EventType.MANUAL -> "📌" to MaterialTheme.colorScheme.primary
        EventType.CRASH -> "🆘" to Color(0xFFE57373)
        EventType.VEHICLE_WARNING -> "🔧" to Color(0xFFFFD54F)
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$icon ${e.type.label}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color)
                Spacer(Modifier.weight(1f))
                Text(fmt(e.timestampMillis), fontSize = 16.sp, color = Color.Gray)
            }
            e.speedMps?.let { Text("당시 시속 ${(it * 3.6).toInt()}km", fontSize = 16.sp) }
            if (e.type == EventType.CAR_DECEL) Text("크루즈 중 브레이크 없이 감속 · 내 운전 평가에서 제외", fontSize = 15.sp, color = Color.Gray)
            e.note?.let { Text("🗣 \"$it\"", fontSize = 16.sp) }
            if (open) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("자세히 보기", fontSize = 14.sp, color = Color.Gray)
                Text(detail(e), fontSize = 14.sp, color = Color.LightGray)
            }
        }
    }
}

private fun detail(e: DriveEvent) = buildList {
    e.decelMps2?.let { add("감속도 %.2f m/s²".format(it)) }
    e.thresholdMps2?.let { add("당시 내 기준 %.2f m/s²".format(it)) }
    e.brakePressed?.let { add("브레이크 " + if (it) "밟음" else "안 밟음") }
    e.cruiseOn?.let { add("크루즈 " + if (it) "켜짐" else "꺼짐") }
    e.cruiseTargetMps?.let { add("크루즈 목표 시속 ${(it * 3.6).toInt()}km") }
    if (e.latitude != null && e.longitude != null) add("위치 %.5f, %.5f".format(e.latitude, e.longitude))
}.joinToString(" · ")

private fun fmt(t: Long) = SimpleDateFormat("M월 d일 HH:mm:ss", Locale.KOREA).format(Date(t))

// ---------------------------------------------------------------- 주행 요약

@Composable
private fun SummaryScreen(state: UiState) {
    val s = state.lastSummary
    if (s == null) { Text("주행이 끝나면 '평소의 나'와 비교한 요약이 여기에 나와요.", fontSize = 20.sp, color = Color.Gray); return }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("최근 주행 · ${fmt(s.trip.endMillis)}", fontSize = 16.sp, color = Color.Gray)
            Text("내 급감속 ${s.count}회", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            val cmpColor = when (s.countComparison) { Comparison.MORE -> Warn; Comparison.FEWER -> Calm; else -> Color.White }
            Text(s.countComparison.label, fontSize = 26.sp, color = cmpColor)
            if (s.strength != Strength.UNKNOWN) Text("감속 세기: ${s.strength.label}", fontSize = 20.sp)
            Text("🚗 차량이 스스로 감속 ${s.carDecelCount}회 · 내 평가에서 제외", fontSize = 17.sp, color = CarColor)
            Text("주행 거리 %.1fkm".format(s.trip.distanceM / 1000), fontSize = 16.sp, color = Color.Gray)
        }
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(20.dp)) {
                Text("최근 주행별 내 급감속", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Bars(s.recentCounts)
            }
        }
    }
}

@Composable
private fun Bars(counts: List<Int>) {
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        counts.forEachIndexed { i, n ->
            val today = i == counts.lastIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$n", fontSize = 14.sp)
                Box(
                    Modifier.fillMaxWidth().height((8 + 110 * n / max).dp)
                        .background(if (today) Warn else Color(0xFF455A64), RoundedCornerShape(6.dp)),
                )
                Text(if (today) "이번" else "${counts.size - 1 - i}회 전", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

// ---------------------------------------------------------------- 설정

@Composable
private fun SettingsScreen(state: UiState, c: DriveController) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("얼마나 민감하게 알려 드릴까요?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sensitivity.entries.forEach { s ->
                Choice(s.label, state.sensitivity == s) { c.setSensitivity(s) }
            }
        }
        Text("민감할수록 작은 변화도 '급감속'으로 봐요.", fontSize = 15.sp, color = Color.Gray)

        Text("주행 중 음성 알림", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AlertLevel.entries.forEach { a ->
                Choice(a.label, state.alertLevel == a) { c.setAlertLevel(a) }
            }
        }
        Text(
            when (state.alertLevel) {
                AlertLevel.OFTEN -> "평소보다 센 감속이면 매번 알려 드려요."
                AlertLevel.NORMAL -> "평소보다 확실히 센 감속일 때만 알려 드려요."
                AlertLevel.RARE -> "평소보다 훨씬 센 감속일 때만 가끔 알려 드려요."
                AlertLevel.OFF -> "말하지 않고 기록만 해요. 주행 후 요약에서 확인하세요."
            },
            fontSize = 15.sp, color = Color.Gray,
        )

        HorizontalDivider()
        OutlinedButton(onClick = { confirmReset = true }) { Text("내 기준 다시 배우기") }
        OutlinedButton(onClick = { confirmClear = true }) { Text("기록 모두 지우기") }
        OutlinedButton(onClick = { c.endTripNow(); c.deselectProfile() }) { Text("운전자 바꾸기") }
    }

    if (confirmReset) ConfirmDialog("내 기준을 처음부터 다시 배울까요?", "지금까지 배운 운전 습관이 초기화돼요.",
        onYes = { c.resetLearning(); confirmReset = false }, onNo = { confirmReset = false })
    if (confirmClear) ConfirmDialog("기록을 모두 지울까요?", "지운 기록은 되돌릴 수 없어요.",
        onYes = { c.clearEvents(); confirmClear = false }, onNo = { confirmClear = false })
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = Modifier.height(56.dp)) { Text(label, fontSize = 18.sp) }
    else OutlinedButton(onClick = onClick, modifier = Modifier.height(56.dp)) { Text(label, fontSize = 18.sp) }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onYes) { Text("네") } },
        dismissButton = { TextButton(onClick = onNo) { Text("아니요") } },
    )
}
