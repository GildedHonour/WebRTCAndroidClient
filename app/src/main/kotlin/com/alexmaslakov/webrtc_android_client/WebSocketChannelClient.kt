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

import com.alexmaslakov.webrtc_android_client.util.AsyncHttpURLConnection
import com.alexmaslakov.webrtc_android_client.util.AsyncHttpURLConnection.AsyncHttpEvents
import com.alexmaslakov.webrtc_android_client.util.LooperExecutor

import android.util.Log

import de.tavendo.autobahn.WebSocket.WebSocketConnectionObserver
import de.tavendo.autobahn.WebSocketConnection
import de.tavendo.autobahn.WebSocketException

import org.json.JSONException
import org.json.JSONObject

import java.net.URI
import java.net.URISyntaxException
import java.util.LinkedList

/**
 * WebSocket client implementation.

 *
 * All public methods should be called from a looper executor thread
 * passed in a constructor, otherwise exception will be thrown.
 * All events are dispatched on the same thread.
 */

public class WebSocketChannelClient(
  private val executor: LooperExecutor,
  private val events: WebSocketChannelClient.WebSocketChannelEvents
) {

  private var ws: WebSocketConnection? = null
  private var wsObserver: WebSocketObserver? = null
  private var wsServerUrl: String? = null
  private var postServerUrl: String? = null
  private var roomID: String? = null
  private var clientID: String? = null
  public var state: WebSocketConnectionState? = null
    private set
  private val closeEventLock = Object()
  private var closeEvent: Boolean = false
  // WebSocket send queue. Messages are added to the queue when WebSocket
  // client is not registered and are consumed in register() call.
  private val wsSendQueue: LinkedList<String>

  /**
   * Possible WebSocket connection states.
   */
  public enum class WebSocketConnectionState {
    NEW
    CONNECTED
    REGISTERED
    CLOSED
    ERROR
  }

  /**
   * Callback interface for messages delivered on WebSocket.
   * All events are dispatched from a looper executor thread.
   */
  public trait WebSocketChannelEvents {
    public fun onWebSocketMessage(message: String)
    public fun onWebSocketClose()
    public fun onWebSocketError(description: String)
  }

  {
    roomID = null
    clientID = null
    wsSendQueue = LinkedList<String>()
    state = WebSocketConnectionState.NEW
  }

  public fun connect(wsUrl: String, postUrl: String) {
    checkIfCalledOnValidThread()
    if (state != WebSocketConnectionState.NEW) {
      Log.e(TAG, "WebSocket is already connected.")
      return
    }
    wsServerUrl = wsUrl
    postServerUrl = postUrl
    closeEvent = false

    Log.d(TAG, "Connecting WebSocket to: " + wsUrl + ". Post URL: " + postUrl)
    ws = WebSocketConnection()
    wsObserver = WebSocketObserver()
    try {
      ws!!.connect(URI(wsServerUrl), wsObserver)
    } catch (e: URISyntaxException) {
      reportError("URI error: " + e.getMessage())
    } catch (e: WebSocketException) {
      reportError("WebSocket connection error: " + e.getMessage())
    }

  }

  public fun register(roomID: String, clientID: String) {
    checkIfCalledOnValidThread()
    this.roomID = roomID
    this.clientID = clientID
    if (state != WebSocketConnectionState.CONNECTED) {
      Log.d(TAG, "WebSocket register() in state " + state)
      return
    }
    Log.d(TAG, "Registering WebSocket for room " + roomID + ". CLientID: " + clientID)
    val json = JSONObject()
    try {
      json.put("cmd", "register")
      json.put("roomid", roomID)
      json.put("clientid", clientID)
      Log.d(TAG, "C->WSS: " + json.toString())
      ws!!.sendTextMessage(json.toString())
      state = WebSocketConnectionState.REGISTERED
      // Send any previously accumulated messages.
      for (sendMessage in wsSendQueue) {
        send(sendMessage)
      }
      wsSendQueue.clear()
    } catch (e: JSONException) {
      reportError("WebSocket register JSON error: " + e.getMessage())
    }

  }

  public fun send(message: String) {
    checkIfCalledOnValidThread()
    when (state) {
      WebSocketChannelClient.WebSocketConnectionState.NEW, WebSocketChannelClient.WebSocketConnectionState.CONNECTED -> {
        // Store outgoing messages and send them after websocket client
        // is registered.
        Log.d(TAG, "WS ACC: " + message)
        wsSendQueue.add(message)
        return
      }
      WebSocketChannelClient.WebSocketConnectionState.ERROR, WebSocketChannelClient.WebSocketConnectionState.CLOSED -> {
        Log.e(TAG, "WebSocket send() in error or closed state : " + message)
        return
      }
      WebSocketChannelClient.WebSocketConnectionState.REGISTERED -> {
        val json = JSONObject()
        try {
          json.put("cmd", "send")
          json.put("msg", message)
          Log.d(TAG, "C->WSS: " + json.toString())
          ws!!.sendTextMessage(message)
        } catch (e: JSONException) {
          reportError("WebSocket send JSON error: " + e.getMessage())
        }

      }
    }
  }

  // This call can be used to send WebSocket messages before WebSocket
  // connection is opened.
  public fun post(message: String) {
    checkIfCalledOnValidThread()
    sendWSSMessage("POST", message)
  }

  public fun disconnect(waitForComplete: Boolean) {
    checkIfCalledOnValidThread()
    Log.d(TAG, "Disonnect WebSocket. State: " + state)
    if (state == WebSocketConnectionState.REGISTERED) {
      send("{\"type\": \"bye\"}")
      state = WebSocketConnectionState.CONNECTED
    }
    // Close WebSocket in CONNECTED or ERROR states only.
    if (state == WebSocketConnectionState.CONNECTED || state == WebSocketConnectionState.ERROR) {
      ws!!.disconnect()

      // Send DELETE to http WebSocket server.
      sendWSSMessage("DELETE", "")
      state = WebSocketConnectionState.CLOSED
      // Wait for websocket close event to prevent websocket library from
      // sending any pending messages to deleted looper thread.
      if (waitForComplete) {
        synchronized (closeEventLock) {
          while (!closeEvent) {
            try {
              closeEventLock.wait(CLOSE_TIMEOUT.toLong())
              break
            } catch (e: InterruptedException) {
              Log.e(TAG, "Wait error: " + e.toString())
            }

          }
        }
      }
    }

    Log.d(TAG, "Disonnecting WebSocket done.")
  }

  private fun reportError(errorMessage: String) {
    Log.e(TAG, errorMessage)
    executor.execute(object : Runnable {
      override fun run() {
        if (state != WebSocketConnectionState.ERROR) {
          state = WebSocketConnectionState.ERROR
          events.onWebSocketError(errorMessage)
        }
      }
    })
  }

  // Asynchronously send POST/DELETE to WebSocket server.
  private fun sendWSSMessage(method: String, message: String) {
    val postUrl = postServerUrl + "/" + roomID + "/" + clientID
    Log.d(TAG, "WS " + method + " : " + postUrl + " : " + message)
    val httpConnection = AsyncHttpURLConnection(method, postUrl, message, object : AsyncHttpEvents {
      override fun onHttpError(errorMessage: String) {
        reportError("WS " + method + " error: " + errorMessage)
      }

      override fun onHttpComplete(response: String) { }
    })

    httpConnection.send()
  }

  // Helper method for debugging purposes. Ensures that WebSocket method is
  // called on a looper thread.
  private fun checkIfCalledOnValidThread() {
    if (!executor.checkOnLooperThread()) {
      throw IllegalStateException("WebSocket method is not called on valid thread")
    }
  }

  private inner class WebSocketObserver : WebSocketConnectionObserver {
    override fun onOpen() {
      Log.d(TAG, "WebSocket connection opened to: " + wsServerUrl)
      executor.execute(object : Runnable {
        override fun run() {
          state = WebSocketConnectionState.CONNECTED
          // Check if we have pending register request.
          if (roomID != null && clientID != null) {
            register(roomID, clientID)
          }
        }
      })
    }

    override fun onClose(code: WebSocketConnectionObserver.WebSocketCloseNotification, reason: String) {
      Log.d(TAG, "WebSocket connection closed. Code: " + code + ". Reason: " + reason + ". State: " + state)
      synchronized (closeEventLock) {
        closeEvent = true
        closeEventLock.notify()
      }
      executor.execute(object : Runnable {
        override fun run() {
          if (state != WebSocketConnectionState.CLOSED) {
            state = WebSocketConnectionState.CLOSED
            events.onWebSocketClose()
          }
        }
      })
    }

    override fun onTextMessage(payload: String) {
      Log.d(TAG, "WSS->C: " + payload)
      val message = payload
      executor.execute(object : Runnable {
        override fun run() {
          if (state == WebSocketConnectionState.CONNECTED || state == WebSocketConnectionState.REGISTERED) {
            events.onWebSocketMessage(message)
          }
        }
      })
    }

    override fun onRawTextMessage(payload: ByteArray) {
    }

    override fun onBinaryMessage(payload: ByteArray) {
    }
  }

  companion object {
    private val TAG = "WSChannelRTCClient"
    private val CLOSE_TIMEOUT = 1000
  }

}
