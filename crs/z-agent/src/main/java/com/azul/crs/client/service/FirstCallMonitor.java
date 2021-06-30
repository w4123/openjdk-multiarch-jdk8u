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

import java.util.HashMap;
import java.util.Map;

import static com.azul.crs.shared.Utils.currentTimeMillis;
import static com.azul.crs.shared.models.VMEvent.Type.VM_METHOD_FIRST_CALLED;
import com.azul.crs.util.logging.Logger;
import com.azul.crs.util.logging.LogChannel;

@LogChannel("service.firstcall")
public class FirstCallMonitor implements ClientService {

    private final static FirstCallMonitor instance = new FirstCallMonitor();

    private Client client;
    private volatile boolean started, stopped;
    private long _count;

    private FirstCallMonitor() {}

    public static FirstCallMonitor getInstance(Client client) {
        instance.client = client;
        return instance;
    }

    /** Creates VM event object with given class load details */
    private static VMEvent methodEntryEvent(int classId, String methodName, long eventTime) {
        Map<String, String> payload = new HashMap<>();
        payload.put("classId", Integer.toString(classId));
        payload.put("methodName", methodName);

        return new VMEvent<Map>()
            .randomEventId()
            .eventType(VM_METHOD_FIRST_CALLED)
            .eventTime(eventTime)
            .eventPayload(payload);
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void stop(long deadline) {
        logger().debug("total methods invoked "+_count);
        PerformanceMetrics.logMethodEntries(_count);
        started = false;
        stopped = true;
    }

    /**
     * VM callback invoked each time method is entered the first time.
     *
     * @param classId id of the class this method belongs to
     * methodName fully qualified name of the entered method with signature in internal format.
     */
    public void notifyMethodFirstCalled(int classId, String methodName) {
        _count++;

        if (stopped)
            return;
        if (!started) {
            logger().error("service is not yet started");
            return;
        }

        Logger.getLogger(FirstCallMonitor.class).trace("Entered "+methodName);
        long eventTime = currentTimeMillis();
        client.postVMEvent(methodEntryEvent(classId, methodName, eventTime));
    }
}
