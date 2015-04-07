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

import com.alexmaslakov.webrtc_android_client.AppRTCClient.SignalingParameters
import com.alexmaslakov.webrtc_android_client.util.AsyncHttpURLConnection
import com.alexmaslakov.webrtc_android_client.util.AsyncHttpURLConnection.AsyncHttpEvents

import android.util.Log

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedList
import java.util.Scanner

/**
 * AsyncTask that converts an AppRTC room URL into the set of signaling
 * parameters to use with that room.
 */
public class RoomParametersFetcher(private val roomUrl: String, private val roomMessage: String, private val events: RoomParametersFetcher.RoomParametersFetcherEvents) {
  private var httpConnection: AsyncHttpURLConnection? = null

  /**
   * Room parameters fetcher callbacks.
   */
  public trait RoomParametersFetcherEvents {
    /**
     * Callback fired once the room's signaling parameters
     * SignalingParameters are extracted.
     */
    public fun onSignalingParametersReady(params: SignalingParameters)

    /**
     * Callback for room parameters extraction error.
     */
    public fun onSignalingParametersError(description: String)
  }

  public fun makeRequest() {
    Log.d(TAG, "Connecting to room: " + roomUrl)
    httpConnection = AsyncHttpURLConnection("POST", roomUrl, roomMessage, object : AsyncHttpEvents {
      override fun onHttpError(errorMessage: String) {
        Log.e(TAG, "Room connection error: " + errorMessage)
        events.onSignalingParametersError(errorMessage)
      }

      override fun onHttpComplete(response: String) {
        roomHttpResponseParse(response)
      }
    })
    httpConnection!!.send()
  }

