/*
 * libjingle
 * Copyright 2015 Google Inc.
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

import android.app.Fragment
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView

import org.webrtc.StatsReport

import java.util.HashMap

/**
 * Fragment for HUD statistics display.
 */
public class HudFragment : Fragment() {
  private var controlView: View? = null
  private var encoderStatView: TextView? = null
  private var hudViewBwe: TextView? = null
  private var hudViewConnection: TextView? = null
  private var hudViewVideoSend: TextView? = null
  private var hudViewVideoRecv: TextView? = null
  private var toggleDebugButton: ImageButton? = null
  private var videoCallEnabled: Boolean = false
  private var displayHud: Boolean = false
  volatile private var isRunning: Boolean = false
  private val cpuMonitor = CpuMonitor()

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle): View? {
    controlView = inflater.inflate(R.layout.fragment_hud, container, false)

    // Create UI controls.
    encoderStatView = controlView!!.findViewById(R.id.encoder_stat_call) as TextView
    hudViewBwe = controlView!!.findViewById(R.id.hud_stat_bwe) as TextView
    hudViewConnection = controlView!!.findViewById(R.id.hud_stat_connection) as TextView
    hudViewVideoSend = controlView!!.findViewById(R.id.hud_stat_video_send) as TextView
    hudViewVideoRecv = controlView!!.findViewById(R.id.hud_stat_video_recv) as TextView
    toggleDebugButton = controlView!!.findViewById(R.id.button_toggle_debug) as ImageButton

    toggleDebugButton!!.setOnClickListener(object : View.OnClickListener {
      override fun onClick(view: View) {
        if (displayHud) {
          val visibility = if ((hudViewBwe!!.getVisibility() == View.VISIBLE))
            View.INVISIBLE
          else
            View.VISIBLE
          hudViewsSetProperties(visibility)
        }
      }
    })

    return controlView
  }

  override fun onStart() {
    super.onStart()

    val args = getArguments()
    if (args != null) {
      videoCallEnabled = args.getBoolean(CallActivity.EXTRA_VIDEO_CALL, true)
      displayHud = args.getBoolean(CallActivity.EXTRA_DISPLAY_HUD, false)
    }
    val visibility = if (displayHud) View.VISIBLE else View.INVISIBLE
    encoderStatView!!.setVisibility(visibility)
    toggleDebugButton!!.setVisibility(visibility)
    hudViewsSetProperties(View.INVISIBLE)
    isRunning = true
  }

  override fun onStop() {
    isRunning = false
    super.onStop()
  }

  private fun hudViewsSetProperties(visibility: Int) {
    hudViewBwe!!.setVisibility(visibility)
    hudViewConnection!!.setVisibility(visibility)
    hudViewVideoSend!!.setVisibility(visibility)
    hudViewVideoRecv!!.setVisibility(visibility)
    hudViewBwe!!.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5)
    hudViewConnection!!.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5)
    hudViewVideoSend!!.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5)
    hudViewVideoRecv!!.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5)
  }

  private fun getReportMap(report: StatsReport): Map<String, String> {
    val reportMap = HashMap<String, String>()
    for (value in report.values) {
      reportMap.put(value.name, value.value)
    }
    return reportMap
  }

  public fun updateEncoderStatistics(reports: Array<StatsReport>) {
    if (!isRunning || !displayHud) {
      return
    }
    val encoderStat = StringBuilder(128)
    val bweStat = StringBuilder()
    val connectionStat = StringBuilder()
    val videoSendStat = StringBuilder()
    val videoRecvStat = StringBuilder()
    var fps: String? = null
    var targetBitrate: String? = null
    var actualBitrate: String? = null

    for (report in reports) {
      if (report.type == "ssrc" && report.id.contains("ssrc") && report.id.contains("send")) {
        // Send video statistics.
        val reportMap = getReportMap(report)
        val trackId = reportMap.get("googTrackId")
        if (trackId != null && trackId.contains(PeerConnectionClient.VIDEO_TRACK_ID)) {
          fps = reportMap.get("googFrameRateSent")
          videoSendStat.append(report.id).append("\n")
          for (value in report.values) {
            val name = value.name.replace("goog", "")
            videoSendStat.append(name).append("=").append(value.value).append("\n")
          }
        }
      } else if (report.type == "ssrc" && report.id.contains("ssrc") && report.id.contains("recv")) {
        // Receive video statistics.
        val reportMap = getReportMap(report)
        // Check if this stat is for video track.
        val frameWidth = reportMap.get("googFrameWidthReceived")
        if (frameWidth != null) {
          videoRecvStat.append(report.id).append("\n")
          for (value in report.values) {
            val name = value.name.replace("goog", "")
            videoRecvStat.append(name).append("=").append(value.value).append("\n")
          }
        }
      } else if (report.id == "bweforvideo") {
        // BWE statistics.
        val reportMap = getReportMap(report)
        targetBitrate = reportMap.get("googTargetEncBitrate")
        actualBitrate = reportMap.get("googActualEncBitrate")

        bweStat.append(report.id).append("\n")
        for (value in report.values) {
          val name = value.name.replace("goog", "").replace("Available", "")
          bweStat.append(name).append("=").append(value.value).append("\n")
        }
      } else if (report.type == "googCandidatePair") {
        // Connection statistics.
        val reportMap = getReportMap(report)
        val activeConnection = reportMap.get("googActiveConnection")
        if (activeConnection != null && activeConnection == "true") {
          connectionStat.append(report.id).append("\n")
          for (value in report.values) {
            val name = value.name.replace("goog", "")
            connectionStat.append(name).append("=").append(value.value).append("\n")
          }
        }
      }
    }
    hudViewBwe!!.setText(bweStat.toString())
    hudViewConnection!!.setText(connectionStat.toString())
    hudViewVideoSend!!.setText(videoSendStat.toString())
    hudViewVideoRecv!!.setText(videoRecvStat.toString())

    if (videoCallEnabled) {
      if (fps != null) {
        encoderStat.append("Fps:  ").append(fps).append("\n")
      }
      if (targetBitrate != null) {
        encoderStat.append("Target BR: ").append(targetBitrate).append("\n")
      }
      if (actualBitrate != null) {
        encoderStat.append("Actual BR: ").append(actualBitrate).append("\n")
      }
    }

    if (cpuMonitor.sampleCpuUtilization()) {
      encoderStat.append("CPU%: ").append(cpuMonitor.cpuCurrent).append("/").append(cpuMonitor.cpuAvg3).append("/").append(cpuMonitor.cpuAvgAll)
    }
    encoderStatView!!.setText(encoderStat.toString())
  }
}
