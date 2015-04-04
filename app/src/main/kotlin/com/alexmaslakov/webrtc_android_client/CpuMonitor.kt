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

import android.util.Log

import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.util.InputMismatchException
import java.util.Scanner

/**
 * Simple CPU monitor.  The caller creates a CpuMonitor object which can then
 * be used via sampleCpuUtilization() to collect the percentual use of the
 * cumulative CPU capacity for all CPUs running at their nominal frequency.  3
 * values are generated: (1) getCpuCurrent() returns the use since the last
 * sampleCpuUtilization(), (2) getCpuAvg3() returns the use since 3 prior
 * calls, and (3) getCpuAvgAll() returns the use over all SAMPLE_SAVE_NUMBER
 * calls.

 *
 * CPUs in Android are often "offline", and while this of course means 0 Hz
 * as current frequency, in this state we cannot even get their nominal
 * frequency.  We therefore tread carefully, and allow any CPU to be missing.
 * Missing CPUs are assumed to have the same nominal frequency as any close
 * lower-numbered CPU, but as soon as it is online, we'll get their proper
 * frequency and remember it.  (Since CPU 0 in practice always seem to be
 * online, this unidirectional frequency inheritance should be no problem in
 * practice.)

 *
 * Caveats:
 * o No provision made for zany "turbo" mode, common in the x86 world.
 * o No provision made for ARM big.LITTLE; if CPU n can switch behind our
 * back, we might get incorrect estimates.
 * o This is not thread-safe.  To call asynchronously, create different
 * CpuMonitor objects.

 *
 * If we can gather enough info to generate a sensible result,
 * sampleCpuUtilization returns true.  It is designed to never through an
 * exception.

 *
 * sampleCpuUtilization should not be called too often in its present form,
 * since then deltas would be small and the percent values would fluctuate and
 * be unreadable. If it is desirable to call it more often than say once per
 * second, one would need to increase SAMPLE_SAVE_NUMBER and probably use
 * Queue to avoid copying overhead.

 *
 * Known problems:
 * 1. Nexus 7 devices running Kitkat have a kernel which often output an
 * incorrect 'idle' field in /proc/stat.  The value is close to twice the
 * correct value, and then returns to back to correct reading.  Both when
 * jumping up and back down we might create faulty CPU load readings.
 */

class CpuMonitor {
  private val percentVec = IntArray(SAMPLE_SAVE_NUMBER)
  private var sum3 = 0
  private var sum10 = 0
  private var cpuFreq: LongArray? = null
  private var cpusPresent: Int = 0
  private var lastPercentFreq = (-1).toDouble()
  public var cpuCurrent: Int = 0
    private set
  public var cpuAvg3: Int = 0
    private set
  public var cpuAvgAll: Int = 0
    private set
  private var initialized = false
  private var maxPath: Array<String>? = null
  private var curPath: Array<String>? = null
  var lastProcStat: ProcStat

  private inner class ProcStat(val runTime: Long, val idleTime: Long)

  private fun init() {
    try {
      val fin = FileReader("/sys/devices/system/cpu/present")
      try {
        val rdr = BufferedReader(fin)
        val scanner = Scanner(rdr).useDelimiter("[-\n]")
        scanner.nextInt()  // Skip leading number 0.
        cpusPresent = 1 + scanner.nextInt()
      } catch (e: InputMismatchException) {
        Log.e(TAG, "Cannot do CPU stats due to /sys/devices/system/cpu/present parsing problem")
      } finally {
        fin.close()
      }
    } catch (e: FileNotFoundException) {
      Log.e(TAG, "Cannot do CPU stats since /sys/devices/system/cpu/present is missing")
    } catch (e: IOException) {
      Log.e(TAG, "Error closing file")
    }


    cpuFreq = LongArray(cpusPresent)
    maxPath = arrayOfNulls<String>(cpusPresent)
    curPath = arrayOfNulls<String>(cpusPresent)
    for (i in 0..cpusPresent - 1) {
      cpuFreq[i] = 0  // Frequency "not yet determined".
      maxPath[i] = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"
      curPath[i] = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq"
    }

    lastProcStat = ProcStat(0, 0)

    initialized = true
  }

