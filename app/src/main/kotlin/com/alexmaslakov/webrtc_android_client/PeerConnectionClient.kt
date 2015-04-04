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

import android.content.Context
import android.opengl.EGLContext
import android.util.Log

import com.alexmaslakov.webrtc_android_client.AppRTCClient.SignalingParameters
import com.alexmaslakov.webrtc_android_client.util.LooperExecutor
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaCodecVideoEncoder
import org.webrtc.MediaConstraints
import org.webrtc.MediaConstraints.KeyValuePair
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.StatsObserver
import org.webrtc.StatsReport
import org.webrtc.VideoCapturerAndroid
import org.webrtc.VideoRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

import java.util.LinkedList
import java.util.Timer
import java.util.TimerTask
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Peer connection client implementation.

 *
 * All public methods are routed to local looper thread.
 * All PeerConnectionEvents callbacks are invoked from the same looper thread.
 */
public class PeerConnectionClient {

  private val executor: LooperExecutor
  private var factory: PeerConnectionFactory? = null
  private var peerConnection: PeerConnection? = null
  private var videoSource: VideoSource? = null
  private var videoCallEnabled = true
  private var preferIsac = false
  private var preferH264 = false
  private var videoSourceStopped = false
  private var isError = false
  private val statsTimer = Timer()
  private val pcObserver = PCObserver()
  private val sdpObserver = SDPObserver()
  private var localRender: VideoRenderer.Callbacks? = null
  private var remoteRender: VideoRenderer.Callbacks? = null
  private var signalingParameters: SignalingParameters? = null
  private var pcConstraints: MediaConstraints? = null
  private var videoConstraints: MediaConstraints? = null
  private var audioConstraints: MediaConstraints? = null
  private var sdpMediaConstraints: MediaConstraints? = null
  private var peerConnectionParameters: PeerConnectionParameters? = null
  // Queued remote ICE candidates are consumed only after both local and
  // remote descriptions are set. Similarly local ICE candidates are sent to
  // remote peer after both local and remote description are set.
  private var queuedRemoteCandidates: LinkedList<IceCandidate>? = null
  private var events: PeerConnectionEvents? = null
  private var isInitiator: Boolean = false
  private var localSdp: SessionDescription? = null // either offer or answer SDP
  private var mediaStream: MediaStream? = null
  private var numberOfCameras: Int = 0
  private var videoCapturer: VideoCapturerAndroid? = null
  // enableVideo is set to true if video should be rendered and sent.
  private var renderVideo = true
  private var localVideoTrack: VideoTrack? = null
  private var remoteVideoTrack: VideoTrack? = null

  /**
   * Peer connection parameters.
   */
  public class PeerConnectionParameters(public val videoCallEnabled: Boolean, public val loopback: Boolean, public val videoWidth: Int, public val videoHeight: Int, public val videoFps: Int, public val videoStartBitrate: Int, public val videoCodec: String?, public val videoCodecHwAcceleration: Boolean, public val audioStartBitrate: Int, public val audioCodec: String?, public val cpuOveruseDetection: Boolean)

  /**
   * Peer connection events.
   */
  public trait PeerConnectionEvents {
    /**
     * Callback fired once local SDP is created and set.
     */
    public fun onLocalDescription(sdp: SessionDescription)

    /**
     * Callback fired once local Ice candidate is generated.
     */
    public fun onIceCandidate(candidate: IceCandidate)

    /**
     * Callback fired once connection is established (IceConnectionState is
     * CONNECTED).
     */
    public fun onIceConnected()

    /**
     * Callback fired once connection is closed (IceConnectionState is
     * DISCONNECTED).
     */
    public fun onIceDisconnected()

    /**
     * Callback fired once peer connection is closed.
     */
    public fun onPeerConnectionClosed()

    /**
     * Callback fired once peer connection statistics is ready.
     */
    public fun onPeerConnectionStatsReady(reports: Array<StatsReport>)

    /**
     * Callback fired once peer connection error happened.
     */
    public fun onPeerConnectionError(description: String)
  }

  {
    executor = LooperExecutor()
  }

