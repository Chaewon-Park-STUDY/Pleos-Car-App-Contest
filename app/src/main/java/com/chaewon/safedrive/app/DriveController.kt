package com.chaewon.safedrive.app

import com.chaewon.safedrive.core.*
import com.chaewon.safedrive.signal.SignalAvailability
import com.chaewon.safedrive.signal.SignalListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ProfileInfo(val id: String, val name: String)

/** 화면이 구독하는 상태. 내부 계산 수치는 넣지 않는다(상세 화면용 이벤트 데이터 제외). */
data class UiState(
    val profiles: List<ProfileInfo> = emptyList(),
    val active: ProfileInfo? = null,
    val driving: Boolean = false,
    val speedKmh: Int = 0,
    val learning: Boolean = true,
    val learningProgress: Double = 0.0,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val alertLevel: AlertLevel = AlertLevel.NORMAL,
    val tripMyCount: Int = 0,
    val tripCarCount: Int = 0,
    val events: List<DriveEvent> = emptyList(),
    val lastSummary: TripSummary? = null,
    val lastSpoken: String? = null,
    val availability: SignalAvailability = SignalAvailability(),
    val demoRunning: Boolean = false,
)

/**
 * 앱의 중심. 신호를 받아 판단 로직에 넘기고, 결과를 저장·음성·화면 상태로 내보낸다.
 * 안드로이드 의존성 없음 (StateFlow 만 사용) → 단위 테스트 가능.
 *
 * 사람 / 자동 경계
 *  - 자동: 신호 감지, 속도 계산, 이벤트 기록, 학습, 주행 요약
 *  - 사람: 프로필 선택, 민감도·알림 빈도 설정, 기준 다시 잡기, 기록 삭제
 *  - 하지 않음: 차량 제어 (제어 퍼미션 미사용)
 */
