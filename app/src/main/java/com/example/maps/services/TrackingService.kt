package com.example.maps.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.example.maps.R
import com.example.maps.other.ACTION_PAUSE_SERVICE
import com.example.maps.other.ACTION_START_OR_RESUME_SERVICE
import com.example.maps.other.ACTION_STOP_SERVICE
import com.example.maps.other.FASTEST_LOCATION_INTERVAL
import com.example.maps.other.LOCATION_UPDATE_INTERVAL
import com.example.maps.other.NOTIFICATION_CHANNEL_ID
import com.example.maps.other.NOTIFICATION_CHANNEL_NAME
import com.example.maps.other.NOTIFICATION_ID
import com.example.maps.other.TIMER_UPDATE_INTERVAL
import com.example.maps.other.TrackingUtility
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private const val MILLISECONDS_IN_SECOND = 1000L

/*"Polyline" представляет список точек (LatLng) для одной линии на карте,
а "Polylines" представляет список линий (Polyline) для отображения маршрута.*/

typealias Polyline = MutableList<LatLng>
typealias Polylines = MutableList<Polyline>

@AndroidEntryPoint
class TrackingService : LifecycleService() {

    var isFirstRun = true
    var serviceKilled = false

    @Inject
    lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private val timeRunInSeconds = MutableLiveData<Long>()

    @Inject
    lateinit var baseNotificationBuilder: NotificationCompat.Builder

    lateinit var curNotificationBuilder: NotificationCompat.Builder

    companion object {
        /* "isTracking" и "pathPoints": Это статические переменные класса, которые представляют состояние отслеживания
         местоположения ("isTracking") и список точек маршрута ("pathPoints").
         Они обернуты в "MutableLiveData", что позволяет уведомлять наблюдателей об изменениях значений.*/

        val timeRunInMillis = MutableLiveData<Long>()
        val isTracking = MutableLiveData<Boolean>()
        val pathPoints = MutableLiveData<Polylines>()
    }

    /* Приватная функция, которая устанавливает начальные значения
     для "isTracking" (false) и "pathPoints" (пустой список)*/

    private fun postInitialValues() {
        isTracking.postValue(false)
        pathPoints.postValue(mutableListOf())
        timeRunInSeconds.postValue(0L)
        timeRunInMillis.postValue(0L)
    }

    override fun onCreate() {
        super.onCreate()
        curNotificationBuilder = baseNotificationBuilder
        postInitialValues()
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        isTracking.observe(this) {
            updateLocationTracking(it)
            updateNotificationTrackingState(it)
        }
    }

    private fun killService() {
        serviceKilled = true
        isFirstRun = true
        pauseService()
        postInitialValues()
        stopForeground(true)
        stopSelf()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START_OR_RESUME_SERVICE -> {
                    if (isFirstRun) {
                        startForegroundService()
                        isFirstRun = false
                    } else {
                        Timber.d(this.getString(R.string.resuming_service))
                        startTimer()
                    }
                }

                ACTION_PAUSE_SERVICE -> {
                    Timber.d(this.getString(R.string.paused_service))
                    pauseService()
                }

                ACTION_STOP_SERVICE -> {
                    Timber.d(this.getString(R.string.stopped_service))
                    killService()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private var isTimerEnabled = false
    private var lapTime = 0L
    private var timeRun = 0L
    private var timeStarted = 0L
    private var lastSecondTimestamp = 0L

    private fun startTimer() {
        addEmptyPolyline()
        isTracking.postValue(true)
        timeStarted = System.currentTimeMillis()
        isTimerEnabled = true
        CoroutineScope(Dispatchers.Main).launch {
            while (isTracking.value!!) {
                lapTime = System.currentTimeMillis() - timeStarted
                timeRunInMillis.postValue(timeRun + lapTime)
                if (timeRunInMillis.value!! >= lastSecondTimestamp + MILLISECONDS_IN_SECOND) {
                    timeRunInSeconds.postValue(timeRunInSeconds.value!! + 1)
                    lastSecondTimestamp += MILLISECONDS_IN_SECOND
                }
                delay(TIMER_UPDATE_INTERVAL)
            }
            timeRun += lapTime
        }
    }

    private fun pauseService() {
        isTracking.postValue(false)
        isTimerEnabled = false
    }

    private fun updateNotificationTrackingState(isTracking: Boolean) {
        val notificationActionText =
            if (isTracking) this.getString(R.string.notification_action_text_pause) else this.getString(R.string.notification_action_text_resume)
        val pendingIntent = if (isTracking) {
            val pauseIntent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_PAUSE_SERVICE
            }
            PendingIntent.getService(this, 1, pauseIntent, FLAG_UPDATE_CURRENT)
        } else {
            val resumeIntent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_START_OR_RESUME_SERVICE
            }
            PendingIntent.getService(this, 2, resumeIntent, FLAG_UPDATE_CURRENT)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        curNotificationBuilder.javaClass.getDeclaredField("mActions").apply { // What is it javaClass.getDeclaredField?
            isAccessible = true
            set(curNotificationBuilder, ArrayList<NotificationCompat.Action>())
            if (!serviceKilled) {
                curNotificationBuilder = baseNotificationBuilder
                    .addAction(R.drawable.ic_run_circle, notificationActionText, pendingIntent)
                notificationManager.notify(NOTIFICATION_ID, curNotificationBuilder.build())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationTracking(isTracking: Boolean) {
        if (isTracking) {
            if (TrackingUtility.hasLocationPermission(this)) {
                val request = com.google.android.gms.location.LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL)
                    .setMinUpdateIntervalMillis(FASTEST_LOCATION_INTERVAL)
                    .build()
                fusedLocationProviderClient.requestLocationUpdates(
                    request,
                    locationCallBack,
                    Looper.getMainLooper()
                )
            }
        } else {
            fusedLocationProviderClient.removeLocationUpdates(locationCallBack)
        }
    }

    val locationCallBack = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            if (isTracking.value!!) {
                result?.locations?.let { locations ->
                    for (location in locations) {
                        addPathPoint(location)
                        Timber.d("NEW_LOCATION: ${location.latitude}, ${location.longitude}")
                    }
                }
            }
        }
    }

    private fun addPathPoint(location: Location?) {
        location?.let {
            val position = LatLng(location.latitude, location.longitude)
            pathPoints.value?.apply {
                last().add(position)
                pathPoints.postValue(this)
            }
        }
    }

    private fun addEmptyPolyline() {
        pathPoints.value?.apply {
            add(mutableListOf())
            pathPoints.postValue(this)
        } ?: pathPoints.postValue(mutableListOf(mutableListOf()))
    }

    private fun startForegroundService() {
        startTimer()
        isTracking.postValue(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)

        startForeground(NOTIFICATION_ID, baseNotificationBuilder.build())

        timeRunInSeconds.observe(this) {
            if (!serviceKilled) {
                val notification = curNotificationBuilder
                    .setContentText(TrackingUtility.getFormattedStopWatchTime(it * MILLISECONDS_IN_SECOND))
                notificationManager.notify(NOTIFICATION_ID, notification.build())
            }
        }
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}
