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
import com.azul.crs.client.Response;
import com.azul.crs.client.Result;
import com.azul.crs.shared.Utils;
import com.azul.crs.shared.models.VMArtifactChunk;
import com.azul.crs.util.logging.LogChannel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

@LogChannel("service.upload")
public class UploadService implements ClientService {

    private static final int MAX_QUEUE_SIZE = 50_000;
    private static final int MAX_WORKERS = 1;
    private static final int BATCH_SIZE = 1;

    private boolean isConnected;
    private final QueueService<Job> queue;
    private final Client client;

    private class Job {
        private VMArtifactChunk chunk;
        private File data;
        private Client.UploadListener<VMArtifactChunk> listener;

        public Job(VMArtifactChunk chunk, File data, Client.UploadListener<VMArtifactChunk> listener) {
            this.chunk = chunk;
            this.data = data;
            this.listener = listener;
        }

        public VMArtifactChunk getChunk() {
            return chunk;
        }

        public File getData() {
            return data;
        }

        public Client.UploadListener<VMArtifactChunk> getListener() {
            return listener;
        }
    }

    public UploadService(Client client) {
        this.queue = new QueueService.Builder<Job>()
            .maxQueueSize(MAX_QUEUE_SIZE)
            .maxBatchSize(BATCH_SIZE)
            .maxWorkers(MAX_WORKERS)
            .processBatch(this::send)
            .stopMarker(new Job(null, null, null))
            .name("UPLOAD")
            .build();
        this.client = client;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop(long deadline) {
        if (Utils.currentTimeCount() < deadline) {
            logger().info("awaiting artifact data to flush to the cloud");
            queue.stop(deadline);
        } else {
            logger().debug("skipping flush of artifact data to the cloud because no time left");
        }
    }

    public void cancel() {
        queue.cancel();
    }

    public void connectionEstablished() {
        isConnected = true;
        logger().trace("connection established, sending artifact data to the cloud");
        queue.start();
    }


    public void post(VMArtifactChunk chunk, File chunkData, Client.UploadListener<VMArtifactChunk> listener) {
        queue.add(new Job(chunk, chunkData, listener));
    }

    public void sync(long deadline) {
        if (Utils.currentTimeCount() < deadline) {
            logger().trace("syncing artifact data to the cloud");
            queue.sync(deadline);
        } else {
            logger().debug("not syncing artifact data to the cloud because no time left");
        }
    }

    private void send(String workerId, Collection<Job> jobs) {
        Job job = jobs.iterator().next();
        VMArtifactChunk chunk = job.getChunk();
        try {
            File data = job.getData();
            PerformanceMetrics.logArtifactBytes(data.length());
            logger().trace("uploading "+data.getName());
            Response<String[]> response =
                    client.getConnectionManager().sendVMArtifactChunk(chunk, new FileInputStream(data));
            logger().trace("upload finished");
            if (response.successful())
                job.getListener().uploadComplete(chunk);
            else
                job.getListener().uploadFailed(chunk, new Result(response));
        } catch (IOException e) {
            job.getListener().uploadFailed(chunk, new Result<>(e));
        }
    }

}
