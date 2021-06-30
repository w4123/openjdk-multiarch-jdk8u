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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.azul.crs.shared.Utils.*;
import com.azul.crs.util.logging.LogChannel;

/**
 * CRS client side queue to accumulate and process batches of items asynchronously
 *
 * The queue has limited capacity and throws error on overflow. The queue starts concurrent
 * workers to monitor new enqueued events and to process them in a batches of max allowed size.
 * The queue can flush all accumulated items to stop workers gracefully.
 */
@LogChannel("service.queue")
public class QueueService<T> implements ClientService {
    private static final int  DEFAULT_MAX_QUEUE_SIZE  = 5_000;   // default max queue size (items)
    private static final int  DEFAULT_MAX_WORKERS     = 3;       // default max workers to process the queue
    private static final int  DEFAULT_MAX_BATCH_SIZE  = 1_000;   // default max batch size to process by worker (items)
    private static final long DEFAULT_ADD_TIMEOUT     = 500;     // default timeout to add item to the queue (ms)

    private volatile boolean stopping;
    private volatile boolean cancelled;
    private final BlockingQueue<T> queue;
    private final List<Thread> workerThreads;
    private final List<Worker> workers;

    private final int maxQueueSize;
    private final int maxWorkers;
    private final int maxBatchSize;
    private final long addTimeout;
    private final ProcessBatch<T> processBatch;
    private final String name;

    // monitors in order of acquiring
    private final Object syncOrderMonitor = new Object(); // order sync() and stop() calls
    private final Object syncFinishNotifier = new Object(); // sync finish notification

    /** Marker event placed to the queue to notify that all previous events have been processed */
    private final T syncMarker;
    /** The barrier to perform a safepoint synchronization of all worker threads with an external request */
    private final AtomicInteger syncCount = new AtomicInteger(0);

    public interface ProcessBatch<T> {
        void process(String workerId, Collection<T> batch);
    }

    public static class Builder<T> {
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private int maxWorkers   = DEFAULT_MAX_WORKERS;
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private long addTimeout  = DEFAULT_ADD_TIMEOUT;
        private ProcessBatch<T> processBatch;
        private T stopMarker;
        private String name = "<unnamed>";