  private fun roomHttpResponseParse(response: String) {
    var response = response
    Log.d(TAG, "Room response: " + response)
    try {
      var iceCandidates: LinkedList<IceCandidate>? = null
      var offerSdp: SessionDescription? = null
      var roomJson = JSONObject(response)

      val result = roomJson.getString("result")
      if (result != "SUCCESS") {
        events.onSignalingParametersError("Room response error: " + result)
        return
      }
      response = roomJson.getString("params")
      roomJson = JSONObject(response)
      val roomId = roomJson.getString("room_id")
      val clientId = roomJson.getString("client_id")
      val wssUrl = roomJson.getString("wss_url")
      val wssPostUrl = roomJson.getString("wss_post_url")
      val initiator = (roomJson.getBoolean("is_initiator"))
      if (!initiator) {
        iceCandidates = LinkedList<IceCandidate>()
        val messagesString = roomJson.getString("messages")
        val messages = JSONArray(messagesString)
        for (i in 0..messages.length() - 1) {
          val messageString = messages.getString(i)
          val message = JSONObject(messageString)
          val messageType = message.getString("type")
          Log.d(TAG, "GAE->C #" + i + " : " + messageString)
          if (messageType == "offer") {
            offerSdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(messageType), message.getString("sdp"))
          } else if (messageType == "candidate") {
            val candidate = IceCandidate(message.getString("id"), message.getInt("label"), message.getString("candidate"))
            iceCandidates!!.add(candidate)
          } else {
            Log.e(TAG, "Unknown message: " + messageString)
          }
        }
      }
      Log.d(TAG, "RoomId: " + roomId + ". ClientId: " + clientId)
      Log.d(TAG, "Initiator: " + initiator)
      Log.d(TAG, "WSS url: " + wssUrl)
      Log.d(TAG, "WSS POST url: " + wssPostUrl)

      val iceServers = iceServersFromPCConfigJSON(roomJson.getString("pc_config"))
      var isTurnPresent = false
      for (server in iceServers) {
        Log.d(TAG, "IceServer: " + server)
        if (server.uri.startsWith("turn:")) {
          isTurnPresent = true
          break
        }
      }
      // Request TURN servers.
      if (!isTurnPresent) {
        val turnServers = requestTurnServers(roomJson.getString("turn_url"))
        for (turnServer in turnServers) {
          Log.d(TAG, "TurnServer: " + turnServer)
          iceServers.add(turnServer)
        }
      }

      val pcConstraints = constraintsFromJSON(roomJson.getString("pc_constraints"))
      Log.d(TAG, "pcConstraints: " + pcConstraints)
      val videoConstraints = constraintsFromJSON(getAVConstraints("video", roomJson.getString("media_constraints")))
      Log.d(TAG, "videoConstraints: " + videoConstraints)
      val audioConstraints = constraintsFromJSON(getAVConstraints("audio", roomJson.getString("media_constraints")))
      Log.d(TAG, "audioConstraints: " + audioConstraints)

      val params = SignalingParameters(iceServers, initiator, pcConstraints!!, videoConstraints!!,
        audioConstraints!!, clientId, wssUrl, wssPostUrl, offerSdp!!, iceCandidates!!
      )

      events.onSignalingParametersReady(params)
    } catch (e: JSONException) {
      events.onSignalingParametersError("Room JSON parsing error: " + e.toString())
    } catch (e: IOException) {
      events.onSignalingParametersError("Room IO error: " + e.toString())
    }

  }

  // Return the constraints specified for |type| of "audio" or "video" in
  // |mediaConstraintsString|.
  throws(javaClass<JSONException>())
  private fun getAVConstraints(type: String, mediaConstraintsString: String): String? {
    val json = JSONObject(mediaConstraintsString)
    // Tricky handling of values that are allowed to be (boolean or
    // MediaTrackConstraints) by the getUserMedia() spec.  There are three
    // cases below.
    if (!json.has(type) || !json.optBoolean(type, true)) {
      // Case 1: "audio"/"video" is not present, or is an explicit "false"
      // boolean.
      return null
    }
    if (json.optBoolean(type, false)) {
      // Case 2: "audio"/"video" is an explicit "true" boolean.
      return "{\"mandatory\": {}, \"optional\": []}"
    }
    // Case 3: "audio"/"video" is an object.
    return json.getJSONObject(type).toString()
  }

  throws(javaClass<JSONException>())
  private fun constraintsFromJSON(jsonString: String?): MediaConstraints? {
    if (jsonString == null) {
      return null
    }
    val constraints = MediaConstraints()
    val json = JSONObject(jsonString)
    val mandatoryJSON = json.optJSONObject("mandatory")
    if (mandatoryJSON != null) {
      val mandatoryKeys = mandatoryJSON.names()
      if (mandatoryKeys != null) {
        for (i in 0..mandatoryKeys.length() - 1) {
          val key = mandatoryKeys.getString(i)
          val value = mandatoryJSON.getString(key)
          constraints.mandatory.add(MediaConstraints.KeyValuePair(key, value))
        }
      }
    }
    val optionalJSON = json.optJSONArray("optional")
    if (optionalJSON != null) {
      for (i in 0..optionalJSON.length() - 1) {
        val keyValueDict = optionalJSON.getJSONObject(i)
        val key = keyValueDict.names().getString(0)
        val value = keyValueDict.getString(key)
        constraints.optional.add(MediaConstraints.KeyValuePair(key, value))
      }
    }
    return constraints
  }

  // Requests & returns a TURN ICE Server based on a request URL.  Must be run
  // off the main thread!
  throws(javaClass<IOException>(), javaClass<JSONException>())
  private fun requestTurnServers(url: String): LinkedList<PeerConnection.IceServer> {
    val turnServers = LinkedList<PeerConnection.IceServer>()
    Log.d(TAG, "Request TURN from: " + url)
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setConnectTimeout(TURN_HTTP_TIMEOUT_MS)
    connection.setReadTimeout(TURN_HTTP_TIMEOUT_MS)
    val responseCode = connection.getResponseCode()
    if (responseCode != 200) {
      throw IOException("Non-200 response when requesting TURN server from " + url + " : " + connection.getHeaderField(null))
    }
    val responseStream = connection.getInputStream()
    val response = drainStream(responseStream)
    connection.disconnect()
    Log.d(TAG, "TURN response: " + response)
    val responseJSON = JSONObject(response)
    val username = responseJSON.getString("username")
    val password = responseJSON.getString("password")
    val turnUris = responseJSON.getJSONArray("uris")
    for (i in 0..turnUris.length() - 1) {
      val uri = turnUris.getString(i)
      turnServers.add(PeerConnection.IceServer(uri, username, password))
    }
    return turnServers
  }

  // Return the list of ICE servers described by a WebRTCPeerConnection
  // configuration string.
  throws(javaClass<JSONException>())
  private fun iceServersFromPCConfigJSON(pcConfig: String): LinkedList<PeerConnection.IceServer> {
    val json = JSONObject(pcConfig)
    val servers = json.getJSONArray("iceServers")
    val ret = LinkedList<PeerConnection.IceServer>()
    for (i in 0..servers.length() - 1) {
      val server = servers.getJSONObject(i)
      val url = server.getString("urls")
      val credential = if (server.has("credential")) server.getString("credential") else ""
      ret.add(PeerConnection.IceServer(url, "", credential))
    }
    return ret
  }

  companion object {
    private val TAG = "RoomRTCClient"
    private val TURN_HTTP_TIMEOUT_MS = 5000

    // Return the contents of an InputStream as a String.
    private fun drainStream(`in`: InputStream): String {
      val s = Scanner(`in`).useDelimiter("\\A")
      return if (s.hasNext()) s.next() else ""
    }
  }

}
