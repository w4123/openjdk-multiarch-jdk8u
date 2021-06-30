/*
 * Copyright 2019-2020 Azul Systems,
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.azul.crs.shared;

import java.util.Arrays;
import java.util.UUID;

/** Generic utils shared by server and client CRS components */
public class Utils {
    /** Generates random UUID string */
    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Generates deterministic UUID by given string value */
    public static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes()).toString();
    }

    /** Generates deterministic UUID by array of values */
    public static String uuid(Object... values) {
        return uuid(Arrays.toString(values));
    }

    /** Gets lower-cased string or null */
    public static String lower(String s) {
        return s != null ? s.toLowerCase() : null;
    }

    /** Returns a current time count in abstract units. To be used along with the following methods */
    public static long currentTimeCount() {
        return System.nanoTime();
    }

    /** Returns a time count in abstract units which is timeoutMillis ms in the future. */
    public static long nextTimeCount(long timeoutMillis) { return System.nanoTime() + timeoutMillis*1000_000; }

    /** Helper to log measured time */
    public static String elapsedTimeString(long startTimeStamp) {
        return String.format(" (%,d ms)", elapsedTimeMillis(startTimeStamp));
    }

    public static long elapsedTimeMillis(long startTimeCount) {
        return (System.nanoTime() - startTimeCount + 500_000) / 1000_000;
    }

    /** Sleep helper to hide try/catch boilerplate */
    public static void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException ie) {}
    }

    /** Helper to get current wall clock time for VM and backend events (ms).*/
    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
