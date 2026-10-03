package com.dicar.vehicle

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.dicar.vehicle.service.VehicleMonitorService
import com.dicar.vehicle.ui.MainViewModel
import com.dicar.vehicle.ui.screen.DashboardScreen
import com.dicar.vehicle.ui.theme.DiCarTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论是否授权都启动服务：没有通知权限只是看不到通知，采集照常
            VehicleMonitorService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 仪表类应用：前台时保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            VehicleMonitorService.start(this)
        }

        setContent {
            DiCarTheme {
                DashboardScreen(viewModel)
            }
        }
    }
}
