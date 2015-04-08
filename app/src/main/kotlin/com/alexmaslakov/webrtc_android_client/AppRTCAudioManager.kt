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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import com.alexmaslakov.webrtc_android_client.util

import java.util.Collections
import java.util.HashSet

public class AppRTCAudioManager private(
  private val apprtcContext: Context,
  private val onStateChangeListener: Runnable?
) {

  // TODO(henrika): add support for BLUETOOTH as well.
  public enum class AudioDevice {
    SPEAKER_PHONE
    WIRED_HEADSET
    EARPIECE
  }

  private var initialized = false
  private val audioManager: AudioManager
  private var savedAudioMode = AudioManager.MODE_INVALID
  private var savedIsSpeakerPhoneOn = false
  private var savedIsMicrophoneMute = false

  // For now; always use the speaker phone as default device selection when
  // there is a choice between SPEAKER_PHONE and EARPIECE.
  // TODO(henrika): it is possible that EARPIECE should be preferred in some
  // cases. If so, we should set this value at construction instead.
  private val defaultAudioDevice = AudioDevice.SPEAKER_PHONE

  // Proximity sensor object. It measures the proximity of an object in cm
  // relative to the view screen of a device and can therefore be used to
  // assist device switching (close to ear <=> use headset earpiece if
  // available, far from ear <=> use speaker phone).
  private var proximitySensor: AppRTCProximitySensor? = null

  // Contains the currently selected audio device.
  public var selectedAudioDevice: AudioDevice? = null
    private set

  // Contains a list of available audio devices. A Set collection is used to
  // avoid duplicate elements.
  private val audioDevices = HashSet<AudioDevice>()

  // Broadcast receiver for wired headset intent broadcasts.
  private var wiredHeadsetReceiver: BroadcastReceiver? = null

  // This method is called when the proximity sensor reports a state change,
  // e.g. from "NEAR to FAR" or from "FAR to NEAR".
  private fun onProximitySensorChangedState() {
    // The proximity sensor should only be activated when there are exactly two
    // available audio devices.
    if (audioDevices.size() == 2 && audioDevices.contains(AppRTCAudioManager.AudioDevice.EARPIECE) && audioDevices.contains(AppRTCAudioManager.AudioDevice.SPEAKER_PHONE)) {
      if (proximitySensor!!.sensorReportsNearState()) {
        // Sensor reports that a "handset is being held up to a person's ear",
        // or "something is covering the light sensor".
        setAudioDevice(AppRTCAudioManager.AudioDevice.EARPIECE)
      } else {
        // Sensor reports that a "handset is removed from a person's ear", or
        // "the light sensor is no longer covered".
        setAudioDevice(AppRTCAudioManager.AudioDevice.SPEAKER_PHONE)
      }
    }
  }

  init {
    audioManager = (apprtcContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    // Create and initialize the proximity sensor.
    // Tablet devices (e.g. Nexus 7) does not support proximity sensors.
    // Note that, the sensor will not be active until start() has been called.
    proximitySensor = AppRTCProximitySensor.create(apprtcContext, object : Runnable {
      // This method will be called each time a state change is detected.
      // Example: user holds his hand over the device (closer than ~5 cm),
      // or removes his hand from the device.
      override fun run() {
        onProximitySensorChangedState()
      }
    })
    util.AppRTCUtils.logDeviceInfo(TAG)
  }

  public fun init() {
    Log.d(TAG, "init")
    if (initialized) {
      return
    }

    // Store current audio state so we can restore it when close() is called.
    savedAudioMode = audioManager.getMode()
    savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn()
    savedIsMicrophoneMute = audioManager.isMicrophoneMute()

    // Request audio focus before making any device switch.
    audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

    // The AppRTC demo shall always run in COMMUNICATION mode since it will
    // result in best possible "VoIP settings", like audio routing, volume
    // control etc.
    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION)

    // Always disable microphone mute during a WebRTC call.
    setMicrophoneMute(false)

    // Do initial selection of audio device. This setting can later be changed
    // either by adding/removing a wired headset or by covering/uncovering the
    // proximity sensor.
    updateAudioDeviceState(hasWiredHeadset())

    // Register receiver for broadcast intents related to adding/removing a
    // wired headset (Intent.ACTION_HEADSET_PLUG).
    registerForWiredHeadsetIntentBroadcast()

    initialized = true
  }

  public fun close() {
    Log.d(TAG, "close")
    if (!initialized) {
      return
    }

    unregisterForWiredHeadsetIntentBroadcast()

    // Restore previously stored audio states.
    setSpeakerphoneOn(savedIsSpeakerPhoneOn)
    setMicrophoneMute(savedIsMicrophoneMute)
    audioManager.setMode(savedAudioMode)
    audioManager.abandonAudioFocus(null)

    if (proximitySensor != null) {
      proximitySensor!!.stop()
      proximitySensor = null
    }

    initialized = false
  }

  /** Changes selection of the currently active audio device.  */
  public fun setAudioDevice(device: AudioDevice) {
    Log.d(TAG, "setAudioDevice(device=" + device + ")")
    util.AppRTCUtils.assertIsTrue(audioDevices.contains(device))

    when (device) {
      AppRTCAudioManager.AudioDevice.SPEAKER_PHONE -> {
        setSpeakerphoneOn(true)
        selectedAudioDevice = AudioDevice.SPEAKER_PHONE
      }
      AppRTCAudioManager.AudioDevice.EARPIECE -> {
        setSpeakerphoneOn(false)
        selectedAudioDevice = AudioDevice.EARPIECE
      }
      AppRTCAudioManager.AudioDevice.WIRED_HEADSET -> {
        setSpeakerphoneOn(false)
        selectedAudioDevice = AudioDevice.WIRED_HEADSET
      }
      else -> Log.e(TAG, "Invalid audio device selection")
    }
    onAudioManagerChangedState()
  }

  /** Returns current set of available/selectable audio devices.  */
  public fun getAudioDevices(): Set<AudioDevice> {
    return Collections.unmodifiableSet<AudioDevice>(HashSet(audioDevices))
  }

  /**
   * Registers receiver for the broadcasted intent when a wired headset is
   * plugged in or unplugged. The received intent will have an extra
   * 'state' value where 0 means unplugged, and 1 means plugged.
   */
  private fun registerForWiredHeadsetIntentBroadcast() {
    val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)

    /** Receiver which handles changes in wired headset availability.  */
    wiredHeadsetReceiver = object : BroadcastReceiver() {
      private val STATE_UNPLUGGED = 0
      private val STATE_PLUGGED = 1
      private val HAS_NO_MIC = 0
      private val HAS_MIC = 1

      override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getIntExtra("state", STATE_UNPLUGGED)
        val microphone = intent.getIntExtra("microphone", HAS_NO_MIC)
        val name = intent.getStringExtra("name")

        Log.d(
          TAG,
          "BroadcastReceiver.onReceive ${util.AppRTCUtils.getThreadInfo()}: a=${intent.getAction()}" +
          ", s=${(if (state == STATE_UNPLUGGED) "unplugged" else "plugged")}" +
          ", m=${(if (microphone == HAS_MIC) "mic" else "no mic")}, n=${name}" +
          ", sb=" + isInitialStickyBroadcast()
        )

        val hasWiredHeadset = state == STATE_PLUGGED
        when (state) {
          STATE_UNPLUGGED -> updateAudioDeviceState(hasWiredHeadset)
          STATE_PLUGGED -> {
            if (selectedAudioDevice != AudioDevice.WIRED_HEADSET) {
              updateAudioDeviceState(hasWiredHeadset)
            }
          }
          else -> Log.e(TAG, "Invalid state")
        }
      }
    }

    apprtcContext.registerReceiver(wiredHeadsetReceiver, filter)
  }

  /** Unregister receiver for broadcasted ACTION_HEADSET_PLUG intent.  */
  private fun unregisterForWiredHeadsetIntentBroadcast() {
    apprtcContext.unregisterReceiver(wiredHeadsetReceiver)
    wiredHeadsetReceiver = null
  }

  /** Sets the speaker phone mode.  */
  private fun setSpeakerphoneOn(on: Boolean) {
    val wasOn = audioManager.isSpeakerphoneOn()
    if (wasOn == on) {
      return
    }

    audioManager.setSpeakerphoneOn(on)
  }

  /** Sets the microphone mute state.  */
  private fun setMicrophoneMute(on: Boolean) {
    val wasMuted = audioManager.isMicrophoneMute()
    if (wasMuted == on) {
      return
    }
    audioManager.setMicrophoneMute(on)
  }

  /** Gets the current earpiece state.  */
  private fun hasEarpiece(): Boolean {
    return apprtcContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
  }

  /**
   * Checks whether a wired headset is connected or not.
   * This is not a valid indication that audio playback is actually over
   * the wired headset as audio routing depends on other conditions. We
   * only use it as an early indicator (during initialization) of an attached
   * wired headset.
   */
  deprecated("")
  private fun hasWiredHeadset(): Boolean {
    return audioManager.isWiredHeadsetOn()
  }

  /** Update list of possible audio devices and make new device selection.  */
  private fun updateAudioDeviceState(hasWiredHeadset: Boolean) {
    // Update the list of available audio devices.
    audioDevices.clear()
    if (hasWiredHeadset) {
      // If a wired headset is connected, then it is the only possible option.
      audioDevices.add(AudioDevice.WIRED_HEADSET)
    } else {
      // No wired headset, hence the audio-device list can contain speaker
      // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
      audioDevices.add(AudioDevice.SPEAKER_PHONE)
      if (hasEarpiece()) {
        audioDevices.add(AudioDevice.EARPIECE)
      }
    }

    Log.d(TAG, "audioDevices: " + audioDevices)

    // Switch to correct audio device given the list of available audio devices.
    if (hasWiredHeadset) {
      setAudioDevice(AudioDevice.WIRED_HEADSET)
    } else {
      setAudioDevice(defaultAudioDevice)
    }
  }

  /** Called each time a new audio device has been added or removed.  */
  private fun onAudioManagerChangedState() {
    Log.d(TAG, "onAudioManagerChangedState: devices=" + audioDevices + ", selected=" + selectedAudioDevice)

    // Enable the proximity sensor if there are two available audio devices
    // in the list. Given the current implementation, we know that the choice
    // will then be between EARPIECE and SPEAKER_PHONE.
    if (audioDevices.size() == 2) {
      AppRTCUtils.assertIsTrue(audioDevices.contains(AudioDevice.EARPIECE) && audioDevices.contains(AudioDevice.SPEAKER_PHONE))
      // Start the proximity sensor.
      proximitySensor!!.start()
    } else if (audioDevices.size() == 1) {
      // Stop the proximity sensor since it is no longer needed.
      proximitySensor!!.stop()
    } else {
      Log.e(TAG, "Invalid device list")
    }

    onStateChangeListener?.run()
  }

  companion object {
    private val TAG = "AppRTCAudioManager"

    /** Construction  */
    fun create(context: Context, deviceStateChangeListener: Runnable): AppRTCAudioManager {
      return AppRTCAudioManager(context, deviceStateChangeListener)
    }
  }
}
/** Returns the currently selected audio device.  */