  public fun createPeerConnectionFactory(context: Context, renderEGLContext: EGLContext, peerConnectionParameters: PeerConnectionParameters, events: PeerConnectionEvents) {
    this.peerConnectionParameters = peerConnectionParameters
    this.events = events
    videoCallEnabled = peerConnectionParameters.videoCallEnabled
    executor.requestStart()
    executor.execute(object : Runnable {
      override fun run() {
        createPeerConnectionFactoryInternal(context, renderEGLContext)
      }
    })
  }

  public fun createPeerConnection(localRender: VideoRenderer.Callbacks, remoteRender: VideoRenderer.Callbacks, signalingParameters: SignalingParameters) {
    if (peerConnectionParameters == null) {
      Log.e(TAG, "Creating peer connection without initializing factory.")
      return
    }
    this.localRender = localRender
    this.remoteRender = remoteRender
    this.signalingParameters = signalingParameters
    executor.execute(object : Runnable {
      override fun run() {
        createMediaConstraintsInternal()
        createPeerConnectionInternal()
      }
    })
  }

  public fun close() {
    executor.execute(object : Runnable {
      override fun run() {
        closeInternal()
      }
    })
    executor.requestStop()
  }

  private fun createPeerConnectionFactoryInternal(context: Context, renderEGLContext: EGLContext) {
    Log.d(TAG, "Create peer connection factory with EGLContext " + renderEGLContext + ". Use video: " + peerConnectionParameters!!.videoCallEnabled)
    isError = false
    // Check if VP9 is used by default.
    if (videoCallEnabled && peerConnectionParameters!!.videoCodec != null && peerConnectionParameters!!.videoCodec == VIDEO_CODEC_VP9) {
      PeerConnectionFactory.initializeFieldTrials(FIELD_TRIAL_VP9)
    } else {
      PeerConnectionFactory.initializeFieldTrials(null)
    }
    // Check if H.264 is used by default.
    preferH264 = false
    if (videoCallEnabled && peerConnectionParameters!!.videoCodec != null && peerConnectionParameters!!.videoCodec == VIDEO_CODEC_H264) {
      preferH264 = true
    }
    // Check if ISAC is used by default.
    preferIsac = false
    if (peerConnectionParameters!!.audioCodec != null && peerConnectionParameters!!.audioCodec == AUDIO_CODEC_ISAC) {
      preferIsac = true
    }
    if (!PeerConnectionFactory.initializeAndroidGlobals(context, true, true, peerConnectionParameters!!.videoCodecHwAcceleration, renderEGLContext)) {
      events!!.onPeerConnectionError("Failed to initializeAndroidGlobals")
    }
    factory = PeerConnectionFactory()
    configureFactory(factory)
    Log.d(TAG, "Peer connection factory created.")
  }

  /**
   * Hook where tests can provide additional configuration for the factory.
   */
  protected fun configureFactory(factory: PeerConnectionFactory) {
  }

  private fun createMediaConstraintsInternal() {
    // Create peer connection constraints.
    pcConstraints = MediaConstraints()
    // Enable DTLS for normal calls and disable for loopback calls.
    if (peerConnectionParameters!!.loopback) {
      pcConstraints!!.optional.add(MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"))
    } else {
      pcConstraints!!.optional.add(MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"))
    }

    // Check if there is a camera on device and disable video call if not.
    numberOfCameras = VideoCapturerAndroid.getDeviceCount()
    if (numberOfCameras == 0) {
      Log.w(TAG, "No camera on device. Switch to audio only call.")
      videoCallEnabled = false
    }
    // Create video constraints if video call is enabled.
    if (videoCallEnabled) {
      videoConstraints = MediaConstraints()
      var videoWidth = peerConnectionParameters!!.videoWidth
      var videoHeight = peerConnectionParameters!!.videoHeight

      // If VP8 HW video encoder is supported and video resolution is not
      // specified force it to HD.
      if ((videoWidth == 0 || videoHeight == 0) && peerConnectionParameters!!.videoCodecHwAcceleration && MediaCodecVideoEncoder.isVp8HwSupported()) {
        videoWidth = HD_VIDEO_WIDTH
        videoHeight = HD_VIDEO_HEIGHT
      }

      // Add video resolution constraints.
      if (videoWidth > 0 && videoHeight > 0) {
        videoWidth = Math.min(videoWidth, MAX_VIDEO_WIDTH)
        videoHeight = Math.min(videoHeight, MAX_VIDEO_HEIGHT)
        videoConstraints!!.mandatory.add(KeyValuePair(MIN_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)))
        videoConstraints!!.mandatory.add(KeyValuePair(MAX_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)))
        videoConstraints!!.mandatory.add(KeyValuePair(MIN_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)))
        videoConstraints!!.mandatory.add(KeyValuePair(MAX_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)))
      }

