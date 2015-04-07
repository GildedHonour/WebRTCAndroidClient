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

import com.alexmaslakov.webrtc_android_client.RoomParametersFetcher.RoomParametersFetcherEvents
import com.alexmaslakov.webrtc_android_client.WebSocketChannelClient.WebSocketChannelEvents
import com.alexmaslakov.webrtc_android_client.WebSocketChannelClient.WebSocketConnectionState
import com.alexmaslakov.webrtc_android_client.util.AsyncHttpURLConnection
import com.alexmaslakov.webrtc_android_client.util.AsyncHttpURLConnection.AsyncHttpEvents
import com.alexmaslakov.webrtc_android_client.util.LooperExecutor

import android.util.Log
import com.alexmaslakov.webrtc_android_client.util

import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Negotiates signaling for chatting with apprtc.appspot.com "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.

 *
 * To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
public class WebSocketRTCClient(
  private val events: AppRTCClient.SignalingEvents,
  private val executor: util.LooperExecutor
): AppRTCClient, WebSocketChannelEvents {

  private enum class ConnectionState {
    NEW
    CONNECTED
    CLOSED
    ERROR
  }

  private enum class MessageType {
    MESSAGE
    LEAVE
  }

  private var initiator: Boolean = false
  private var wsClient: WebSocketChannelClient? = null
  private var roomState: ConnectionState? = null
  private var connectionParameters: AppRTCClient.RoomConnectionParameters? = null
  private var messageUrl: String? = null
  private var leaveUrl: String? = null

  {
    roomState = ConnectionState.NEW
    executor.requestStart()
  }

  // --------------------------------------------------------------------
  // AppRTCClient interface implementation.
  // Asynchronously connect to an AppRTC room URL using supplied connection
  // parameters, retrieves room parameters and connect to WebSocket server.
  override fun connectToRoom(connectionParameters: AppRTCClient.RoomConnectionParameters) {
    this.connectionParameters = connectionParameters
    executor.execute(object : Runnable {
      override fun run() {
        connectToRoomInternal()
      }
    })
  }

  override fun disconnectFromRoom() {
    executor.execute(object : Runnable {
      override fun run() {
        disconnectFromRoomInternal()
      }
    })
    executor.requestStop()
  }

  // Connects to room - function runs on a local looper thread.
  private fun connectToRoomInternal() {
    val connectionUrl = getConnectionUrl(connectionParameters)
    Log.d(TAG, "Connect to room: " + connectionUrl)
    roomState = ConnectionState.NEW
    wsClient = WebSocketChannelClient(executor, this)

    val callbacks = object : RoomParametersFetcherEvents {
      override fun onSignalingParametersReady(params: AppRTCClient.SignalingParameters) {
        this@WebSocketRTCClient.executor.execute(object : Runnable {
          override fun run() {
            this@WebSocketRTCClient.signalingParametersReady(params)
          }
        })
      }

      override fun onSignalingParametersError(description: String) {
        this@WebSocketRTCClient.reportError(description)
      }
    }

    RoomParametersFetcher(connectionUrl, null, callbacks).makeRequest()
  }

  // Disconnect from room and send bye messages - runs on a local looper thread.
  private fun disconnectFromRoomInternal() {
    Log.d(TAG, "Disconnect. Room state: " + roomState)
    if (roomState == ConnectionState.CONNECTED) {
      Log.d(TAG, "Closing room.")
      sendPostMessage(MessageType.LEAVE, leaveUrl, null)
    }
    roomState = ConnectionState.CLOSED
    if (wsClient != null) {
      wsClient!!.disconnect(true)
    }
  }

  // Helper functions to get connection, post message and leave message URLs
  private fun getConnectionUrl(connectionParameters: AppRTCClient.RoomConnectionParameters): String {
    return connectionParameters.roomUrl + "/" + ROOM_JOIN + "/" + connectionParameters.roomId
  }

  private fun getMessageUrl(connectionParameters: AppRTCClient.RoomConnectionParameters, signalingParameters: AppRTCClient.SignalingParameters): String {
    return connectionParameters.roomUrl + "/" + ROOM_MESSAGE + "/" + connectionParameters.roomId + "/" + signalingParameters.clientId
  }

  private fun getLeaveUrl(connectionParameters: AppRTCClient.RoomConnectionParameters, signalingParameters: AppRTCClient.SignalingParameters): String {
    return connectionParameters.roomUrl + "/" + ROOM_LEAVE + "/" + connectionParameters.roomId + "/" + signalingParameters.clientId
  }

  // Callback issued when room parameters are extracted. Runs on local
  // looper thread.
  private fun signalingParametersReady(signalingParameters: AppRTCClient.SignalingParameters) {
    Log.d(TAG, "Room connection completed.")
    if (connectionParameters!!.loopback && (!signalingParameters.initiator || signalingParameters.offerSdp != null)) {
      reportError("Loopback room is busy.")
      return
    }
    if (!connectionParameters!!.loopback && !signalingParameters.initiator && signalingParameters.offerSdp == null) {
      Log.w(TAG, "No offer SDP in room response.")
    }
    initiator = signalingParameters.initiator
    messageUrl = getMessageUrl(connectionParameters!!, signalingParameters)
    leaveUrl = getLeaveUrl(connectionParameters!!, signalingParameters)
    Log.d(TAG, "Message URL: " + messageUrl)
    Log.d(TAG, "Leave URL: " + leaveUrl)
    roomState = ConnectionState.CONNECTED

    // Fire connection and signaling parameters events.
    events.onConnectedToRoom(signalingParameters)

    // Connect and register WebSocket client.
    wsClient!!.connect(signalingParameters.wssUrl, signalingParameters.wssPostUrl)
    wsClient!!.register(connectionParameters!!.roomId, signalingParameters.clientId)
  }

  // Send local offer SDP to the other participant.
  override fun sendOfferSdp(sdp: SessionDescription) {
    executor.execute(object : Runnable {
      override fun run() {
        if (roomState != ConnectionState.CONNECTED) {
          reportError("Sending offer SDP in non connected state.")
          return
        }
        val json = JSONObject()
        jsonPut(json, "sdp", sdp.description)
        jsonPut(json, "type", "offer")
        sendPostMessage(MessageType.MESSAGE, messageUrl!!, json.toString())
        if (connectionParameters!!.loopback) {
          // In loopback mode rename this offer to answer and route it back.
          val sdpAnswer = SessionDescription(SessionDescription.Type.fromCanonicalForm("answer"), sdp.description)
          events.onRemoteDescription(sdpAnswer)
        }
      }
    })
  }

  // Send local answer SDP to the other participant.
  override fun sendAnswerSdp(sdp: SessionDescription) {
    executor.execute(object : Runnable {
      override fun run() {
        if (connectionParameters!!.loopback) {
          Log.e(TAG, "Sending answer in loopback mode.")
          return
        }
        val json = JSONObject()
        jsonPut(json, "sdp", sdp.description)
        jsonPut(json, "type", "answer")
        wsClient!!.send(json.toString())
      }
    })
  }

  // Send Ice candidate to the other participant.
  override fun sendLocalIceCandidate(candidate: IceCandidate) {
    executor.execute(object : Runnable {
      override fun run() {
        val json = JSONObject()
        jsonPut(json, "type", "candidate")
        jsonPut(json, "label", candidate.sdpMLineIndex)
        jsonPut(json, "id", candidate.sdpMid)
        jsonPut(json, "candidate", candidate.sdp)
        if (initiator) {
          // Call initiator sends ice candidates to GAE server.
          if (roomState != ConnectionState.CONNECTED) {
            reportError("Sending ICE candidate in non connected state.")
            return
          }

          sendPostMessage(MessageType.MESSAGE, messageUrl!!, json.toString())
          if (connectionParameters!!.loopback) {
            events.onRemoteIceCandidate(candidate)
          }
        } else {
          // Call receiver sends ice candidates to websocket server.
          wsClient!!.send(json.toString())
        }
      }
    })
  }

  // --------------------------------------------------------------------
  // WebSocketChannelEvents interface implementation.
  // All events are called by WebSocketChannelClient on a local looper thread
  // (passed to WebSocket client constructor).
  override fun onWebSocketMessage(message: String) {
    if (wsClient!!.state != WebSocketConnectionState.REGISTERED) {
      Log.e(TAG, "Got WebSocket message in non registered state.")
      return
    }
    try {
      var json = JSONObject(message)
      val msgText = json.getString("msg")
      val errorText = json.optString("error")
      if (msgText.length() > 0) {
        json = JSONObject(msgText)
        val type = json.optString("type")
        if (type == "candidate") {
          val candidate = IceCandidate(json.getString("id"), json.getInt("label"), json.getString("candidate"))
          events.onRemoteIceCandidate(candidate)
        } else if (type == "answer") {
          if (initiator) {
            val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"))
            events.onRemoteDescription(sdp)
          } else {
            reportError("Received answer for call initiator: " + message)
          }
        } else if (type == "offer") {
          if (!initiator) {
            val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), json.getString("sdp"))
            events.onRemoteDescription(sdp)
          } else {
            reportError("Received offer for call receiver: " + message)
          }
        } else if (type == "bye") {
          events.onChannelClose()
        } else {
          reportError("Unexpected WebSocket message: " + message)
        }
      } else {
        if (errorText != null && errorText.length() > 0) {
          reportError("WebSocket error message: " + errorText)
        } else {
          reportError("Unexpected WebSocket message: " + message)
        }
      }
    } catch (e: JSONException) {
      reportError("WebSocket message JSON parsing error: " + e.toString())
    }

  }

  override fun onWebSocketClose() {
    events.onChannelClose()
  }

  override fun onWebSocketError(description: String) {
    reportError("WebSocket error: " + description)
  }

  // --------------------------------------------------------------------
  // Helper functions.
  private fun reportError(errorMessage: String) {
    Log.e(TAG, errorMessage)
    executor.execute(object : Runnable {
      override fun run() {
        if (roomState != ConnectionState.ERROR) {
          roomState = ConnectionState.ERROR
          events.onChannelError(errorMessage)
        }
      }
    })
  }

  // Send SDP or ICE candidate to a room server.
  private fun sendPostMessage(messageType: MessageType, url: String, message: String?) {
    var logInfo = url
    if (message != null) {
      logInfo += ". Message: " + message
    }
    Log.d(TAG, "C->GAE: " + logInfo)
    val httpConnection = util.AsyncHttpURLConnection("POST", url, message, object : util.AsyncHttpURLConnection.AsyncHttpEvents {
      override fun onHttpError(errorMessage: String) {
        reportError("GAE POST error: " + errorMessage)
      }

      override fun onHttpComplete(response: String) {
        if (messageType == MessageType.MESSAGE) {
          try {
            val roomJson = JSONObject(response)
            val result = roomJson.getString("result")
            if (result != "SUCCESS") {
              reportError("GAE POST error: " + result)
            }
          } catch (e: JSONException) {
            reportError("GAE POST JSON error: " + e.toString())
          }

        }
      }
    })
    httpConnection.send()
  }

  companion object {
    private val TAG = "WSRTCClient"
    private val ROOM_JOIN = "join"
    private val ROOM_MESSAGE = "message"
    private val ROOM_LEAVE = "leave"

    // Put a |key|->|value| mapping in |json|.
    private fun jsonPut(json: JSONObject, key: String, value: Any) {
      try {
        json.put(key, value)
      } catch (e: JSONException) {
        throw RuntimeException(e)
      }

    }
  }
}
