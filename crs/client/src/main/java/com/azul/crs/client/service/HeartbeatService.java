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
import com.azul.crs.shared.models.VMEvent;

import static com.azul.crs.shared.Utils.currentTimeMillis;
import static com.azul.crs.shared.models.VMEvent.Type.VM_HEARTBEAT;
import com.azul.crs.util.logging.LogChannel;

@LogChannel("service.heartbeat")
public class HeartbeatService implements ClientService {
    private static final long HEARTBEAT_SLEEP = 5_000;   // time between heart beats, in millis
    private static final int HEARTBEAT_LOG_COUNT = 300;  // number of heart beats to be logged
    private static final long HEARTBEAT_STOP = 60_000;   // time to stop heartbeat thread, in millis

    private static HeartbeatService instance = new HeartbeatService();
    private Client client;
    private Thread thread;
    private volatile boolean running;

    private HeartbeatService() {}

    public static HeartbeatService getInstance(Client client) {
        instance.client = client;
        return instance;
    }

    private void run() {
        // Post heart beats while VM is running
        long heartbeatCount = 0;
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_SLEEP);
                long lastHeardTime = currentTimeMillis();
                client.postVMEvent(new VMEvent()
                    .randomEventId()
                    .eventType(VM_HEARTBEAT)
                    .eventTime(lastHeardTime));

                // Log batch of successful heartbeats
                if (++heartbeatCount % HEARTBEAT_LOG_COUNT == 0)
                    logger().info("CRS client heartbeats: lastHeardTime=%s, count=%,d\n",
                        lastHeardTime, heartbeatCount);
            } catch (InterruptedException ie) {}
        }
    }

    /** Creates and starts daemon thread to report VM heartbeat events to CRS */
    @Override
    public synchronized void start() {
        if (running)
            throw new IllegalStateException(serviceName() + " is running already");
        thread = new Thread(this::run);
        thread.setDaemon(true);
        thread.setName("CRSHeartbeat");
        running = true;
        thread.start();
    }

    @Override
    public synchronized void stop(long deadline) {
        if (!running) return;
        try {
            running = false;
            thread.interrupt();
            thread.join(HEARTBEAT_STOP);
        } catch (InterruptedException ie) {}
    }
}