  /**
   * Re-measure CPU use.  Call this method at an interval of around 1/s.
   * This method returns true on success.  The fields
   * cpuCurrent, cpuAvg3, and cpuAvgAll are updated on success, and represents:
   * cpuCurrent: The CPU use since the last sampleCpuUtilization call.
   * cpuAvg3: The average CPU over the last 3 calls.
   * cpuAvgAll: The average CPU over the last SAMPLE_SAVE_NUMBER calls.
   */
  public fun sampleCpuUtilization(): Boolean {
    var lastSeenMaxFreq: Long = 0
    var cpufreqCurSum: Long = 0
    var cpufreqMaxSum: Long = 0

    if (!initialized) {
      init()
    }

    for (i in 0..cpusPresent - 1) {
      /*
       * For each CPU, attempt to first read its max frequency, then its
       * current frequency.  Once as the max frequency for a CPU is found,
       * save it in cpuFreq[].
       */

      if (cpuFreq!![i] == 0) {
        // We have never found this CPU's max frequency.  Attempt to read it.
        val cpufreqMax = readFreqFromFile(maxPath!![i])
        if (cpufreqMax > 0) {
          lastSeenMaxFreq = cpufreqMax
          cpuFreq[i] = cpufreqMax
          maxPath[i] = null  // Kill path to free its memory.
        }
      } else {
        lastSeenMaxFreq = cpuFreq!![i]  // A valid, previously read value.
      }

      val cpufreqCur = readFreqFromFile(curPath!![i])
      cpufreqCurSum += cpufreqCur

      /* Here, lastSeenMaxFreq might come from
       * 1. cpuFreq[i], or
       * 2. a previous iteration, or
       * 3. a newly read value, or
       * 4. hypothetically from the pre-loop dummy.
       */
      cpufreqMaxSum += lastSeenMaxFreq
    }

    if (cpufreqMaxSum == 0) {
      Log.e(TAG, "Could not read max frequency for any CPU")
      return false
    }

    /*
     * Since the cycle counts are for the period between the last invocation
     * and this present one, we average the percentual CPU frequencies between
     * now and the beginning of the measurement period.  This is significantly
     * incorrect only if the frequencies have peeked or dropped in between the
     * invocations.
     */
    val newPercentFreq = 100.0 * cpufreqCurSum.toDouble() / cpufreqMaxSum.toDouble()
    val percentFreq: Double
    if (lastPercentFreq > 0) {
      percentFreq = (lastPercentFreq + newPercentFreq) * 0.5
    } else {
      percentFreq = newPercentFreq
    }

    lastPercentFreq = newPercentFreq

    val procStat = readIdleAndRunTime()
    if (procStat == null) {
      return false
    }

    val diffRunTime = procStat.runTime - lastProcStat.runTime
    val diffIdleTime = procStat.idleTime - lastProcStat.idleTime
    lastProcStat = procStat
    val allTime = diffRunTime + diffIdleTime
    var percent =
      if (allTime == 0) 0
      else Math.round(percentFreq * diffRunTime.toDouble() / allTime.toDouble()).toInt()
    percent = Math.max(0, Math.min(percent, 100))

    // Subtract old relevant measurement, add newest.
    sum3 += percent - percentVec[2]
    // Subtract oldest measurement, add newest.
    sum10 += percent - percentVec[SAMPLE_SAVE_NUMBER - 1]

    // Rotate saved percent values, save new measurement in vacated spot.
    run {
      var i = SAMPLE_SAVE_NUMBER - 1
      while (i > 0) {
        percentVec[i] = percentVec[i - 1]
        i--
      }
    }
    percentVec[0] = percent

    cpuCurrent = percent
    cpuAvg3 = sum3 / 3
    cpuAvgAll = sum10 / SAMPLE_SAVE_NUMBER

    return true
  }

  /**
   * Read a single integer value from the named file.  Return the read value
   * or if an error occurs return 0.
   */
  private fun readFreqFromFile(fileName: String): Long {
    var number: Long = 0
    try {
      val fin = FileReader(fileName)
      try {
        val rdr = BufferedReader(fin)
        val scannerC = Scanner(rdr)
        number = scannerC.nextLong()
      } catch (e: InputMismatchException) {
        // CPU presumably got offline just after we opened file.
      } finally {
        fin.close()
      }
    } catch (e: FileNotFoundException) {
      // CPU is offline, not an error.
    } catch (e: IOException) {
      Log.e(TAG, "Error closing file")
    }

    return number
  }

  /*
   * Read the current utilization of all CPUs using the cumulative first line
   * of /proc/stat.
   */
  private fun readIdleAndRunTime(): ProcStat? {
    var runTime: Long = 0
    var idleTime: Long = 0
    try {
      val fin = FileReader("/proc/stat")
      try {
        val rdr = BufferedReader(fin)
        val scanner = Scanner(rdr)
        scanner.next()
        val user = scanner.nextLong()
        val nice = scanner.nextLong()
        val sys = scanner.nextLong()
        runTime = user + nice + sys
        idleTime = scanner.nextLong()
      } catch (e: InputMismatchException) {
        Log.e(TAG, "Problems parsing /proc/stat")
        return null
      } finally {
        fin.close()
      }
    } catch (e: FileNotFoundException) {
      Log.e(TAG, "Cannot open /proc/stat for reading")
      return null
    } catch (e: IOException) {
      Log.e(TAG, "Problems reading /proc/stat")
      return null
    }

    return ProcStat(runTime, idleTime)
  }

  companion object {
    private val SAMPLE_SAVE_NUMBER = 10  // Assumed to be >= 3.
    private val TAG = "CpuMonitor"
  }
}