class DriveController(
    private val store: ProfileStore,
    private val voice: VoiceOutput,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxEvents: Int = 500,
) : SignalListener {

    private val _state = MutableStateFlow(UiState(profiles = store.loadProfiles().map { ProfileInfo(it.id, it.name) }))
    val state: StateFlow<UiState> = _state

    private var profile: ProfileData? = null
    private var threshold = PersonalThreshold()
    private val alert = AlertPolicy()
    private var estimator = SpeedEstimator()
    private var detector = newDetector()
    private var tracker = newTracker()
    private val events = mutableListOf<DriveEvent>()   // 최신순
    private val trips = mutableListOf<Trip>()
    private var tripMy = 0
    private var tripCar = 0

    // ---------------- 프로필 (사람이 결정) ----------------

    fun createProfile(name: String): String {
        val id = "p" + clock()
        store.saveProfile(ProfileData(id = id, name = name.trim().ifEmpty { "운전자" }))
        refreshProfiles()
        return id
    }

    fun deleteProfile(id: String) {
        store.deleteProfile(id)
        if (profile?.id == id) { profile = null; _state.value = _state.value.copy(active = null) }
        refreshProfiles()
    }

    fun selectProfile(id: String) {
        val p = store.loadProfiles().firstOrNull { it.id == id } ?: return
        profile = p
        threshold = PersonalThreshold(sensitivity = p.sensitivity).also { it.restore(p.peaks, p.baselineMps2) }
        alert.level = p.alertLevel
        estimator = SpeedEstimator()
        detector = newDetector()
        tracker = newTracker()
        events.clear(); events += store.loadEvents(id).sortedByDescending { it.timestampMillis }
        trips.clear(); trips += p.trips
        tripMy = 0; tripCar = 0
        _state.value = _state.value.copy(
            active = ProfileInfo(p.id, p.name), driving = false, speedKmh = 0,
            lastSummary = trips.lastOrNull()?.let { TripSummarizer.summarize(it, trips.dropLast(1)) },
            lastSpoken = null,
        )
        publish()
    }

    /** '운전자 바꾸기' — 프로필 선택 화면으로 */
    fun deselectProfile() {
        persist()
        profile = null
        _state.value = UiState(profiles = store.loadProfiles().map { ProfileInfo(it.id, it.name) })
    }

    fun setSensitivity(s: Sensitivity) { threshold.sensitivity = s; persist(); publish() }
    fun setAlertLevel(a: AlertLevel) { alert.level = a; persist(); publish() }

    /** 사람이 직접 누르는 '기준 다시 잡기' */
    fun resetLearning() { threshold.resetLearning(); persist(); publish() }

    fun clearEvents() { events.clear(); persistEvents(); publish() }

    fun setAvailability(a: SignalAvailability) { _state.value = _state.value.copy(availability = a) }
    fun setDemoRunning(running: Boolean) { _state.value = _state.value.copy(demoRunning = running) }

    /** 데모·앱 종료 시 진행 중 주행을 마감 */
    fun endTripNow() = tracker.forceEnd(clock())

    // ---------------- 신호 (자동) ----------------

    override fun onLocation(sample: LocationSample) {
        if (profile == null) return
        val s = estimator.feed(sample) ?: return
        tracker.onSpeed(s)
        detector.tripId = tracker.activeTripId
        detector.onSpeed(s)
        _state.value = _state.value.copy(
            speedKmh = (s.speedMps * 3.6).toInt(),
            driving = tracker.activeTripId != null,
        )
    }

    override fun onBrake(pressed: Boolean, now: Long) = detector.onBrake(pressed, now)
    override fun onCruiseState(on: Boolean, now: Long) = detector.onCruiseState(on)
    override fun onCruiseTarget(mps: Float?, now: Long) = detector.onCruiseTarget(mps)
    override fun onAirbag(deployed: Boolean, seat: String, now: Long) = detector.onAirbagDeployed(deployed, now, seat)
    override fun onVehicleWarning(active: Boolean, what: String, now: Long) = detector.onVehicleWarning(active, now, what)

    override fun onVoiceCommand(text: String, now: Long) {
        if (profile == null) return
        if (text.contains("저장") || text.contains("기록")) {
            detector.manualBookmark(now, text)
            say("기록했어요.")
        }
    }

    // ---------------- 내부 ----------------

    private fun newDetector() = EventDetector(
        threshold = threshold,
        onEvent = ::handleEvent,
        onEpisodeEnd = { tracker.onEpisode(it) },
    )

    private fun newTracker() = TripTracker(
        onTripStart = { tripMy = 0; tripCar = 0; publish() },
        onTripEnd = ::handleTripEnd,
    )

    private fun handleEvent(e: DriveEvent) {
        events.add(0, e)
        while (events.size > maxEvents) events.removeAt(events.lastIndex)
        tracker.onEvent(e)
        when (e.type) {
            EventType.MY_HARD_DECEL -> tripMy++
            EventType.CAR_DECEL -> tripCar++
            else -> {}
        }
        if (alert.shouldSpeak(e)) say("평소보다 센 감속이에요. 여유 있게 가요.")
        if (e.type == EventType.CRASH) say("괜찮으세요? 기록을 남겼어요.")
        persistEvents(); persist(); publish()
    }

    private fun handleTripEnd(t: Trip) {
        threshold.onTripEnd()
        val summary = TripSummarizer.summarize(t, trips)
        trips += t
        while (trips.size > 60) trips.removeAt(0)
        val msg = buildString {
            append("주행이 끝났어요. 오늘 내 급감속은 ${summary.count}회")
            if (summary.countComparison != Comparison.UNKNOWN) append(", ${summary.countComparison.label}")
            append(".")
        }
        _state.value = _state.value.copy(lastSummary = summary, driving = false, speedKmh = 0)
        say(msg)
        persist(); publish()
    }

    private fun say(text: String) {
        voice.speak(text)
        _state.value = _state.value.copy(lastSpoken = text)
    }

    private fun persist() {
        val p = profile ?: return
        val updated = p.copy(
            sensitivity = threshold.sensitivity, alertLevel = alert.level,
            peaks = threshold.history(), baselineMps2 = threshold.baselineMps2, trips = trips.toList(),
        )
        profile = updated
        store.saveProfile(updated)
    }

    private fun persistEvents() { profile?.let { store.saveEvents(it.id, events.toList()) } }

    private fun refreshProfiles() {
        _state.value = _state.value.copy(profiles = store.loadProfiles().map { ProfileInfo(it.id, it.name) })
    }

    private fun publish() {
        _state.value = _state.value.copy(
            learning = threshold.isLearning,
            learningProgress = threshold.learningProgress,
            sensitivity = threshold.sensitivity,
            alertLevel = alert.level,
            tripMyCount = tripMy,
            tripCarCount = tripCar,
            events = events.toList(),
        )
    }
}