      // Add fps constraints.
      var videoFps = peerConnectionParameters!!.videoFps
      if (videoFps > 0) {
        videoFps = Math.min(videoFps, MAX_VIDEO_FPS)
        videoConstraints!!.mandatory.add(KeyValuePair(MIN_VIDEO_FPS_CONSTRAINT, Integer.toString(videoFps)))
        videoConstraints!!.mandatory.add(KeyValuePair(MAX_VIDEO_FPS_CONSTRAINT, Integer.toString(videoFps)))
      }
    }

    // Create audio constraints.
    audioConstraints = MediaConstraints()

    // Create SDP constraints.
    sdpMediaConstraints = MediaConstraints()
    sdpMediaConstraints!!.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    if (videoCallEnabled || peerConnectionParameters!!.loopback) {
      sdpMediaConstraints!!.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    } else {
      sdpMediaConstraints!!.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }
  }

  private fun createPeerConnectionInternal() {
    if (factory == null || isError) {
      Log.e(TAG, "Peerconnection factory is not created")
      return
    }
    Log.d(TAG, "Create peer connection")
    Log.d(TAG, "PCConstraints: " + pcConstraints!!.toString())
    if (videoConstraints != null) {
      Log.d(TAG, "VideoConstraints: " + videoConstraints!!.toString())
    }
    queuedRemoteCandidates = LinkedList<IceCandidate>()

    peerConnection = factory!!.createPeerConnection(signalingParameters!!.iceServers, pcConstraints, pcObserver)
    isInitiator = false

    // Uncomment to get ALL WebRTC tracing and SENSITIVE libjingle logging.
    // NOTE: this _must_ happen while |factory| is alive!
    // Logging.enableTracing(
    //     "logcat:",
    //     EnumSet.of(Logging.TraceLevel.TRACE_ALL),
    //     Logging.Severity.LS_SENSITIVE);

    mediaStream = factory!!.createLocalMediaStream("ARDAMS")
    if (videoCallEnabled) {
      var cameraDeviceName = VideoCapturerAndroid.getDeviceName(0)
      val frontCameraDeviceName = VideoCapturerAndroid.getNameOfFrontFacingDevice()
      if (numberOfCameras > 1 && frontCameraDeviceName != null) {
        cameraDeviceName = frontCameraDeviceName
      }
      Log.d(TAG, "Opening camera: " + cameraDeviceName)
      videoCapturer = VideoCapturerAndroid.create(cameraDeviceName)
      mediaStream!!.addTrack(createVideoTrack(videoCapturer))
    }

    mediaStream!!.addTrack(factory!!.createAudioTrack(AUDIO_TRACK_ID, factory!!.createAudioSource(audioConstraints)))
    peerConnection!!.addStream(mediaStream)

    Log.d(TAG, "Peer connection created.")
  }

  private fun closeInternal() {
    Log.d(TAG, "Closing peer connection.")
    statsTimer.cancel()
    if (peerConnection != null) {
      peerConnection!!.dispose()
      peerConnection = null
    }
    if (videoSource != null) {
      videoSource!!.dispose()
      videoSource = null
    }
    Log.d(TAG, "Closing peer connection factory.")
    if (factory != null) {
      factory!!.dispose()
      factory = null
    }
    Log.d(TAG, "Closing peer connection done.")
    events!!.onPeerConnectionClosed()
  }

  public fun isHDVideo(): Boolean {
    if (!videoCallEnabled) {
      return false
    }
    var minWidth = 0
    var minHeight = 0
    for (keyValuePair in videoConstraints!!.mandatory) {
      if (keyValuePair.getKey() == "minWidth") {
        try {
          minWidth = Integer.parseInt(keyValuePair.getValue())
        } catch (e: NumberFormatException) {
          Log.e(TAG, "Can not parse video width from video constraints")
        }

      } else if (keyValuePair.getKey() == "minHeight") {
        try {
          minHeight = Integer.parseInt(keyValuePair.getValue())
        } catch (e: NumberFormatException) {
          Log.e(TAG, "Can not parse video height from video constraints")
        }

      }
    }
    if (minWidth * minHeight >= 1280 * 720) {
      return true
    } else {
      return false
    }
  }

  private fun getStats() {
    if (peerConnection == null || isError) {
      return
    }
    val success = peerConnection!!.getStats(object : StatsObserver {
      override fun onComplete(reports: Array<StatsReport>) {
        events!!.onPeerConnectionStatsReady(reports)
      }
    }, null)
    if (!success) {
      Log.e(TAG, "getStats() returns false!")
    }
  }

  public fun enableStatsEvents(enable: Boolean, periodMs: Int) {
    if (enable) {
      statsTimer.schedule(object : TimerTask() {
        override fun run() {
          executor.execute(object : Runnable {
            override fun run() {
              getStats()
            }
          })
        }
      }, 0, periodMs.toLong())
    } else {
      statsTimer.cancel()
    }
  }

  public fun setVideoEnabled(enable: Boolean) {
    executor.execute(object : Runnable {
      override fun run() {
        renderVideo = enable
        if (localVideoTrack != null) {
          localVideoTrack!!.setEnabled(renderVideo)
        }
        if (remoteVideoTrack != null) {
          remoteVideoTrack!!.setEnabled(renderVideo)
        }
      }
    })
  }

  public fun createOffer() {
    executor.execute(object : Runnable {
      override fun run() {
        if (peerConnection != null && !isError) {
          Log.d(TAG, "PC Create OFFER")
          isInitiator = true
          peerConnection!!.createOffer(sdpObserver, sdpMediaConstraints)
        }
      }
    })
  }

  public fun createAnswer() {
    executor.execute(object : Runnable {
      override fun run() {
        if (peerConnection != null && !isError) {
          Log.d(TAG, "PC create ANSWER")
          isInitiator = false
          peerConnection!!.createAnswer(sdpObserver, sdpMediaConstraints)
        }
      }
    })
  }

  public fun addRemoteIceCandidate(candidate: IceCandidate) {
    executor.execute(object : Runnable {
      override fun run() {
        if (peerConnection != null && !isError) {
          if (queuedRemoteCandidates != null) {
            queuedRemoteCandidates!!.add(candidate)
          } else {
            peerConnection!!.addIceCandidate(candidate)
          }
        }
      }
    })
  }

  public fun setRemoteDescription(sdp: SessionDescription) {
    executor.execute(object : Runnable {
      override fun run() {
        if (peerConnection == null || isError) {
          return
        }
        var sdpDescription = sdp.description
        if (preferIsac) {
          sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true)
        }
        if (videoCallEnabled && preferH264) {
          sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_H264, false)
        }
        if (videoCallEnabled && peerConnectionParameters!!.videoStartBitrate > 0) {
          sdpDescription = setStartBitrate(VIDEO_CODEC_VP8, true, sdpDescription, peerConnectionParameters!!.videoStartBitrate)
          sdpDescription = setStartBitrate(VIDEO_CODEC_VP9, true, sdpDescription, peerConnectionParameters!!.videoStartBitrate)
          sdpDescription = setStartBitrate(VIDEO_CODEC_H264, true, sdpDescription, peerConnectionParameters!!.videoStartBitrate)
        }
        if (peerConnectionParameters!!.audioStartBitrate > 0) {
          sdpDescription = setStartBitrate(AUDIO_CODEC_OPUS, false, sdpDescription, peerConnectionParameters!!.audioStartBitrate)
        }
        Log.d(TAG, "Set remote SDP.")
        val sdpRemote = SessionDescription(sdp.type, sdpDescription)
        peerConnection!!.setRemoteDescription(sdpObserver, sdpRemote)
      }
    })
  }

  public fun stopVideoSource() {
    executor.execute(object : Runnable {
      override fun run() {
        if (videoSource != null && !videoSourceStopped) {
          Log.d(TAG, "Stop video source.")
          videoSource!!.stop()
          videoSourceStopped = true
        }
      }
    })
  }

  public fun startVideoSource() {
    executor.execute(object : Runnable {
      override fun run() {
        if (videoSource != null && videoSourceStopped) {
          Log.d(TAG, "Restart video source.")
          videoSource!!.restart()
          videoSourceStopped = false
        }
      }
    })
  }

  private fun reportError(errorMessage: String) {
    Log.e(TAG, "Peerconnection error: " + errorMessage)
    executor.execute(object : Runnable {
      override fun run() {
        if (!isError) {
          events!!.onPeerConnectionError(errorMessage)
          isError = true
        }
      }
    })
  }

  private fun createVideoTrack(capturer: VideoCapturerAndroid): VideoTrack {
    videoSource = factory!!.createVideoSource(capturer, videoConstraints)

    localVideoTrack = factory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
    localVideoTrack!!.setEnabled(renderVideo)
    localVideoTrack!!.addRenderer(VideoRenderer(localRender))
    return localVideoTrack
  }

  private fun drainCandidates() {
    if (queuedRemoteCandidates != null) {
      Log.d(TAG, "Add " + queuedRemoteCandidates!!.size() + " remote candidates")
      for (candidate in queuedRemoteCandidates!!) {
        peerConnection!!.addIceCandidate(candidate)
      }
      queuedRemoteCandidates = null
    }
  }

  private fun switchCameraInternal() {
    if (!videoCallEnabled || numberOfCameras < 2) {
      return   // No video is sent or only one camera is available.
    }
    Log.d(TAG, "Switch camera")
    videoCapturer!!.switchCamera()
  }

  public fun switchCamera() {
    executor.execute(object : Runnable {
      override fun run() {
        if (peerConnection != null && !isError) {
          switchCameraInternal()
        }
      }
    })
  }

  // Implementation detail: observe ICE & stream changes and react accordingly.
  private inner class PCObserver : PeerConnection.Observer {
    override fun onIceCandidate(candidate: IceCandidate) {
      executor.execute(object : Runnable {
        override fun run() {
          events!!.onIceCandidate(candidate)
        }
      })
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState) {
      Log.d(TAG, "SignalingState: " + newState)
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
      executor.execute(object : Runnable {
        override fun run() {
          Log.d(TAG, "IceConnectionState: " + newState)
          if (newState == IceConnectionState.CONNECTED) {
            events!!.onIceConnected()
          } else if (newState == IceConnectionState.DISCONNECTED) {
            events!!.onIceDisconnected()
          } else if (newState == IceConnectionState.FAILED) {
            reportError("ICE connection failed.")
          }
        }
      })
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
      Log.d(TAG, "IceGatheringState: " + newState)
    }

    override fun onAddStream(stream: MediaStream) {
      executor.execute(object : Runnable {
        override fun run() {
          if (peerConnection == null || isError) {
            return
          }
          if (stream.audioTracks.size() > 1 || stream.videoTracks.size() > 1) {
            reportError("Weird-looking stream: " + stream)
            return
          }
          if (stream.videoTracks.size() == 1) {
            remoteVideoTrack = stream.videoTracks.get(0)
            remoteVideoTrack!!.setEnabled(renderVideo)
            remoteVideoTrack!!.addRenderer(VideoRenderer(remoteRender))
          }
        }
      })
    }

    override fun onRemoveStream(stream: MediaStream) {
      executor.execute(object : Runnable {
        override fun run() {
          if (peerConnection == null || isError) {
            return
          }
          remoteVideoTrack = null
          stream.videoTracks.get(0).dispose()
        }
      })
    }

    override fun onDataChannel(dc: DataChannel) {
      reportError("AppRTC doesn't use data channels, but got: " + dc.label() + " anyway!")
    }

    override fun onRenegotiationNeeded() {
      // No need to do anything; AppRTC follows a pre-agreed-upon
      // signaling/negotiation protocol.
    }
  }

  // Implementation detail: handle offer creation/signaling and answer setting,
  // as well as adding remote ICE candidates once the answer SDP is set.
  private inner class SDPObserver : SdpObserver {
    override fun onCreateSuccess(origSdp: SessionDescription) {
      if (localSdp != null) {
        reportError("Multiple SDP create.")
        return
      }
      var sdpDescription = origSdp.description
      if (preferIsac) {
        sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true)
      }
      if (videoCallEnabled && preferH264) {
        sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_H264, false)
      }
      val sdp = SessionDescription(origSdp.type, sdpDescription)
      localSdp = sdp
      executor.execute(object : Runnable {
        override fun run() {
          if (peerConnection != null && !isError) {
            Log.d(TAG, "Set local SDP from " + sdp.type)
            peerConnection!!.setLocalDescription(sdpObserver, sdp)
          }
        }
      })
    }

    override fun onSetSuccess() {
      executor.execute(object : Runnable {
        override fun run() {
          if (peerConnection == null || isError) {
            return
          }
          if (isInitiator) {
            // For offering peer connection we first create offer and set
            // local SDP, then after receiving answer set remote SDP.
            if (peerConnection!!.getRemoteDescription() == null) {
              // We've just set our local SDP so time to send it.
              Log.d(TAG, "Local SDP set succesfully")
              events!!.onLocalDescription(localSdp)
            } else {
              // We've just set remote description, so drain remote
              // and send local ICE candidates.
              Log.d(TAG, "Remote SDP set succesfully")
              drainCandidates()
            }
          } else {
            // For answering peer connection we set remote SDP and then
            // create answer and set local SDP.
            if (peerConnection!!.getLocalDescription() != null) {
              // We've just set our local SDP so time to send it, drain
              // remote and send local ICE candidates.
              Log.d(TAG, "Local SDP set succesfully")
              events!!.onLocalDescription(localSdp)
              drainCandidates()
            } else {
              // We've just set remote SDP - do nothing for now -
              // answer will be created soon.
              Log.d(TAG, "Remote SDP set succesfully")
            }
          }
        }
      })
    }

    override fun onCreateFailure(error: String) {
      reportError("createSDP error: " + error)
    }

    override fun onSetFailure(error: String) {
      reportError("setSDP error: " + error)
    }
  }

  companion object {
    public val VIDEO_TRACK_ID: String = "ARDAMSv0"
    public val AUDIO_TRACK_ID: String = "ARDAMSa0"
    private val TAG = "PCRTCClient"
    private val FIELD_TRIAL_VP9 = "WebRTC-SupportVP9/Enabled/"
    private val VIDEO_CODEC_VP8 = "VP8"
    private val VIDEO_CODEC_VP9 = "VP9"
    private val VIDEO_CODEC_H264 = "H264"
    private val AUDIO_CODEC_OPUS = "opus"
    private val AUDIO_CODEC_ISAC = "ISAC"
    private val VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate"
    private val AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate"
    private val MAX_VIDEO_WIDTH_CONSTRAINT = "maxWidth"
    private val MIN_VIDEO_WIDTH_CONSTRAINT = "minWidth"
    private val MAX_VIDEO_HEIGHT_CONSTRAINT = "maxHeight"
    private val MIN_VIDEO_HEIGHT_CONSTRAINT = "minHeight"
    private val MAX_VIDEO_FPS_CONSTRAINT = "maxFrameRate"
    private val MIN_VIDEO_FPS_CONSTRAINT = "minFrameRate"
    private val DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement"
    private val HD_VIDEO_WIDTH = 1280
    private val HD_VIDEO_HEIGHT = 720
    private val MAX_VIDEO_WIDTH = 1280
    private val MAX_VIDEO_HEIGHT = 1280
    private val MAX_VIDEO_FPS = 30

    private fun setStartBitrate(codec: String, isVideoCodec: Boolean, sdpDescription: String, bitrateKbps: Int): String {
      val lines = sdpDescription.split("\r\n")
      var rtpmapLineIndex = -1
      var sdpFormatUpdated = false
      var codecRtpMap: String? = null
      // Search for codec rtpmap in format
      // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
      var regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$"
      var codecPattern = Pattern.compile(regex)
      for (i in lines.indices) {
        val codecMatcher = codecPattern.matcher(lines[i])
        if (codecMatcher.matches()) {
          codecRtpMap = codecMatcher.group(1)
          rtpmapLineIndex = i
          break
        }
      }
      if (codecRtpMap == null) {
        Log.w(TAG, "No rtpmap for " + codec + " codec")
        return sdpDescription
      }
      Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex])

      // Check if a=fmtp string already exist in remote SDP for this codec and
      // update it with new bitrate parameter.
      regex = "^a=fmtp:" + codecRtpMap + " \\w+=\\d+.*[\r]?$"
      codecPattern = Pattern.compile(regex)
      for (i in lines.indices) {
        val codecMatcher = codecPattern.matcher(lines[i])
        if (codecMatcher.matches()) {
          Log.d(TAG, "Found " + codec + " " + lines[i])
          if (isVideoCodec) {
            lines[i] += "; " + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps
          } else {
            lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000)
          }
          Log.d(TAG, "Update remote SDP line: " + lines[i])
          sdpFormatUpdated = true
          break
        }
      }

      val newSdpDescription = StringBuilder()
      for (i in lines.indices) {
        newSdpDescription.append(lines[i]).append("\r\n")
        // Append new a=fmtp line if no such line exist for a codec.
        if (!sdpFormatUpdated && i == rtpmapLineIndex) {
          val bitrateSet: String
          if (isVideoCodec) {
            bitrateSet = "a=fmtp:" + codecRtpMap + " " + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps
          } else {
            bitrateSet = "a=fmtp:" + codecRtpMap + " " + AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000)
          }
          Log.d(TAG, "Add remote SDP line: " + bitrateSet)
          newSdpDescription.append(bitrateSet).append("\r\n")
        }

      }
      return newSdpDescription.toString()
    }

    private fun preferCodec(sdpDescription: String, codec: String, isAudio: Boolean): String {
      val lines = sdpDescription.split("\r\n")
      var mLineIndex = -1
      var codecRtpMap: String? = null
      // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
      val regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$"
      val codecPattern = Pattern.compile(regex)
      var mediaDescription = "m=video "
      if (isAudio) {
        mediaDescription = "m=audio "
      }
      run {
        var i = 0
        while ((i < lines.size()) && (mLineIndex == -1 || codecRtpMap == null)) {
          if (lines[i].startsWith(mediaDescription)) {
            mLineIndex = i
            continue
          }
          val codecMatcher = codecPattern.matcher(lines[i])
          if (codecMatcher.matches()) {
            codecRtpMap = codecMatcher.group(1)
            continue
          }
          i++
        }
      }
      if (mLineIndex == -1) {
        Log.w(TAG, "No " + mediaDescription + " line, so can't prefer " + codec)
        return sdpDescription
      }
      if (codecRtpMap == null) {
        Log.w(TAG, "No rtpmap for " + codec)
        return sdpDescription
      }
      Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + ", prefer at " + lines[mLineIndex])
      val origMLineParts = lines[mLineIndex].split(" ")
      if (origMLineParts.size() > 3) {
        val newMLine = StringBuilder()
        var origPartIndex = 0
        // Format is: m=<media> <port> <proto> <fmt> ...
        newMLine.append(origMLineParts[origPartIndex++]).append(" ")
        newMLine.append(origMLineParts[origPartIndex++]).append(" ")
        newMLine.append(origMLineParts[origPartIndex++]).append(" ")
        newMLine.append(codecRtpMap)
        while (origPartIndex < origMLineParts.size()) {
          if (origMLineParts[origPartIndex] != codecRtpMap) {
            newMLine.append(" ").append(origMLineParts[origPartIndex])
          }
          origPartIndex++
        }
        lines[mLineIndex] = newMLine.toString()
        Log.d(TAG, "Change media description: " + lines[mLineIndex])
      } else {
        Log.e(TAG, "Wrong SDP media description format: " + lines[mLineIndex])
      }
      val newSdpDescription = StringBuilder()
      for (line in lines) {
        newSdpDescription.append(line).append("\r\n")
      }
      return newSdpDescription.toString()
    }
  }
}
