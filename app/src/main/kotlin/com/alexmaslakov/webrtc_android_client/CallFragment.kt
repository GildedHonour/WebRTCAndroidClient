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

import android.app.Activity
import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView

import org.webrtc.VideoRendererGui.ScalingType

/**
 * Fragment for call control.
 */
public class CallFragment : Fragment() {
  private var controlView: View? = null
  private var contactView: TextView? = null
  private var disconnectButton: ImageButton? = null
  private var cameraSwitchButton: ImageButton? = null
  private var videoScalingButton: ImageButton? = null
  private var callEvents: OnCallEvents? = null
  private var scalingType: ScalingType? = null
  private var videoCallEnabled = true

  /**
   * Call control interface for container activity.
   */
  public trait OnCallEvents {
    public fun onCallHangUp()
    public fun onCameraSwitch()
    public fun onVideoScalingSwitch(scalingType: ScalingType)
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle): View? {
    controlView = inflater.inflate(R.layout.fragment_call, container, false)

    // Create UI controls.
    contactView = controlView!!.findViewById(R.id.contact_name_call) as TextView
    disconnectButton = controlView!!.findViewById(R.id.button_call_disconnect) as ImageButton
    cameraSwitchButton = controlView!!.findViewById(R.id.button_call_switch_camera) as ImageButton
    videoScalingButton = controlView!!.findViewById(R.id.button_call_scaling_mode) as ImageButton

    // Add buttons click events.
    disconnectButton!!.setOnClickListener {
      callEvents!!.onCallHangUp()
    }

    cameraSwitchButton!!.setOnClickListener {
        callEvents!!.onCameraSwitch()
    }


    videoScalingButton!!.setOnClickListener {
      if (scalingType == ScalingType.SCALE_ASPECT_FILL) {
        videoScalingButton!!.setBackgroundResource(R.drawable.ic_action_full_screen)
        scalingType = ScalingType.SCALE_ASPECT_FIT
      } else {
        videoScalingButton!!.setBackgroundResource(R.drawable.ic_action_return_from_full_screen)
        scalingType = ScalingType.SCALE_ASPECT_FILL
      }
      callEvents!!.onVideoScalingSwitch(scalingType!!)

    }

    scalingType = ScalingType.SCALE_ASPECT_FILL
    return controlView
  }

  override fun onStart() {
    super.onStart()

    val args = getArguments()
    if (args != null) {
      val contactName = args.getString(CallActivity.EXTRA_ROOMID)
      contactView!!.setText(contactName)
      videoCallEnabled = args.getBoolean(CallActivity.EXTRA_VIDEO_CALL, true)
    }
    if (!videoCallEnabled) {
      cameraSwitchButton!!.setVisibility(View.INVISIBLE)
    }
  }

  override fun onAttach(activity: Activity) {
    super.onAttach(activity)
    callEvents = activity as OnCallEvents
  }

}
