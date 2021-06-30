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
package com.azul.crs.client.service;

import com.azul.crs.client.Client;
import com.azul.crs.client.PerformanceMetrics;
import com.azul.crs.shared.models.VMEvent;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.azul.crs.shared.Utils.currentTimeMillis;
import static com.azul.crs.shared.models.VMEvent.Type.*;
import com.azul.crs.util.logging.Logger;
import com.azul.crs.util.logging.LogChannel;

@LogChannel("service.classload")
public class ClassLoadMonitor implements ClientService {

    private static ClassLoadMonitor instance = new ClassLoadMonitor();
    private Client client;
    private volatile boolean started, stopped;
    private long _count;
    private final PrintWriter traceOut;

    private ClassLoadMonitor() {
        PrintWriter out = null;
        if (logger().isEnabled(Logger.Level.TRACE)) {
            try {
                Path traceOutFileName = Files.createTempFile("CRSClassLoadMonitor", ".log");
                logger().trace("writing ClassLoadMonitor trace to file %s", traceOutFileName);
                out = new PrintWriter(Files.newBufferedWriter(traceOutFileName));
            } catch (IOException ignored) {
                ignored.printStackTrace();
            }
        }
        traceOut = out;
    }

    public static ClassLoadMonitor getInstance(Client client) {
        instance.client = client;
        return instance;
    }

    /** Creates VM event object with given class load details */
    private VMEvent classLoadEvent(String className, String hashString, int classId, int loaderId, String source, long eventTime) {
        Map<String, String> payload = new HashMap<>();
        payload.put("className", className);
        payload.put("hash", hashString);
        payload.put("classId", Integer.toString(classId));
        payload.put("loaderId", Integer.toString(loaderId));
        if (source != null)
            payload.put("source", source);

        return new VMEvent<Map>()
            .randomEventId()
            .eventType(VM_CLASS_LOADED)
            .eventTime(eventTime)
            .eventPayload(payload);
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void stop(long deadline) {
        logger().debug("total classes loaded count "+_count);
        PerformanceMetrics.logClassLoads(_count);
        if (traceOut  != null)
            traceOut.close();

        started = false;
        stopped = true;
    }

    private static final char[] digit = "0123456789abcdef".toCharArray();
    private static String encodeToString(byte[] hash) {
        char[] str = new char[hash.length*2];
        for(int i = 0; i < hash.length; i++) {
            byte b = hash[i];
            str[i*2] = digit[(b >>> 4) & 0x0f];
            str[i*2+1] = digit[b & 0x0f];
        }
        return new String(str);
    }

    /**
     * VM callback invoked each time class is loaded.
     *
     * className name of the loaded class.
     * hash SHA-256 hash of the class file.
     * classId the unique id of the class
     */
    public void notifyClassLoad(String className, byte[] hash, int classId, int loaderId, String source) {
        _count++;

        if (stopped)
            return;
        if (!started) {
            Logger.getLogger(ClassLoadMonitor.class).error("service is not yet started");
        }

        long eventTime = currentTimeMillis();
        String hashString = encodeToString(hash);
        client.postVMEvent(classLoadEvent(className, hashString, classId, loaderId, source, eventTime));

        if (traceOut != null)
            traceOut.printf("%s [%d:%d]", className, loaderId, classId);
    }
}
