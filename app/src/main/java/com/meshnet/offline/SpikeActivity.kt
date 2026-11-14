package com.meshnet.offline

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Throwaway: `adb shell am start -n com.meshnet.offline/.SpikeActivity` after granting permissions. Output in logcat. */
class SpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "BLE spike: see logcat -s OfflineMesh" })
        BleSpike(this).start()
    }
}
