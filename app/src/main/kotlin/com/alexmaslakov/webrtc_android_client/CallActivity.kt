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

import com.alexmaslakov.webrtc_android_client.AppRTCClient.RoomConnectionParameters
import com.alexmaslakov.webrtc_android_client.AppRTCClient.SignalingParameters
import com.alexmaslakov.webrtc_android_client.PeerConnectionClient.PeerConnectionParameters
import com.alexmaslakov.webrtc_android_client.util.LooperExecutor

import android.app.Activity
import android.app.AlertDialog
import android.app.FragmentTransaction
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import com.alexmaslakov.webrtc_android_client.util

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.StatsReport
import org.webrtc.VideoRenderer
import org.webrtc.VideoRendererGui
import org.webrtc.VideoRendererGui.ScalingType

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity : Activity(), AppRTCClient.SignalingEvents, PeerConnectionClient.PeerConnectionEvents, CallFragment.OnCallEvents {

  private var peerConnectionClient: PeerConnectionClient? = null
  private var appRtcClient: AppRTCClient? = null
  private var signalingParameters: SignalingParameters? = null
  private var audioManager: AppRTCAudioManager? = null
  private var localRender: VideoRenderer.Callbacks? = null
  private var remoteRender: VideoRenderer.Callbacks? = null
  private var scalingType: ScalingType? = null
  private var logToast: Toast? = null
  private var commandLineRun: Boolean = false
  private var runTimeMs: Int = 0
  private var activityRunning: Boolean = false
  private var roomConnectionParameters: RoomConnectionParameters? = null
  private var peerConnectionParameters: PeerConnectionParameters? = null
  private var iceConnected: Boolean = false
  private var isError: Boolean = false
  private var callControlFragmentVisible = true
  private var callStartedTimeMs: Long = 0

  // Controls
  private var videoView: GLSurfaceView? = null
  var callFragment: CallFragment? = null
  var hudFragment: HudFragment? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Thread.setDefaultUncaughtExceptionHandler(UnhandledExceptionHandler(this))

    // Set window styles for fullscreen-window size. Needs to be done before
    // adding content.
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    getWindow().addFlags(LayoutParams.FLAG_FULLSCREEN or LayoutParams.FLAG_KEEP_SCREEN_ON or LayoutParams.FLAG_DISMISS_KEYGUARD or LayoutParams.FLAG_SHOW_WHEN_LOCKED or LayoutParams.FLAG_TURN_SCREEN_ON)
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    setContentView(R.layout.activity_call)

    iceConnected = false
    signalingParameters = null
    scalingType = ScalingType.SCALE_ASPECT_FILL

    // Create UI controls.
    videoView = findViewById(R.id.glview_call) as GLSurfaceView
    callFragment = CallFragment()
    hudFragment = HudFragment()

    // Create video renderers.
    VideoRendererGui.setView(videoView, object : Runnable {
      override fun run() {
        createPeerConnectionFactory()
      }
    })
    remoteRender = VideoRendererGui.create(REMOTE_X, REMOTE_Y, REMOTE_WIDTH, REMOTE_HEIGHT, scalingType, false)
    localRender = VideoRendererGui.create(LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING, LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING, scalingType, true)

    // Show/hide call control fragment on view click.
    videoView!!.setOnClickListener(object : View.OnClickListener {
      override fun onClick(view: View) {
        toggleCallControlFragmentVisibility()
      }
    })

    // Get Intent parameters.
    val intent = getIntent()
    val roomUri = intent.getData()
    if (roomUri == null) {
      logAndToast(getString(R.string.missing_url))
      Log.e(TAG, "Didn't get any URL in intent!")
      setResult(Activity.RESULT_CANCELED)
      finish()
      return
    }
    val roomId = intent.getStringExtra(EXTRA_ROOMID)
    if (roomId == null || roomId.length() == 0) {
      logAndToast(getString(R.string.missing_url))
      Log.e(TAG, "Incorrect room ID in intent!")
      setResult(Activity.RESULT_CANCELED)
      finish()
      return
    }
    val loopback = intent.getBooleanExtra(EXTRA_LOOPBACK, false)
    peerConnectionParameters = PeerConnectionParameters(intent.getBooleanExtra(EXTRA_VIDEO_CALL, true), loopback, intent.getIntExtra(EXTRA_VIDEO_WIDTH, 0), intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 0), intent.getIntExtra(EXTRA_VIDEO_FPS, 0), intent.getIntExtra(EXTRA_VIDEO_BITRATE, 0), intent.getStringExtra(EXTRA_VIDEOCODEC), intent.getBooleanExtra(EXTRA_HWCODEC_ENABLED, true), intent.getIntExtra(EXTRA_AUDIO_BITRATE, 0), intent.getStringExtra(EXTRA_AUDIOCODEC), intent.getBooleanExtra(EXTRA_CPUOVERUSE_DETECTION, true))
    commandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false)
    runTimeMs = intent.getIntExtra(EXTRA_RUNTIME, 0)

    // Create connection client and connection parameters.
    appRtcClient = WebSocketRTCClient(this, util.LooperExecutor())
    roomConnectionParameters = RoomConnectionParameters(roomUri.toString(), roomId, loopback)

    // Send intent arguments to fragments.
    callFragment.setArguments(intent.getExtras())
    hudFragment.setArguments(intent.getExtras())
    // Activate call and HUD fragments and start the call.
    val ft = getFragmentManager().beginTransaction()
    ft.add(R.id.call_fragment_container, callFragment)
    ft.add(R.id.hud_fragment_container, hudFragment)
    ft.commit()
    startCall()

    // For command line execution run connection for <runTimeMs> and exit.
    if (commandLineRun && runTimeMs > 0) {
      videoView!!.postDelayed(object : Runnable {
        override fun run() {
          disconnect()
        }
      }, runTimeMs.toLong())
    }
  }

  // Activity interfaces
  override fun onPause() {
    super.onPause()
    videoView!!.onPause()
    activityRunning = false
    if (peerConnectionClient != null) {
      peerConnectionClient!!.stopVideoSource()
    }
  }

  override fun onResume() {
    super.onResume()
    videoView!!.onResume()
    activityRunning = true
    if (peerConnectionClient != null) {
      peerConnectionClient!!.startVideoSource()
    }
  }

  override fun onDestroy() {
    disconnect()
    super.onDestroy()
    if (logToast != null) {
      logToast!!.cancel()
    }
    activityRunning = false
  }

  // CallFragment.OnCallEvents interface implementation.
  override fun onCallHangUp() {
    disconnect()
  }

  override fun onCameraSwitch() {
    if (peerConnectionClient != null) {
      peerConnectionClient!!.switchCamera()
    }
  }

  override fun onVideoScalingSwitch(scalingType: ScalingType) {
    this.scalingType = scalingType
    updateVideoView()
  }

  // Helper functions.
  private fun toggleCallControlFragmentVisibility() {
    if (!iceConnected || !callFragment.isAdded()) {
      return
    }
    // Show/hide call control fragment
    callControlFragmentVisible = !callControlFragmentVisible
    val ft = getFragmentManager().beginTransaction()
    if (callControlFragmentVisible) {
      ft.show(callFragment)
      ft.show(hudFragment)
    } else {
      ft.hide(callFragment)
      ft.hide(hudFragment)
    }
    ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
    ft.commit()
  }

  private fun updateVideoView() {
    VideoRendererGui.update(remoteRender, REMOTE_X, REMOTE_Y, REMOTE_WIDTH, REMOTE_HEIGHT, scalingType)
    if (iceConnected) {
      VideoRendererGui.update(localRender, LOCAL_X_CONNECTED, LOCAL_Y_CONNECTED, LOCAL_WIDTH_CONNECTED, LOCAL_HEIGHT_CONNECTED, ScalingType.SCALE_ASPECT_FIT)
    } else {
      VideoRendererGui.update(localRender, LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING, LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING, scalingType)
    }
  }

  private fun startCall() {
    if (appRtcClient == null) {
      Log.e(TAG, "AppRTC client is not allocated for a call.")
      return
    }
    callStartedTimeMs = System.currentTimeMillis()

    // Start room connection.
    logAndToast(getString(R.string.connecting_to, roomConnectionParameters!!.roomUrl))
    appRtcClient!!.connectToRoom(roomConnectionParameters)

    // Create and audio manager that will take care of audio routing,
    // audio modes, audio device enumeration etc.
    audioManager = AppRTCAudioManager.create(this, object : Runnable {
      // This method will be called each time the audio state (number and
      // type of devices) has been changed.
      override fun run() {
        onAudioManagerChangedState()
      }
    })
    // Store existing audio settings and change audio mode to
    // MODE_IN_COMMUNICATION for best possible VoIP performance.
    Log.d(TAG, "Initializing the audio manager...")
    audioManager!!.init()
  }

  // Should be called from UI thread
  private fun callConnected() {
    val delta = System.currentTimeMillis() - callStartedTimeMs
    Log.i(TAG, "Call connected: delay=" + delta + "ms")

    // Update video view.
    updateVideoView()
    // Enable statistics callback.
    peerConnectionClient!!.enableStatsEvents(true, STAT_CALLBACK_PERIOD)
  }

  private fun onAudioManagerChangedState() {
    // TODO(henrika): disable video if AppRTCAudioManager.AudioDevice.EARPIECE
    // is active.
  }

  // Create peer connection factory when EGL context is ready.
  private fun createPeerConnectionFactory() {
    runOnUiThread(object : Runnable {
      override fun run() {
        if (peerConnectionClient == null) {
          val delta = System.currentTimeMillis() - callStartedTimeMs
          Log.d(TAG, "Creating peer connection factory, delay=" + delta + "ms")
          peerConnectionClient = PeerConnectionClient()
          peerConnectionClient!!.createPeerConnectionFactory(this@CallActivity,
            VideoRendererGui.getEGLContext(), peerConnectionParameters!!, this@CallActivity
          )
        }

        if (signalingParameters != null) {
          Log.w(TAG, "EGL context is ready after room connection.")
          onConnectedToRoomInternal(signalingParameters!!)
        }
      }
    })
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  private fun disconnect() {
    activityRunning = false
    if (appRtcClient != null) {
      appRtcClient!!.disconnectFromRoom()
      appRtcClient = null
    }
    if (peerConnectionClient != null) {
      peerConnectionClient!!.close()
      peerConnectionClient = null
    }
    if (audioManager != null) {
      audioManager!!.close()
      audioManager = null
    }
    if (iceConnected && !isError) {
      setResult(Activity.RESULT_OK)
    } else {
      setResult(Activity.RESULT_CANCELED)
    }
    finish()
  }

  private fun disconnectWithErrorMessage(errorMessage: String) {
    if (commandLineRun || !activityRunning) {
      Log.e(TAG, "Critical error: " + errorMessage)
      disconnect()
    } else {
      AlertDialog.Builder(this).setTitle(getText(R.string.channel_error_title)).setMessage(errorMessage).setCancelable(false).setNeutralButton(R.string.ok, object : DialogInterface.OnClickListener {
        override fun onClick(dialog: DialogInterface, id: Int) {
          dialog.cancel()
          disconnect()
        }
      }).create().show()
    }
  }

  // Log |msg| and Toast about it.
  private fun logAndToast(msg: String) {
    Log.d(TAG, msg)
    if (logToast != null) {
      logToast!!.cancel()
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
    logToast!!.show()
  }

  private fun reportError(description: String) {
    runOnUiThread(object : Runnable {
      override fun run() {
        if (!isError) {
          isError = true
          disconnectWithErrorMessage(description)
        }
      }
    })
  }

  // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
  // All callbacks are invoked from websocket signaling looper thread and
  // are routed to UI thread.
  private fun onConnectedToRoomInternal(params: SignalingParameters) {
    val delta = System.currentTimeMillis() - callStartedTimeMs

    signalingParameters = params
    if (peerConnectionClient == null) {
      Log.w(TAG, "Room is connected, but EGL context is not ready yet.")
      return
    }
    logAndToast("Creating peer connection, delay=" + delta + "ms")
    peerConnectionClient!!.createPeerConnection(localRender, remoteRender, signalingParameters)

    if (signalingParameters!!.initiator) {
      logAndToast("Creating OFFER...")
      // Create offer. Offer SDP will be sent to answering client in
      // PeerConnectionEvents.onLocalDescription event.
      peerConnectionClient!!.createOffer()
    } else {
      if (params.offerSdp != null) {
        peerConnectionClient!!.setRemoteDescription(params.offerSdp)
        logAndToast("Creating ANSWER...")
        // Create answer. Answer SDP will be sent to offering client in
        // PeerConnectionEvents.onLocalDescription event.
        peerConnectionClient!!.createAnswer()
      }
      if (params.iceCandidates != null) {
        // Add remote ICE candidates from room.
        for (iceCandidate in params.iceCandidates) {
          peerConnectionClient!!.addRemoteIceCandidate(iceCandidate)
        }
      }
    }
  }

  override fun onConnectedToRoom(params: SignalingParameters) {
    runOnUiThread(object : Runnable {
      override fun run() {
        onConnectedToRoomInternal(params)
      }
    })
  }

  override fun onRemoteDescription(sdp: SessionDescription) {
    val delta = System.currentTimeMillis() - callStartedTimeMs
    runOnUiThread(object : Runnable {
      override fun run() {
        if (peerConnectionClient == null) {
          Log.e(TAG, "Received remote SDP for non-initilized peer connection.")
          return
        }
        logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms")
        peerConnectionClient!!.setRemoteDescription(sdp)
        if (!signalingParameters!!.initiator) {
          logAndToast("Creating ANSWER...")
          // Create answer. Answer SDP will be sent to offering client in
          // PeerConnectionEvents.onLocalDescription event.
          peerConnectionClient!!.createAnswer()
        }
      }
    })
  }

  override fun onRemoteIceCandidate(candidate: IceCandidate) {
    runOnUiThread(object : Runnable {
      override fun run() {
        if (peerConnectionClient == null) {
          Log.e(TAG, "Received ICE candidate for non-initilized peer connection.")
          return
        }
        peerConnectionClient!!.addRemoteIceCandidate(candidate)
      }
    })
  }

  override fun onChannelClose() {
    runOnUiThread(object : Runnable {
      override fun run() {
        logAndToast("Remote end hung up; dropping PeerConnection")
        disconnect()
      }
    })
  }

  override fun onChannelError(description: String) {
    reportError(description)
  }

  // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
  // Send local peer connection SDP and ICE candidates to remote party.
  // All callbacks are invoked from peer connection client looper thread and
  // are routed to UI thread.
  override fun onLocalDescription(sdp: SessionDescription) {
    val delta = System.currentTimeMillis() - callStartedTimeMs
    runOnUiThread(object : Runnable {
      override fun run() {
        if (appRtcClient != null) {
          logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms")
          if (signalingParameters!!.initiator) appRtcClient!!.sendOfferSdp(sdp)
          else appRtcClient!!.sendAnswerSdp(sdp)
        }
      }
    })
  }

  override fun onIceCandidate(candidate: IceCandidate) {
    runOnUiThread {
      if (appRtcClient != null) {
        appRtcClient!!.sendLocalIceCandidate(candidate)
      }
    }
  }

  override fun onIceConnected() {
    val delta = System.currentTimeMillis() - callStartedTimeMs
    runOnUiThread(object : Runnable {
      override fun run() {
        logAndToast("ICE connected, delay=" + delta + "ms")
        iceConnected = true
        callConnected()
      }
    })
  }

  override fun onIceDisconnected() {
    runOnUiThread(object : Runnable {
      override fun run() {
        logAndToast("ICE disconnected")
        iceConnected = false
        disconnect()
      }
    })
  }

  override fun onPeerConnectionClosed() {
  }

  override fun onPeerConnectionStatsReady(reports: Array<StatsReport>) {
    runOnUiThread(object : Runnable {
      override fun run() {
        if (!isError && iceConnected) {
          hudFragment.updateEncoderStatistics(reports)
        }
      }
    })
  }

  override fun onPeerConnectionError(description: String) {
    reportError(description)
  }

  companion object {

    public val EXTRA_ROOMID: String = "com.alexmaslakov.webrtc_android_client.ROOMID"
    public val EXTRA_LOOPBACK: String = "com.alexmaslakov.webrtc_android_client.LOOPBACK"
    public val EXTRA_VIDEO_CALL: String = "com.alexmaslakov.webrtc_android_client.VIDEO_CALL"
    public val EXTRA_VIDEO_WIDTH: String = "com.alexmaslakov.webrtc_android_client.VIDEO_WIDTH"
    public val EXTRA_VIDEO_HEIGHT: String = "com.alexmaslakov.webrtc_android_client.VIDEO_HEIGHT"
    public val EXTRA_VIDEO_FPS: String = "com.alexmaslakov.webrtc_android_client.VIDEO_FPS"
    public val EXTRA_VIDEO_BITRATE: String = "com.alexmaslakov.webrtc_android_client.VIDEO_BITRATE"
    public val EXTRA_VIDEOCODEC: String = "com.alexmaslakov.webrtc_android_client.VIDEOCODEC"
    public val EXTRA_HWCODEC_ENABLED: String = "com.alexmaslakov.webrtc_android_client.HWCODEC"
    public val EXTRA_AUDIO_BITRATE: String = "com.alexmaslakov.webrtc_android_client.AUDIO_BITRATE"
    public val EXTRA_AUDIOCODEC: String = "com.alexmaslakov.webrtc_android_client.AUDIOCODEC"
    public val EXTRA_CPUOVERUSE_DETECTION: String = "com.alexmaslakov.webrtc_android_client.CPUOVERUSE_DETECTION"
    public val EXTRA_DISPLAY_HUD: String = "com.alexmaslakov.webrtc_android_client.DISPLAY_HUD"
    public val EXTRA_CMDLINE: String = "com.alexmaslakov.webrtc_android_client.CMDLINE"
    public val EXTRA_RUNTIME: String = "com.alexmaslakov.webrtc_android_client.RUNTIME"
    private val TAG = "CallRTCClient"
    // Peer connection statistics callback period in ms.
    private val STAT_CALLBACK_PERIOD = 1000
    // Local preview screen position before call is connected.
    private val LOCAL_X_CONNECTING = 0
    private val LOCAL_Y_CONNECTING = 0
    private val LOCAL_WIDTH_CONNECTING = 100
    private val LOCAL_HEIGHT_CONNECTING = 100
    // Local preview screen position after call is connected.
    private val LOCAL_X_CONNECTED = 72
    private val LOCAL_Y_CONNECTED = 72
    private val LOCAL_WIDTH_CONNECTED = 25
    private val LOCAL_HEIGHT_CONNECTED = 25
    // Remote video screen position
    private val REMOTE_X = 0
    private val REMOTE_Y = 0
    private val REMOTE_WIDTH = 100
    private val REMOTE_HEIGHT = 100
  }
}