        public Builder<T> maxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; return this; }
        public Builder<T> maxWorkers(int maxWorkers) { this.maxWorkers = maxWorkers; return this; }
        public Builder<T> maxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; return this; }
        public Builder<T> addTimeout(long addTimeout) { this.addTimeout = addTimeout; return this; }
        public Builder<T> processBatch(ProcessBatch<T> processBatch) { this.processBatch = processBatch; return this; }
        public Builder<T> stopMarker(T stopMarker) { this.stopMarker = stopMarker; return this; }
        public Builder<T> name(String name) { this.name = name; return this; }
        private void notNull(Object o) { o.getClass(); }

        QueueService<T> build() {
            notNull(processBatch);
            notNull(stopMarker);

            return new QueueService<>(
                maxQueueSize, maxWorkers, maxBatchSize,
                addTimeout, stopMarker,
                processBatch, name
            );
        }
    }

    private QueueService(int maxQueueSize, int maxWorkers, int maxBatchSize,
                        long addTimeout, T syncMarker, ProcessBatch<T> processBatch,
                         String name) {

        this.maxQueueSize = maxQueueSize;
        this.maxWorkers = maxWorkers;
        this.maxBatchSize = maxBatchSize;
        this.addTimeout = addTimeout;
        this.syncMarker = syncMarker;
        this.processBatch = processBatch;

        this.queue = new LinkedBlockingDeque<>(maxQueueSize);
        this.workerThreads = new LinkedList<>();
        this.workers = new LinkedList<>();
        this.name = name;
    }

    /** Adds new item to the queue, ignores new items when the queue is stopping */
    public void add(T item) {
        if (cancelled || stopping) return;
        try {
            queue.offer(item, addTimeout, TimeUnit.MILLISECONDS);
            PerformanceMetrics.logEventQueueLength(queue.size());
        } catch (InterruptedException ie) {
            if (!Client.isVMShutdownInitiated())
                logger().error("Queue failed to enqueue item" +
                    ": queueSize=" + queue.size() +
                    ", maxQueueSize=" + maxQueueSize +
                    ", timeout=" + addTimeout +
                    ", item=" + item);
        }
    }

    public void addAll(Collection<T> items) {
        if (stopping) return;
        try {
            for (T item: items) {
                queue.offer(item, addTimeout, TimeUnit.MILLISECONDS);
            }
            PerformanceMetrics.logEventQueueLength(queue.size());
        } catch (InterruptedException ie) {
            if (!Client.isVMShutdownInitiated())
                logger().error("Queue failed to enqueue item" +
                    ": queueSize=" + queue.size() +
                    ", maxQueueSize=" + maxQueueSize +
                    ", timeout=" + addTimeout +
                    ", number of items=" + items.size());
        }
    }

    /** Starts concurrent queue workers. */
    public synchronized void start() {
        if (stopping || cancelled) throw new IllegalStateException(
            serviceName() + " is stopping or cancelled");

        for (int i = 0; i < maxWorkers; i++) {
            Worker w = new Worker(String.valueOf(i));
            Thread t = new Thread(w);
            workerThreads.add(t);
            workers.add(w);
            t.setDaemon(true);
            t.setName("CRSQW-"+name+i);
            t.start();
        }
    }

    public void sync(long deadline) {
        if (cancelled)
            return;

        // check if already missed
        if (elapsedTimeMillis(deadline) >= 0) {
            logger().debug("%s sync missed deadline", name);
            return;
        }

        synchronized (syncOrderMonitor) {
            boolean syncMarkerAdded = false;

            syncCount.set(maxWorkers);
            synchronized (syncFinishNotifier) {
                // try to inject a sync marker
                logger().trace("%s sync start", name);
                while (!(syncMarkerAdded = queue.offer(syncMarker)) && currentTimeCount() < deadline)
                    sleep(10);
                if (syncMarkerAdded) {
                    try {
                        final long timeout = -elapsedTimeMillis(deadline);
                        if (timeout > 0) syncFinishNotifier.wait(timeout);
                    } catch (InterruptedException ignored) {}
                    // resume any worker waiting for operation to complete
                    if (syncCount.get() > 0) {
                        logger().warning("%s sync timeout waiting response. %d workers not finished", name, syncCount.get());
                        syncFinishNotifier.notifyAll();
                    }
                } else {
                    logger().warning("%s sync timeout waiting to initiate queue sync", name);
                }
            }
        }
    }

    /**
     * Flushes queue content and stops the workers.
     * @param deadline the time count the operation should be aborted if not able to complete
     */
    public void stop(long deadline) {
        if (stopping) return;

        // Stop accepting new items, inject SYNC marker and
        // wait until ALL workers reach the marker and stop
        stopping = true;

        sync(deadline);
    }

    /**
     * Indicates that Queue shall cancel all pending tasks and cease future processing of incoming data.
     */
    public void cancel() {
        cancelled = true;
    }

    /** Worker to poll batches of queue items and process them. */
    protected class Worker implements Runnable {
        private final String workerId;

        public Worker(String workerId) {
            this.workerId = workerId;
        }

        private void sync() {
            try {
                synchronized (syncFinishNotifier) {
                    if (syncCount.decrementAndGet() > 0) {
                        while (!queue.offer(syncMarker))
                            Thread.sleep(10); // must add syncMarker at all costs
                        syncFinishNotifier.wait();
                    } else {
                        syncFinishNotifier.notifyAll();
                    }
                }
            } catch (InterruptedException ignored) {}
        }

        /** Main loop of the worker polls item batches and processes them */
        public void run() {
            List<T> batch = new ArrayList<>(maxBatchSize);
            boolean running = true;
            while (running) {
                int batchSize = 0;
                T item;
                try {
                    // Wait for first item available in the queue
                    item = queue.take();
                } catch (InterruptedException ignored) {
                    break;
                }

                if (item != syncMarker) {
                    batch.add(item);
                    batchSize++;

                    // Get batch until queue is empty or batch size reached
                    while (batchSize < maxBatchSize) {
                        item = queue.poll();
                        if (item == syncMarker ||
                            item == null) break;
                        batch.add(item);
                        batchSize++;
                    }

                    // Post accumulated batch of events
                    processBatch.process(workerId, batch);
                    batch.clear();
                }

                if (item == syncMarker) {
                    sync();
                    running = !stopping;
                }
            }
        }
    }
}
