package com.chaewon.safedrive.data

import android.content.Context
import com.chaewon.safedrive.app.ProfileData
import com.chaewon.safedrive.app.ProfileStore
import com.chaewon.safedrive.core.AlertLevel
import com.chaewon.safedrive.core.DriveEvent
import com.chaewon.safedrive.core.EventType
import com.chaewon.safedrive.core.Sensitivity
import com.chaewon.safedrive.core.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 기기 내부 저장소(앱 전용 폴더)에 JSON 파일로 저장. 외부 전송 없음.
 *  profiles.json               : 프로필 목록 + 학습 상태 + 주행 기록
 *  events_<profileId>.json     : 이벤트 기록
 */
class JsonFileStore(context: Context) : ProfileStore {
    private val dir: File = context.filesDir
    private val profilesFile = File(dir, "profiles.json")

    @Synchronized
    override fun loadProfiles(): List<ProfileData> {
        if (!profilesFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(profilesFile.readText())
            (0 until arr.length()).map { profileFrom(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun saveProfile(p: ProfileData) {
        val list = loadProfiles().filter { it.id != p.id } + p
        writeProfiles(list)
    }

    @Synchronized
    override fun deleteProfile(id: String) {
        writeProfiles(loadProfiles().filter { it.id != id })
        eventsFile(id).delete()
    }

    @Synchronized
    override fun loadEvents(profileId: String): List<DriveEvent> {
        val f = eventsFile(profileId)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { eventFrom(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun saveEvents(profileId: String, events: List<DriveEvent>) {
        val arr = JSONArray()
        events.forEach { arr.put(eventTo(it)) }
        atomicWrite(eventsFile(profileId), arr.toString())
    }

    // ---------------- 직렬화 ----------------

    private fun writeProfiles(list: List<ProfileData>) {
        val arr = JSONArray()
        list.forEach { arr.put(profileTo(it)) }
        atomicWrite(profilesFile, arr.toString())
    }

    private fun eventsFile(id: String) = File(dir, "events_$id.json")

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    private fun profileTo(p: ProfileData) = JSONObject().apply {
        put("id", p.id); put("name", p.name)
        put("sensitivity", p.sensitivity.name); put("alertLevel", p.alertLevel.name)
        put("peaks", JSONArray().apply { p.peaks.forEach { put(it) } })
        if (p.baselineMps2 != null) put("baseline", p.baselineMps2)
        put("trips", JSONArray().apply { p.trips.forEach { put(tripTo(it)) } })
    }

    private fun profileFrom(o: JSONObject) = ProfileData(
        id = o.getString("id"),
        name = o.getString("name"),
        sensitivity = runCatching { Sensitivity.valueOf(o.getString("sensitivity")) }.getOrDefault(Sensitivity.NORMAL),
        alertLevel = runCatching { AlertLevel.valueOf(o.getString("alertLevel")) }.getOrDefault(AlertLevel.NORMAL),
        peaks = o.optJSONArray("peaks")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList(),
        baselineMps2 = if (o.has("baseline")) o.getDouble("baseline") else null,
        trips = o.optJSONArray("trips")?.let { a -> (0 until a.length()).map { tripFrom(a.getJSONObject(it)) } } ?: emptyList(),
    )

    private fun tripTo(t: Trip) = JSONObject().apply {
        put("id", t.id); put("start", t.startMillis); put("end", t.endMillis); put("dist", t.distanceM)
        put("my", t.myHardDecelCount); put("car", t.carDecelCount)
        put("peaks", JSONArray().apply { t.myEpisodePeaks.forEach { put(it) } })
    }

    private fun tripFrom(o: JSONObject) = Trip(
        id = o.getLong("id"), startMillis = o.getLong("start"), endMillis = o.getLong("end"),
        distanceM = o.getDouble("dist"), myHardDecelCount = o.getInt("my"), carDecelCount = o.getInt("car"),
        myEpisodePeaks = o.optJSONArray("peaks")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList(),
    )

    private fun eventTo(e: DriveEvent) = JSONObject().apply {
        put("id", e.id); put("type", e.type.name); put("t", e.timestampMillis)
        e.latitude?.let { put("lat", it) }; e.longitude?.let { put("lon", it) }
        e.speedMps?.let { put("v", it) }; e.decelMps2?.let { put("a", it) }; e.thresholdMps2?.let { put("th", it) }
        e.brakePressed?.let { put("brake", it) }; e.cruiseOn?.let { put("cruise", it) }
        e.cruiseTargetMps?.let { put("target", it.toDouble()) }
        e.note?.let { put("note", it) }; e.tripId?.let { put("trip", it) }
    }

    private fun eventFrom(o: JSONObject) = DriveEvent(
        type = runCatching { EventType.valueOf(o.getString("type")) }.getOrDefault(EventType.MANUAL),
        timestampMillis = o.getLong("t"),
        latitude = o.optDoubleOrNull("lat"), longitude = o.optDoubleOrNull("lon"),
        speedMps = o.optDoubleOrNull("v"), decelMps2 = o.optDoubleOrNull("a"), thresholdMps2 = o.optDoubleOrNull("th"),
        brakePressed = if (o.has("brake")) o.getBoolean("brake") else null,
        cruiseOn = if (o.has("cruise")) o.getBoolean("cruise") else null,
        cruiseTargetMps = o.optDoubleOrNull("target")?.toFloat(),
        note = if (o.has("note")) o.getString("note") else null,
        tripId = if (o.has("trip")) o.getLong("trip") else null,
        id = o.optLong("id", o.getLong("t")),
    )

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k)) getDouble(k) else null
}
