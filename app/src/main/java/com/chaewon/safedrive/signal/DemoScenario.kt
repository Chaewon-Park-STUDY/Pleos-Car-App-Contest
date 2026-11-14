package com.chaewon.safedrive.signal

import com.chaewon.safedrive.core.LocationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 데모 모드: 실제 차량/에뮬레이터 신호 대신, 같은 SignalListener 로 가상의 주행을 재생한다.
 *  - seedHistory(): 과거 '평소 주행' 여러 번을 즉시 재생 → 내 기준 학습 + 비교용 주행 기록 생성
 *  - runDemoDrive(): 시연 영상용 주행을 실시간에 가깝게 재생 (단계별 안내 문구 콜백)
 *
 * 1 시뮬레이션 초 = 위치 1개 (1Hz). 북쪽으로 직진.
 */
class DemoScenario(private val random: Random = Random(7)) {

    private class Sim(val l: SignalListener, var t: Long) {
        var lat = 37.5665
        val lon = 126.9780
        var v = 0.0
        var brake = false
        var cruise = false

        fun tick() {
            l.onBrake(brake, t)
            l.onLocation(LocationSample(lat, lon, 1f, t))
            lat += v / 111_195.0
            t += 1000
        }
    }

    // ---------- 과거 주행 (즉시) ----------

    /** 과거 '평소 주행' trips 회를 재생. 부드러운 제동(약 1.8~2.8 m/s²)만 포함. */
    fun seedHistory(l: SignalListener, now: Long, trips: Int = 5, episodesPerTrip: Int = 8) {
        repeat(trips) { k ->
            val sim = Sim(l, now - (trips - k) * 24L * 3_600_000L)   // 하루에 한 번씩, 과거부터
            accelTo(sim, 15.0, 2.0)
            repeat(episodesPerTrip) {
                hold(sim, 6 + random.nextInt(6))
                val a = 1.8 + random.nextDouble() * 1.0
                sim.brake = true; decelBy(sim, a, 3); sim.brake = false
                accelTo(sim, 15.0, 2.0)
            }
            hold(sim, 10)
            sim.brake = true; decelTo(sim, 0.0, 2.0); sim.brake = false
            hold(sim, 75)   // 60초 이상 정차 → 주행 종료 (속도 smoothing 여유 포함)
        }
    }

    // ---------- 시연 주행 (실시간) ----------

    fun runDemoDrive(
        scope: CoroutineScope,
        l: SignalListener,
        now: () -> Long,
        msPerSimSecond: Long = 300,
        onStep: (String) -> Unit,
        onDone: () -> Unit,
    ): Job = scope.launch {
        val sim = Sim(l, now())
        suspend fun tick() { sim.tick(); delay(msPerSimSecond) }
        suspend fun hold(n: Int) = repeat(n) { tick() }
        suspend fun ramp(target: Double, perSec: Double) {
            while (sim.v != target) {
                sim.v = if (sim.v < target) minOf(target, sim.v + perSec) else maxOf(target, sim.v - perSec)
                tick()
            }
        }

        onStep("출발 · 시속 60km")
        ramp(16.7, 2.0); hold(6)

        onStep("① 앞차가 끼어들어 브레이크를 세게 밟음")
        sim.brake = true; ramp(4.7, 6.0); sim.brake = false
        hold(2); ramp(16.7, 2.0); hold(4)

        onStep("② 크루즈 켬 · 목표 시속 80km")
        sim.cruise = true
        l.onCruiseState(true, sim.t); l.onCruiseTarget(22.2f, sim.t)
        ramp(22.2, 1.5); hold(5)

        onStep("③ 크루즈 중, 브레이크 없이 차가 스스로 급감속")
        ramp(12.2, 5.0); hold(2); ramp(22.2, 1.5); hold(3)
        sim.cruise = false
        l.onCruiseState(false, sim.t)

        onStep("④ 음성: \"방금 저장해, 흰 SUV가 깜빡이 없이 끼어들었어\"")
        l.onVoiceCommand("방금 저장해, 흰 SUV가 깜빡이 없이 끼어들었어", sim.t)
        hold(4)

        onStep("⑤ 도착 · 정차")
        sim.brake = true; ramp(0.0, 2.0); sim.brake = false
        repeat(75) { sim.tick(); delay(15) }   // 정차 대기는 빠르게
        onStep("주행 종료 · 요약 확인")
        onDone()
    }

    // ---------- 즉시 재생용 헬퍼 ----------

    private fun hold(s: Sim, n: Int) = repeat(n) { s.tick() }
    private fun accelTo(s: Sim, target: Double, perSec: Double) {
        while (s.v < target) { s.v = minOf(target, s.v + perSec); s.tick() }
    }
    private fun decelTo(s: Sim, target: Double, perSec: Double) {
        while (s.v > target) { s.v = maxOf(target, s.v - perSec); s.tick() }
    }
    private fun decelBy(s: Sim, perSec: Double, seconds: Int) {
        repeat(seconds) { s.v = maxOf(0.0, s.v - perSec); s.tick() }
    }
}
