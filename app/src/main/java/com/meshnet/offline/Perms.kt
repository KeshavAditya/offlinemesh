package com.meshnet.offline

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

/** Runtime permissions and radio state the mesh needs before MeshService may start (plan 8, "Permissions"). */
object Perms {
    /** Bluetooth runtime permissions for this API level; without them the service must not start. */
    val bluetooth: List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Everything to ask for; notifications are optional (the foreground service runs without them). */
    val toRequest: List<String> = bluetooth + (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())

    fun missing(ctx: Context, perms: List<String> = toRequest) = perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(ctx: Context) = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.isEnabled == true

    /** API 29-30 scans return nothing while Location is off. */
    fun locationOk(ctx: Context) = Build.VERSION.SDK_INT >= 31 || (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled

    /** Null when ready to start the mesh, else what the user must do. */
    fun blocker(ctx: Context): String? = when {
        missing(ctx, bluetooth).isNotEmpty() -> "Allow Nearby devices (Bluetooth) for OfflineMesh in Settings > Apps."
        !bluetoothOn(ctx) -> "Turn on Bluetooth."
        !locationOk(ctx) -> "Turn on Location (needed for Bluetooth scanning on this Android version)."
        else -> null
    }
}
