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

import android.os.Handler
import android.os.Looper
import android.util.Log

import java.util.concurrent.Executor

/**
 * Looper based executor class.
 */
public class LooperExecutor : Thread(), Executor {
  // Object used to signal that looper thread has started and Handler instance
  // associated with looper thread has been allocated.
  private val looperStartedEvent = Object()
  private var handler: Handler? = null
  private var running = false
  private var threadId: Long = 0

  override fun run() {
    Looper.prepare()
    synchronized (looperStartedEvent) {
      Log.d(TAG, "Looper thread started.")
      handler = Handler()
      threadId = Thread.currentThread().getId()
      looperStartedEvent.notify()
    }
    Looper.loop()
  }

  synchronized public fun requestStart() {
    if (running) {
      return
    }
    running = true
    handler = null
    start()
    // Wait for Hander allocation.
    synchronized (looperStartedEvent) {
      while (handler == null) {
        try {
          looperStartedEvent.wait()
        } catch (e: InterruptedException) {
          Log.e(TAG, "Can not start looper thread")
          running = false
        }

      }
    }
  }

  synchronized public fun requestStop() {
    if (!running) {
      return
    }
    running = false
    handler!!.post(object : Runnable {
      override fun run() {
        Looper.myLooper().quit()
        Log.d(TAG, "Looper thread finished.")
      }
    })
  }

  // Checks if current thread is a looper thread.
  public fun checkOnLooperThread(): Boolean {
    return (Thread.currentThread().getId() == threadId)
  }

  synchronized override fun execute(runnable: Runnable) {
    if (!running) {
      Log.w(TAG, "Running looper executor without calling requestStart()")
      return
    }
    if (Thread.currentThread().getId() == threadId) {
      runnable.run()
    } else {
      handler!!.post(runnable)
    }
  }

  companion object {
    private val TAG = "LooperExecutor"
  }

}
