// Copyright 2015-present 650 Industries. All rights reserved.
package expo.modules.sensors.modules

import android.Manifest
import android.hardware.Sensor
import android.os.Build
import android.os.Bundle
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.sensors.UseSensorProxy
import expo.modules.sensors.createSensorProxy
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

private const val EventName = "Exponent.pedometerUpdate"

class NotSupportedException(message: String) : CodedException(message)

class PedometerModule : Module() {
  private var stepsAtTheBeginning: Int? = null

  private val localRecordingClient by lazy { FitnessLocal.getLocalRecordingClient(appContext.reactContext ?: throw Exceptions.ReactContextLost()) }

  private val sensorProxy by lazy {
    createSensorProxy(EventName, Sensor.TYPE_STEP_COUNTER, appContext) { sensorEvent ->
      if (stepsAtTheBeginning == null) {
        stepsAtTheBeginning = sensorEvent.values[0].toInt() - 1
      }
      Bundle().apply {
        putDouble("steps", (sensorEvent.values[0] - (stepsAtTheBeginning ?: (sensorEvent.values[0].toInt() - 1))).toDouble())
      }
    }
  }

  override fun definition() = ModuleDefinition {
    Name("ExponentPedometer")

    UseSensorProxy(
      this@PedometerModule,
      Sensor.TYPE_STEP_COUNTER,
      EventName,
      listenerDecorator = { stepsAtTheBeginning = null }
    ) { sensorProxy }

    AsyncFunction("getPermissionsAsync") { promise: Promise ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Permissions.getPermissionsWithPermissionsManager(appContext.permissions, promise, Manifest.permission.ACTIVITY_RECOGNITION)
      } else {
        // Permissions don't need to be requested on Android versions below Q
        Permissions.getPermissionsWithPermissionsManager(appContext.permissions, promise)
      }
    }

    AsyncFunction("requestPermissionsAsync") { promise: Promise ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Permissions.askForPermissionsWithPermissionsManager(appContext.permissions, promise, Manifest.permission.ACTIVITY_RECOGNITION)
      } else {
        // Permissions don't need to be requested on Android versions below Q
        Permissions.askForPermissionsWithPermissionsManager(appContext.permissions, promise)
      }
    }

    AsyncFunction("getStepCountAsync") { from: Long, to: Long, promise: Promise ->
      // Subscribe to step count if not already subscribed
      localRecordingClient.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA)
        .addOnSuccessListener {
          // After subscribing, read the step count for the last 24 hours
          val readRequest = LocalDataReadRequest.Builder()
            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(1, TimeUnit.DAYS)
            .setTimeRange(from, to, TimeUnit.MILLISECONDS)
            .build()
          localRecordingClient.readData(readRequest)
            .addOnSuccessListener { response ->
              // Aggregate all steps in the returned buckets
              var totalSteps = 0
              response.buckets.forEach { bucket ->
                bucket.dataSets.forEach { dataSet ->
                  dataSet.dataPoints.forEach { dataPoint ->
                    val steps = dataPoint.getValue(LocalField.FIELD_STEPS).asInt()
                    totalSteps += steps
                  }
                }
              }
              val bundle = Bundle().apply {
                putDouble("steps", totalSteps.toDouble())
              }
              promise.resolve(bundle)
            }
            .addOnFailureListener { e ->
              promise.reject("ERR_READ_STEPS", "Failed to read step count", e)
            }
        }
        .addOnFailureListener { e ->
          promise.reject("ERR_SUBSCRIBE_STEPS", "Failed to subscribe to step count", e)
        }
    }
  }
}
