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

import android.app.Activity
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.preference.Preference

/**
 * Settings activity for AppRTC.
 */
public class SettingsActivity : Activity(), OnSharedPreferenceChangeListener {
  private var settingsFragment: SettingsFragment? = null
  private var keyprefVideoCall: String? = null
  private var keyprefResolution: String? = null
  private var keyprefFps: String? = null
  private var keyprefStartVideoBitrateType: String? = null
  private var keyprefStartVideoBitrateValue: String? = null
  private var keyPrefVideoCodec: String? = null
  private var keyprefHwCodec: String? = null

  private var keyprefStartAudioBitrateType: String? = null
  private var keyprefStartAudioBitrateValue: String? = null
  private var keyPrefAudioCodec: String? = null

  private var keyprefCpuUsageDetection: String? = null
  private var keyPrefRoomServerUrl: String? = null
  private var keyPrefDisplayHud: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    keyprefVideoCall = getString(R.string.pref_videocall_key)
    keyprefResolution = getString(R.string.pref_resolution_key)
    keyprefFps = getString(R.string.pref_fps_key)
    keyprefStartVideoBitrateType = getString(R.string.pref_startvideobitrate_key)
    keyprefStartVideoBitrateValue = getString(R.string.pref_startvideobitratevalue_key)
    keyPrefVideoCodec = getString(R.string.pref_videocodec_key)
    keyprefHwCodec = getString(R.string.pref_hwcodec_key)

    keyprefStartAudioBitrateType = getString(R.string.pref_startaudiobitrate_key)
    keyprefStartAudioBitrateValue = getString(R.string.pref_startaudiobitratevalue_key)
    keyPrefAudioCodec = getString(R.string.pref_audiocodec_key)

    keyprefCpuUsageDetection = getString(R.string.pref_cpu_usage_detection_key)
    keyPrefRoomServerUrl = getString(R.string.pref_room_server_url_key)
    keyPrefDisplayHud = getString(R.string.pref_displayhud_key)

    // Display the fragment as the main content.
    settingsFragment = SettingsFragment()
    getFragmentManager().beginTransaction().replace(android.R.id.content, settingsFragment).commit()
  }

  override fun onResume() {
    super.onResume()
    // Set summary to be the user-description for the selected value
    val sharedPreferences = settingsFragment!!.getPreferenceScreen().getSharedPreferences()
    sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    updateSummaryB(sharedPreferences, keyprefVideoCall)
    updateSummary(sharedPreferences, keyprefResolution)
    updateSummary(sharedPreferences, keyprefFps)
    updateSummary(sharedPreferences, keyprefStartVideoBitrateType)
    updateSummaryBitrate(sharedPreferences, keyprefStartVideoBitrateValue)
    setVideoBitrateEnable(sharedPreferences)
    updateSummary(sharedPreferences, keyPrefVideoCodec)
    updateSummaryB(sharedPreferences, keyprefHwCodec)

    updateSummary(sharedPreferences, keyprefStartAudioBitrateType)
    updateSummaryBitrate(sharedPreferences, keyprefStartAudioBitrateValue)
    setAudioBitrateEnable(sharedPreferences)
    updateSummary(sharedPreferences, keyPrefAudioCodec)

    updateSummaryB(sharedPreferences, keyprefCpuUsageDetection)
    updateSummary(sharedPreferences, keyPrefRoomServerUrl)
    updateSummaryB(sharedPreferences, keyPrefDisplayHud)
  }

  override fun onPause() {
    super.onPause()
    val sharedPreferences = settingsFragment!!.getPreferenceScreen().getSharedPreferences()
    sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
  }

  override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
    if (key == keyprefResolution || key == keyprefFps || key == keyprefStartVideoBitrateType || key == keyPrefVideoCodec || key == keyprefStartAudioBitrateType || key == keyPrefAudioCodec || key == keyPrefRoomServerUrl) {
      updateSummary(sharedPreferences, key)
    } else if (key == keyprefStartVideoBitrateValue || key == keyprefStartAudioBitrateValue) {
      updateSummaryBitrate(sharedPreferences, key)
    } else if (key == keyprefVideoCall || key == keyprefHwCodec || key == keyprefCpuUsageDetection || key == keyPrefDisplayHud) {
      updateSummaryB(sharedPreferences, key)
    }
    if (key == keyprefStartVideoBitrateType) {
      setVideoBitrateEnable(sharedPreferences)
    }
    if (key == keyprefStartAudioBitrateType) {
      setAudioBitrateEnable(sharedPreferences)
    }
  }

  private fun updateSummary(sharedPreferences: SharedPreferences, key: String) {
    val updatedPref = settingsFragment!!.findPreference(key)
    // Set summary to be the user-description for the selected value
    updatedPref.setSummary(sharedPreferences.getString(key, ""))
  }

  private fun updateSummaryBitrate(sharedPreferences: SharedPreferences, key: String) {
    val updatedPref = settingsFragment!!.findPreference(key)
    updatedPref.setSummary(sharedPreferences.getString(key, "") + " kbps")
  }

  private fun updateSummaryB(sharedPreferences: SharedPreferences, key: String) {
    val updatedPref = settingsFragment!!.findPreference(key)
    updatedPref.setSummary(if (sharedPreferences.getBoolean(key, true))
      getString(R.string.pref_value_enabled)
    else
      getString(R.string.pref_value_disabled))
  }

  private fun setVideoBitrateEnable(sharedPreferences: SharedPreferences) {
    val bitratePreferenceValue = settingsFragment!!.findPreference(keyprefStartVideoBitrateValue)
    val bitrateTypeDefault = getString(R.string.pref_startvideobitrate_default)
    val bitrateType = sharedPreferences.getString(keyprefStartVideoBitrateType, bitrateTypeDefault)
    if (bitrateType == bitrateTypeDefault) {
      bitratePreferenceValue.setEnabled(false)
    } else {
      bitratePreferenceValue.setEnabled(true)
    }
  }

  private fun setAudioBitrateEnable(sharedPreferences: SharedPreferences) {
    val bitratePreferenceValue = settingsFragment!!.findPreference(keyprefStartAudioBitrateValue)
    val bitrateTypeDefault = getString(R.string.pref_startaudiobitrate_default)
    val bitrateType = sharedPreferences.getString(keyprefStartAudioBitrateType, bitrateTypeDefault)
    if (bitrateType == bitrateTypeDefault) {
      bitratePreferenceValue.setEnabled(false)
    } else {
      bitratePreferenceValue.setEnabled(true)
    }
  }
}
