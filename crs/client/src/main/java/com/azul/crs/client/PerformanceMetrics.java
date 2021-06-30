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
package com.azul.crs.client;

import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@LogChannel("performance")
public class PerformanceMetrics {
    private final AtomicLong communicationMillis = new AtomicLong();
    private final AtomicLong numBytesOut = new AtomicLong();
    private final AtomicLong numBytesIn =  new AtomicLong();
    private long shutdownMillis;
    private final AtomicLong preShutdownMillis = new AtomicLong();
    private final AtomicLong numEvents = new AtomicLong();
    private final AtomicLong numEventBatches = new AtomicLong();
    private final AtomicLong[] numEventHistogram = new AtomicLong[20];
    private final AtomicLong numConnections = new AtomicLong();
    private final AtomicLong handshakeMillis = new AtomicLong();
    private final AtomicInteger maxQueueLength = new AtomicInteger();
    private final AtomicLong numBytesInArtifacts = new AtomicLong();
    private final AtomicLong numClassLoads = new AtomicLong();
    private final AtomicLong numMethodEntries = new AtomicLong();

    private static final Map<String, Number> fieldDesc = new HashMap<>();

    private static PerformanceMetrics instance;

    static void init() {
        instance = new PerformanceMetrics();
        for (int i=0; i<instance.numEventHistogram.length; i++)
            instance.numEventHistogram[i] = new AtomicLong();

        for (Field f: PerformanceMetrics.class.getDeclaredFields()) {
            if (Number.class.isAssignableFrom(f.getType())) {
                try {
                    fieldDesc.put(f.getName(), (Number) f.get(instance));
                } catch (IllegalAccessException shouldNotHappen) {
                    shouldNotHappen.printStackTrace();
                }
            }
        }
    }

    static void logNetworkTime(long duration) {
        instance.communicationMillis.addAndGet(duration);
    }

    static void logHandshakeTime(long duration) {
        instance.handshakeMillis.addAndGet(duration);
        instance.numConnections.incrementAndGet();
    }

    static void logBytes(long in, long out) {
        instance.numBytesIn.addAndGet(in);
        instance.numBytesOut.addAndGet(out);
    }

    static void logShutdown(long duration) {
        instance.shutdownMillis = duration;
    }

    public static void logEventBatch(long size) {
        instance.numEvents.addAndGet(size);
        instance.numEventBatches.incrementAndGet();
        instance.numEventHistogram[(int)(Math.log(size)/Math.log(2))].incrementAndGet();
    }

    public static void logClassLoads(long count) {
        instance.numClassLoads.addAndGet(count);
    }

    public static void logMethodEntries(long count) {
        instance.numMethodEntries.addAndGet(count);
    }

    public static void logEventQueueLength(int size) {
        AtomicInteger l = instance.maxQueueLength;
        int prev;
        do {
            prev = l.get();
        } while (prev < size && !l.compareAndSet(prev, size));
    }

    public static Map logPreShutdown(long duration) {
        instance.preShutdownMillis.set(duration);
        return instance.toEventPayload();
    }

    public static void logArtifactBytes(long bytes) {
        instance.numBytesInArtifacts.addAndGet(bytes);
    }

    private Map toEventPayload() {
        Map<String, String> data = new HashMap<>();
        for (HashMap.Entry<String, Number> e : fieldDesc.entrySet())
            data.put(e.getKey(), e.getValue().toString());
        return data;
    }

    static void report() {
        Logger logger = Logger.getLogger(PerformanceMetrics.class);
        if (logger.isEnabled(Logger.Level.INFO)) {
            StringBuilder numEventsHistogram = new StringBuilder();
            for (int i=0; i<instance.numEventHistogram.length; i++)
                numEventsHistogram.append(instance.numEventHistogram[i].get()).append(' ');
            logger.info("total communication duration %.3fs\n" +
                    "number of connections established %d, %.3fs spent in handshake\n" +
                    "total bytes in %.3fM\n" +
                    "total event data bytes out %.3fM\n" +
                    "total artifacts bytes %.3fM\n" +
                    "maximum queue length %d\n" +
                    "shutdown delay %.3fs (pre %.3fs)\n" +
                    "classes loaded %d\n" +
                    "methods invoked %d\n" +
                    "events sent %d batches %d [%s]\n",
                instance.communicationMillis.get() / 1000.,
                instance.numConnections.get(), instance.handshakeMillis.get() / 1000.,
                instance.numBytesIn.get() / 1024. / 1024.,
                instance.numBytesOut.get() / 1024. / 1024.,
                instance.numBytesInArtifacts.get() / 1024. / 1024.,
                instance.maxQueueLength.get(),
                instance.shutdownMillis / 1000., instance.preShutdownMillis.get() / 1000.,
                instance.numClassLoads.get(),
                instance.numMethodEntries.get(),
                instance.numEvents.get(), instance.numEventBatches.get(),
                numEventsHistogram.toString());
        }
    }
}
