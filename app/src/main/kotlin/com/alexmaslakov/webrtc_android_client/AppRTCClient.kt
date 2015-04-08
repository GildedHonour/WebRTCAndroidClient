/*
 * libjingle
 * Copyright 2013 Google Inc.
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

import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * AppRTCClient is the interface representing an AppRTC client.
 */
public trait AppRTCClient {

  /**
   * Struct holding the connection parameters of an AppRTC room.
   */
  public class RoomConnectionParameters(
    val roomUrl: String,
    val roomId: String,
    val loopback: Boolean
  )

  /**
   * Asynchronously connect to an AppRTC room URL using supplied connection
   * parameters. Once connection is established onConnectedToRoom()
   * callback with room parameters is invoked.
   */
  public fun connectToRoom(connectionParameters: RoomConnectionParameters)

  /**
   * Send offer SDP to the other participant.
   */
  public fun sendOfferSdp(sdp: SessionDescription)

  /**
   * Send answer SDP to the other participant.
   */
  public fun sendAnswerSdp(sdp: SessionDescription)

  /**
   * Send Ice candidate to the other participant.
   */
  public fun sendLocalIceCandidate(candidate: IceCandidate)

  /**
   * Disconnect from room.
   */
  public fun disconnectFromRoom()

  /**
   * Struct holding the signaling parameters of an AppRTC room.
   */
  public class SignalingParameters(
    val iceServers: List<PeerConnection.IceServer>,
    val initiator: Boolean,
    val pcConstraints: MediaConstraints,
    val videoConstraints: MediaConstraints,
    val audioConstraints: MediaConstraints,
    val clientId: String,
    val wssUrl: String,
    val wssPostUrl: String,
    val offerSdp: SessionDescription?,
    val iceCandidates: List<IceCandidate>?
  )

  /**
   * Callback interface for messages delivered on signaling channel.

   *
   * Methods are guaranteed to be invoked on the UI thread of |activity|.
   */
  public trait SignalingEvents {
    /**
     * Callback fired once the room's signaling parameters
     * SignalingParameters are extracted.
     */
    public fun onConnectedToRoom(params: SignalingParameters)

    /**
     * Callback fired once remote SDP is received.
     */
    public fun onRemoteDescription(sdp: SessionDescription)

    /**
     * Callback fired once remote Ice candidate is received.
     */
    public fun onRemoteIceCandidate(candidate: IceCandidate)

    /**
     * Callback fired once channel is closed.
     */
    public fun onChannelClose()

    /**
     * Callback fired once channel error happened.
     */
    public fun onChannelError(description: String)
  }
}
