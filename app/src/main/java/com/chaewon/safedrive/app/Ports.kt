package com.chaewon.safedrive.app

import com.chaewon.safedrive.core.AlertLevel
import com.chaewon.safedrive.core.DriveEvent
import com.chaewon.safedrive.core.Sensitivity
import com.chaewon.safedrive.core.Trip

/** 프로필 1명분 저장 데이터 */
data class ProfileData(
    val id: String,
    val name: String,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val alertLevel: AlertLevel = AlertLevel.NORMAL,
    val peaks: List<Double> = emptyList(),
    val baselineMps2: Double? = null,
    val trips: List<Trip> = emptyList(),
)

/** 저장소. 안드로이드에서는 JsonFileStore, 테스트에서는 InMemoryStore. */
interface ProfileStore {
    fun loadProfiles(): List<ProfileData>
    fun saveProfile(p: ProfileData)
    fun deleteProfile(id: String)
    fun loadEvents(profileId: String): List<DriveEvent>
    fun saveEvents(profileId: String, events: List<DriveEvent>)
}

/** 음성 출력. Pleos TextToSpeech SDK 어댑터 또는 안드로이드 기본 TTS. */
fun interface VoiceOutput { fun speak(text: String) }

class InMemoryStore : ProfileStore {
    private val profiles = linkedMapOf<String, ProfileData>()
    private val events = mutableMapOf<String, List<DriveEvent>>()
    override fun loadProfiles() = profiles.values.toList()
    override fun saveProfile(p: ProfileData) { profiles[p.id] = p }
    override fun deleteProfile(id: String) { profiles.remove(id); events.remove(id) }
    override fun loadEvents(profileId: String) = events[profileId] ?: emptyList()
    override fun saveEvents(profileId: String, events: List<DriveEvent>) { this.events[profileId] = events }
}
