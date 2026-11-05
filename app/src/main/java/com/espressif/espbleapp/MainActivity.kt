package com.espressif.espbleapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.espressif.espbleapp.ui.EslAppRoot
import com.espressif.espbleapp.ui.FluxAppRoot
import com.espressif.espbleapp.ui.GmpFileTransferRoot
import com.espressif.espbleapp.ui.GmpOtaRoot
import com.espressif.espbleapp.ui.LauncherScreen
import com.espressif.espbleapp.ui.theme.EspBleAppTheme
import com.espressif.espbleapp.viewmodel.MainViewModel

enum class AppSelection {
    LAUNCHER,
    ESL,
    FLUX,
    FILE_TRANSFER,
    GMP_OTA,
}

class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureBlePermissions()
        enableEdgeToEdge()
        setContent {
            EspBleAppContent()
        }
    }

    @Composable
    private fun EspBleAppContent() {
        EspBleAppTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                var selectedApp by rememberSaveable { mutableStateOf(AppSelection.LAUNCHER) }

                if (selectedApp != AppSelection.LAUNCHER) {
                    BackHandler {
                        selectedApp = AppSelection.LAUNCHER
                    }
                }

                when (selectedApp) {
                    AppSelection.LAUNCHER -> {
                        LauncherScreen(
                            onSelectEsl = { selectedApp = AppSelection.ESL },
                            onSelectFlux = { selectedApp = AppSelection.FLUX },
                            onSelectFileTransfer = { selectedApp = AppSelection.FILE_TRANSFER },
                            onSelectOta = { selectedApp = AppSelection.GMP_OTA },
                        )
                    }

                    AppSelection.ESL -> {
                        EslAppRoot(
                            vm = vm,
                            onBackToLauncher = { selectedApp = AppSelection.LAUNCHER },
                        )
                    }

                    AppSelection.FLUX -> {
                        FluxAppRoot(
                            onBackToLauncher = { selectedApp = AppSelection.LAUNCHER },
                        )
                    }

                    AppSelection.FILE_TRANSFER -> {
                        GmpFileTransferRoot(
                            onBackToLauncher = { selectedApp = AppSelection.LAUNCHER },
                        )
                    }

                    AppSelection.GMP_OTA -> {
                        GmpOtaRoot(
                            onBackToLauncher = { selectedApp = AppSelection.LAUNCHER },
                        )
                    }
                }
            }
        }
    }

    private fun ensureBlePermissions() {
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
