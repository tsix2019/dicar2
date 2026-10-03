package com.dicar.vehicle

import android.app.Application
import android.content.Context
import com.dicar.vehicle.data.SettingsStore
import com.dicar.vehicle.data.VehicleRepository
import com.dicar.vehicle.data.source.bydauto.BydAutoDataSource
import com.dicar.vehicle.data.source.bydauto.BydAutoProbe
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
    val repository = VehicleRepository(
        settings = settings,
        sources = listOf(
            BydAutoDataSource(context),
            DiPlusDataSource(),
            MockDataSource(),
        ),
    )
    val probe = BydAutoProbe(context)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as VehicleApp).container
