package app.stride.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** High-accuracy fused location updates while the camera is open. */
class LocationTracker(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val newest = result.lastLocation ?: return
            val current = _location.value
            if (current == null || newest.elapsedRealtimeNanos >= current.elapsedRealtimeNanos) {
                _location.value = newest
            }
        }
    }

    /** Caller must hold ACCESS_FINE_LOCATION. */
    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { last ->
            if (last != null && _location.value == null) _location.value = last
        }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    companion object {
        fun ageMillis(location: Location): Long =
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
    }
}
