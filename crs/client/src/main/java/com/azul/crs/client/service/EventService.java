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
import com.azul.crs.client.ConnectionManager;
import com.azul.crs.client.PerformanceMetrics;
import com.azul.crs.client.Response;
import com.azul.crs.shared.models.Payload;
import com.azul.crs.shared.models.VMEvent;

import java.io.IOException;
import java.util.Collection;
import com.azul.crs.util.logging.LogChannel;

/**
 * CRS client side queue for VM events posted by connected runtime to CRS service.
 *
 * The queue has limited capacity and throws error on overflow. The queue is able to start concurrent
 * workers to monitor new enqueued events, and to post them to CRS in a batches of max allowed size.
 * The queue can flush all accumulated events to CRS and to stop workers gracefully.
 */
@LogChannel("service.event")
public class EventService implements ClientService {
    private static final int MAX_QUEUE_SIZE = 50_000;
    private static final int MAX_WORKERS = 1; // back-end currently cannot tolerate events received not in order
    private static final int BATCH_SIZE = 1_000;

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_SLEEP = 100;

    private final Client client;
    private final QueueService<VMEvent> queue;
    private static final boolean DEBUG = false;

    private EventService(Client client) {
        this.client = client;
        this.queue = new QueueService.Builder<VMEvent>()
            .maxQueueSize(MAX_QUEUE_SIZE)
            .maxBatchSize(BATCH_SIZE)
            .maxWorkers(MAX_WORKERS)
            .processBatch(this::postWithRetries)
            .stopMarker(new VMEvent())
            .name("EVENT")
            .build();
    }

    public static EventService getInstance(Client client) {
        return new EventService(client);
    }

    public void add(VMEvent event) { queue.add(event); }
    public void addAll(Collection<VMEvent> events) { queue.addAll(events); }

    @Override public String serviceName() { return "client.service.Events"; }
    @Override public void start() { }
    @Override public void stop(long deadline) { queue.stop(deadline); }
    public void cancel() { queue.cancel(); }
    public void connectionEstablished() { queue.start(); }

    /** Posts batch of events with number of retries on failure */
    private void postWithRetries(String workerId, Collection<VMEvent> batch) {
        logger().info("event worker tries to post batch of %,d VM events", batch.size());
        PerformanceMetrics.logEventBatch(batch.size());
        client.getConnectionManager().requestWithRetries(
            new ConnectionManager.ResponseSupplier() {
                @Override
                public Response get() throws IOException {
                    return client.getConnectionManager().sendVMEventBatch(batch);
                }
            },
            "postEventBatch",
            MAX_RETRIES,
            RETRY_SLEEP
        );
    }
}
