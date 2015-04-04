/*
 * libjingle
 * Copyright 2014 Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.alexmaslakov.webrtc_android_client

import com.alexmaslakov.webrtc_android_client.util.AppRTCUtils
import com.alexmaslakov.webrtc_android_client.util.AppRTCUtils.NonThreadSafe

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.alexmaslakov.webrtc_android_client.util

/**
 * AppRTCProximitySensor manages functions related to the proximity sensor in
 * the AppRTC demo.
 * On most device, the proximity sensor is implemented as a boolean-sensor.
 * It returns just two values "NEAR" or "FAR". Thresholding is done on the LUX
 * value i.e. the LUX value of the light sensor is compared with a threshold.
 * A LUX-value more than the threshold means the proximity sensor returns "FAR".
 * Anything less than the threshold value and the sensor  returns "NEAR".
 */
public class AppRTCProximitySensor private(
  context: Context,
  private val onSensorStateListener: Runnable?
) : SensorEventListener {

  // This class should be created, started and stopped on one thread
  // (e.g. the main thread). We use |nonThreadSafe| to ensure that this is
  // the case. Only active when |DEBUG| is set to true.
  private val nonThreadSafe = util.AppRTCUtils.NonThreadSafe()
  private val sensorManager: SensorManager
  private var proximitySensor: Sensor? = null
  private var lastStateReportIsNear = false

  {
    Log.d(tag, "AppRTCProximitySensor" + util.AppRTCUtils.getThreadInfo())
    sensorManager = (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
  }

  /**
   * Activate the proximity sensor. Also do initializtion if called for the
   * first time.
   */
  public fun start(): Boolean {
    checkIfCalledOnValidThread()
    Log.d(tag, "start" + util.AppRTCUtils.getThreadInfo())
    if (!initDefaultSensor()) {
      // Proximity sensor is not supported on this device.
      return false
    }

    sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
    return true
  }

  /** Deactivate the proximity sensor.  */
  public fun stop() {
    checkIfCalledOnValidThread()
    Log.d(tag, "stop" + util.AppRTCUtils.getThreadInfo())
    if (proximitySensor != null) {
      sensorManager.unregisterListener(this, proximitySensor)
    }
  }

  /** Getter for last reported state. Set to true if "near" is reported.  */
  public fun sensorReportsNearState(): Boolean {
    checkIfCalledOnValidThread()
    return lastStateReportIsNear
  }

  override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
    checkIfCalledOnValidThread()
    util.AppRTCUtils.assertIsTrue(sensor.getType() == Sensor.TYPE_PROXIMITY)
    if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
      Log.e(tag, "The values returned by this sensor cannot be trusted")
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    checkIfCalledOnValidThread()
    util.AppRTCUtils.assertIsTrue(event.sensor.getType() == Sensor.TYPE_PROXIMITY)
    // As a best practice; do as little as possible within this method and
    // avoid blocking.
    val distanceInCentimeters = event.values[0]
    if (distanceInCentimeters < proximitySensor!!.getMaximumRange()) {
      Log.d(tag, "Proximity sensor => NEAR state")
      lastStateReportIsNear = true
    } else {
      Log.d(tag, "Proximity sensor => FAR state")
      lastStateReportIsNear = false
    }

    // Report about new state to listening client. Client can then call
    // sensorReportsNearState() to query the current state (NEAR or FAR).
    onSensorStateListener?.run()
    Log.d(tag, "onSensorChanged" + util.AppRTCUtils.getThreadInfo() + ": " + "accuracy=" + event.accuracy + ", timestamp=" + event.timestamp + ", distance=" + event.values[0])
  }

  /**
   * Get default proximity sensor if it exists. Tablet devices (e.g. Nexus 7)
   * does not support this type of sensor and false will be retured in such
   * cases.
   */
  private fun initDefaultSensor(): Boolean {
    if (proximitySensor != null) {
      return true
    }
    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    if (proximitySensor == null) {
      return false
    }
    logProximitySensorInfo()
    return true
  }

  /** Helper method for logging information about the proximity sensor.  */
  private fun logProximitySensorInfo() {
    if (proximitySensor == null) {
      return
    }
    val info = StringBuilder("Proximity sensor: ")
    info.append("name=" + proximitySensor!!.getName())
    info.append(", vendor: " + proximitySensor!!.getVendor())
    info.append(", power: " + proximitySensor!!.getPower())
    info.append(", resolution: " + proximitySensor!!.getResolution())
    info.append(", max range: " + proximitySensor!!.getMaximumRange())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
      // Added in API level 9.
      info.append(", min delay: " + proximitySensor!!.getMinDelay())
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
      // Added in API level 20.
      info.append(", type: " + proximitySensor!!.getStringType())
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Added in API level 21.
      info.append(", max delay: " + proximitySensor!!.getMaxDelay())
      info.append(", reporting mode: " + proximitySensor!!.getReportingMode())
      info.append(", isWakeUpSensor: " + proximitySensor!!.isWakeUpSensor())
    }
    Log.d(tag, info.toString())
  }

  /**
   * Helper method for debugging purposes. Ensures that method is
   * called on same thread as this object was created on.
   */
  private fun checkIfCalledOnValidThread() {
    if (!nonThreadSafe.calledOnValidThread()) {
      throw IllegalStateException("Method is not called on valid thread")
    }
  }

  companion object {
    private val tag = "AppRTCProximitySensor"

    fun create(context: Context, sensorStateListener: Runnable): AppRTCProximitySensor {
      return AppRTCProximitySensor(context, sensorStateListener)
    }
  }
}
