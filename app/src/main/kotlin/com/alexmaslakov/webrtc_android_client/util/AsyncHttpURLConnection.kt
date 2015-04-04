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

package com.alexmaslakov.webrtc_android_client.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Scanner

/**
 * Asynchronous http requests implementation.
 */
public class AsyncHttpURLConnection(
  private val method: String,
  private val url: String,
  private val message: String?,
  private val events: AsyncHttpURLConnection.AsyncHttpEvents
) {

  /**
   * Http requests callbacks.
   */
  public trait AsyncHttpEvents {
    public fun onHttpError(errorMessage: String)
    public fun onHttpComplete(response: String)
  }

  public fun send() {
    val runHttp = object : Runnable {
      override fun run() {
        sendHttpMessage()
      }
    }
    Thread(runHttp).start()
  }

  private fun sendHttpMessage() {
    try {
      val connection = URL(url).openConnection() as HttpURLConnection
      var postData = ByteArray(0)
      if (message != null) {
        postData = message.toByteArray("UTF-8")
      }
      connection.setRequestMethod(method)
      connection.setUseCaches(false)
      connection.setDoInput(true)
      connection.setConnectTimeout(HTTP_TIMEOUT_MS)
      connection.setReadTimeout(HTTP_TIMEOUT_MS)
      // TODO(glaznev) - query request origin from pref_room_server_url_key preferences.
      connection.addRequestProperty("origin", HTTP_ORIGIN)
      var doOutput = false
      if (method == "POST") {
        doOutput = true
        connection.setDoOutput(true)
        connection.setFixedLengthStreamingMode(postData.size())
      }
      connection.setRequestProperty("content-type", "text/plain; charset=utf-8")

      // Send POST request.
      if (doOutput && postData.size() > 0) {
        val outStream = connection.getOutputStream()
        outStream.write(postData)
        outStream.close()
      }

      // Get response.
      val responseCode = connection.getResponseCode()
      if (responseCode != 200) {
        connection.disconnect()
        events.onHttpError("Non-200 response to " + method + " to URL: " + url + " : " + connection.getHeaderField(null))
        return
      }
      val responseStream = connection.getInputStream()
      val response = drainStream(responseStream)
      responseStream.close()
      connection.disconnect()
      events.onHttpComplete(response)
    } catch (e: SocketTimeoutException) {
      events.onHttpError("HTTP " + method + " to " + url + " timeout")
    } catch (e: IOException) {
      events.onHttpError("HTTP " + method + " to " + url + " error: " + e.getMessage())
    }

  }

  companion object {
    private val HTTP_TIMEOUT_MS = 8000
    private val HTTP_ORIGIN = "https://apprtc.appspot.com"

    // Return the contents of an InputStream as a String.
    private fun drainStream(`in`: InputStream): String {
      val s = Scanner(`in`).useDelimiter("\\A")
      return if (s.hasNext()) s.next() else ""
    }
  }
}
