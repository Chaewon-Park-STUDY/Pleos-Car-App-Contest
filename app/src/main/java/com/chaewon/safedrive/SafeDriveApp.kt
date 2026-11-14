package com.chaewon.safedrive

import android.app.Application
import kotlinx.coroutines.MainScope
import com.chaewon.safedrive.app.DriveController
import com.chaewon.safedrive.data.JsonFileStore
import com.chaewon.safedrive.voice.AndroidTtsVoice

/** 앱 전체에서 컨트롤러 1개를 공유 */
class SafeDriveApp : Application() {
    lateinit var controller: DriveController
        private set

    /** 화면이 다시 그려져도(회전 등) 데모 주행이 끊기지 않도록 앱 수준 scope 사용 */
    val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        controller = DriveController(
            store = JsonFileStore(this),
            voice = AndroidTtsVoice(this),
        )
    }
}
