package com.chaewon.safedrive

import org.junit.Assert.assertEquals
import org.junit.Test
import com.chaewon.safedrive.app.*
import com.chaewon.safedrive.core.*
import com.chaewon.safedrive.signal.DemoScenario
import kotlinx.coroutines.runBlocking

var failures = 0
fun check(name: String, cond: Boolean, detail: String = "") {
    println((if (cond) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!cond) failures++
}

fun sim(det: EventDetector, est: SpeedEstimator, speeds: List<Double>, brakeAt: Set<Int> = emptySet(), t0: Long = 0) {
    var lat = 37.5; var t = t0
    speeds.forEachIndexed { i, v ->
        det.onBrake(i in brakeAt, t)
        est.feed(LocationSample(lat, 127.0, 1f, t))?.let { det.onSpeed(it) }
        lat += v / 111_195.0; t += 1000
    }
}
fun flat(v: Double, n: Int) = List(n) { v }
fun ramp(from: Double, a: Double, n: Int) = List(n) { (from - a * (it + 1)).coerceAtLeast(0.0) }

fun runAllChecks(): Int {
    failures = 0
    // ---- core: 이벤트 2종 분류 ----
    run {
        val ev = mutableListOf<DriveEvent>(); val th = PersonalThreshold()
        val det = EventDetector(th, { ev += it }); val est = SpeedEstimator()
        sim(det, est, flat(25.0, 10) + ramp(25.0, 6.0, 3) + flat(7.0, 10), brakeAt = (9..13).toSet())
        check("brake hard decel -> MY_HARD_DECEL", ev.singleOrNull()?.type == EventType.MY_HARD_DECEL, ev.map { it.type }.toString())
        check("flagged episode not learned", th.sampleCount == 0, "n=${th.sampleCount}")
    }
    run {
        val ev = mutableListOf<DriveEvent>(); val th = PersonalThreshold()
        val det = EventDetector(th, { ev += it }); val est = SpeedEstimator()
        sim(det, est, flat(25.0, 10) + ramp(25.0, 6.0, 3) + flat(7.0, 10))
        check("no brake, no cruise (foot off) -> MY_HARD_DECEL", ev.singleOrNull()?.type == EventType.MY_HARD_DECEL)
    }
    run {
        val ev = mutableListOf<DriveEvent>(); val th = PersonalThreshold()
        val det = EventDetector(th, { ev += it }); val est = SpeedEstimator()
        det.onCruiseState(true)
        sim(det, est, flat(27.8, 10) + ramp(27.8, 6.0, 3) + flat(9.8, 10))
        check("cruise, no brake -> CAR_DECEL", ev.singleOrNull()?.type == EventType.CAR_DECEL)
        // 크루즈 중 부드러운 감속도 학습 제외
        val ev2 = mutableListOf<DriveEvent>(); val th2 = PersonalThreshold()
        val det2 = EventDetector(th2, { ev2 += it }); val est2 = SpeedEstimator()
        det2.onCruiseState(true)
        sim(det2, est2, flat(20.0, 8) + ramp(20.0, 1.5, 6) + flat(11.0, 8))
        check("car episode excluded from learning", th2.sampleCount == 0 && ev2.isEmpty())
    }
    run {
        val ev = mutableListOf<DriveEvent>(); val th = PersonalThreshold()
        val det = EventDetector(th, { ev += it }); val est = SpeedEstimator()
        det.onCruiseState(true)
        sim(det, est, flat(27.8, 10) + ramp(27.8, 6.0, 3) + flat(9.8, 10), brakeAt = (10..12).toSet())
        check("cruise but driver braked -> MY_HARD_DECEL", ev.singleOrNull()?.type == EventType.MY_HARD_DECEL)
    }

    // ---- core: '과거의 나' 기준은 천천히 이동 ----
    run {
        val th = PersonalThreshold()
        check("learning: default before", th.isLearning && th.currentThresholdMps2() == 3.5)
        repeat(30) { th.addEpisodePeak(2.0 + it / 29.0) }
        val b0 = th.baselineMps2!!
        check("baseline fixed at learning end", !th.isLearning && b0 in 2.9..3.0, "%.3f".format(b0))
        th.onTripEnd()
        repeat(10) { th.addEpisodePeak(4.0) }; th.onTripEnd()   // 거칠어진 주행 1회
        val b1 = th.baselineMps2!!
        check("baseline moves only 5% toward recent", b1 in b0 + 0.04..b0 + 0.06, "%.3f -> %.3f".format(b0, b1))
        val normal = th.currentThresholdMps2()
        th.sensitivity = Sensitivity.SENSITIVE
        val sens = th.currentThresholdMps2()
        th.sensitivity = Sensitivity.RELAXED
        check("sensitivity order: 민감 < 보통 < 둔감", sens < normal && normal < th.currentThresholdMps2())
        th.resetLearning()
        check("reset -> learning again", th.isLearning)
    }

    // ---- core: 알림 정책 ----
    run {
        val p = AlertPolicy(AlertLevel.NORMAL)
        fun e(d: Double, t: Long, type: EventType = EventType.MY_HARD_DECEL) = DriveEvent(type, t, decelMps2 = d, thresholdMps2 = 3.0)
        check("normal: below 1.2x silent", !p.shouldSpeak(e(3.3, 0)))
        check("normal: above 1.2x speaks", p.shouldSpeak(e(4.0, 1_000)))
        check("normal: cooldown", !p.shouldSpeak(e(5.0, 60_000)))
        check("normal: after cooldown", p.shouldSpeak(e(5.0, 400_000)))
        check("car decel never spoken", !p.shouldSpeak(e(9.0, 2_000_000, EventType.CAR_DECEL)))
        check("off: silent", !AlertPolicy(AlertLevel.OFF).shouldSpeak(e(9.0, 0)))
    }

    // ---- app: 데모 전체 흐름 ----
    run {
        val spoken = mutableListOf<String>()
        var clock = 1_700_000_000_000L
        val c = DriveController(InMemoryStore(), { spoken += it }, { clock })
        val id = c.createProfile("채원")
        c.selectProfile(id)
        check("profile active, learning", c.state.value.active?.name == "채원" && c.state.value.learning)

        DemoScenario().seedHistory(c, clock)
        val s1 = c.state.value
        check("seed: learning complete", !s1.learning, "progress=${s1.learningProgress}")
        println("   seed events=" + s1.events.map { "%.2f/%.2f".format(it.decelMps2, it.thresholdMps2) })
        check("seed: no events from gentle driving", s1.events.isEmpty(), "${s1.events.map { it.type }}")
        check("seed: summary exists", s1.lastSummary != null)

        spoken.clear()
        runBlocking {
            DemoScenario().runDemoDrive(this, c, { clock }, msPerSimSecond = 0, onStep = { println("   step: $it") }, onDone = {}).join()
        }
        val s2 = c.state.value
        val types = s2.events.map { it.type }
        println("   events=" + s2.events.map { "${it.type}(d=%.2f th=%.2f)".format(it.decelMps2 ?: 0.0, it.thresholdMps2 ?: 0.0) })
        println("   spoken=$spoken")
        check("demo: my hard decel recorded", EventType.MY_HARD_DECEL in types)
        check("demo: car decel recorded", types.count { it == EventType.CAR_DECEL } == 1)
        check("demo: manual bookmark with note", s2.events.any { it.type == EventType.MANUAL && it.note!!.contains("SUV") })
        check("demo: alert spoken", spoken.any { it.contains("센 감속") })
        val sum = s2.lastSummary!!
        println("   summary: count=${sum.count} cmp=${sum.countComparison} strength=${sum.strength} car=${sum.carDecelCount} recent=${sum.recentCounts}")
        check("demo: summary count = events in this trip", sum.count == s2.events.count { it.type == EventType.MY_HARD_DECEL && it.tripId == sum.trip.id })
        check("demo: compared to past me -> MORE", sum.countComparison == Comparison.MORE)
        check("demo: car decel in summary", sum.carDecelCount == 1)
        check("demo: trip-end speech", spoken.any { it.startsWith("주행이 끝났어요") })

        // 프로필 재선택 시 학습 유지
        c.selectProfile(id)
        check("persist: learning kept after reselect", !c.state.value.learning)
    }

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    return failures
}


/** Android Studio 에서 실행: app/src/test → 우클릭 → Run 'CoreLogicTest' */
class CoreLogicTest {
    @Test
    fun allScenarios() {
        assertEquals("실패한 검사 수", 0, runAllChecks())
    }
}
