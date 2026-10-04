package com.dicar.vehicle

import android.app.Application
import android.content.Context
import com.dicar.vehicle.data.SettingsStore
import com.dicar.vehicle.data.VehicleRepository
import com.dicar.vehicle.data.source.bydauto.BydAutoDataSource
import com.dicar.vehicle.data.source.bydauto.BydAutoProbe
import com.dicar.vehicle.data.source.bydauto.adb.AdbTransport
import com.dicar.vehicle.data.source.diplus.DiPlusDataSource
import com.dicar.vehicle.data.source.mock.MockDataSource

class VehicleApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 手动依赖注入：App 很小，不引入 Hilt/Koin。 */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    private val adbTransport = AdbTransport(context)
    private val bydAuto = BydAutoDataSource(context, adbTransport)
    val repository = VehicleRepository(
        settings = settings,
        sources = listOf(bydAuto, DiPlusDataSource(), MockDataSource()),
    )
    val probe = BydAutoProbe(context) { bydAuto.acquireAccess() }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as VehicleApp).container
