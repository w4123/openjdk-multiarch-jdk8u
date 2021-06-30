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
import com.azul.crs.client.Inventory;
import com.azul.crs.client.Result;
import com.azul.crs.jfr.access.FlightRecorderAccess;

import com.azul.crs.shared.models.VMArtifact;
import com.azul.crs.shared.models.VMArtifactChunk;
import jdk.jfr.*;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import com.azul.crs.util.logging.LogChannel;

@LogChannel("service.jfr")
public class JFRMonitor implements ClientService, FlightRecorderListener, FlightRecorderAccess.FlightRecorderCallbacks {
    // JFR is sensitive to access to it's methods from the callbacks. in order to avoid deadlocks
    // process the information in a own thread
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName("CRSJFRMonitor");
            t.setDaemon(true);
            return t;
        }
    });

    private static volatile JFRMonitor instance;

    private boolean running;
    private boolean isJfrInitialized;
    private final FlightRecorder fr;
    private Client client;
    private String params;
    private Recording theRecording;
    private Map<RecordingMirror, RecordingMirror> knownRecordings;
    private boolean noLifeTimeJfr;
    private int sequenceNumber;
    private final Object shutdownJfrMonitor = new Object();
    private FlightRecorderAccess access;

    private static final boolean DEBUG = true;
    private static final String SERVICE_NAME = "client.service.JFR";

    // To avoid deadlock on shutdown all delayed processing shall not use instances of jdk.jfr.Recording class
    // hashCode and equals are ok
    private static class RecordingMirror {
        Recording r;
        String name;
        RecordingState state;
        Instant startTime;
        Instant stopTime;
        int id;
        String destination;

        RecordingMirror(Recording r) {
            this.r = r;
            this.name = r.getName();
            this.state = r.getState();
            this.startTime = r.getStartTime();
            this.stopTime = r.getStopTime();
            this.destination = r.getDestination() != null
                ? r.getDestination().toString()
                : "<unknown>";
        }

        @Override
        public int hashCode() {
            return r.hashCode();
        }

        public boolean equals(Recording r) {
            return this.r.equals(r);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof RecordingMirror && r.equals(((RecordingMirror)obj).r);
        }

        @Override
        public String toString() {
            return Integer.toString(id);
        }
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    private JFRMonitor(Client client, String params) {
        this.client = client;
        this.params = params;

        FlightRecorder fr = null;
        if (FlightRecorder.isAvailable()) {
            try {
                fr = FlightRecorder.getFlightRecorder();
            }
            catch (IllegalStateException ex) {
                // the use case here is if we are starting while application requests shutdown via Runtime.exit
                // JFR fails to initialize but we want to continue CRS operation
                logger().warning("Cannot initialize. Disabling JFR monitoring. %s", ex);
            }
        } else {
            logger().info("JFR is not available in this VM");
        }
        this.fr = fr;
        // must set listener only after fr is initialized because it might be invoked synchronously
        if (fr != null)
            fr.addListener(this);
    }

    public static JFRMonitor getInstance(Client client, String params) {
        if (instance == null)
            synchronized (JFRMonitor.class) {
                if (instance == null)
                    instance = new JFRMonitor(client, params);
            }

        // Check whether existing instance has parameters as requested
        if (!Objects.equals(client, instance.client) ||
                !Objects.equals(params, instance.params))
            throw new IllegalArgumentException(SERVICE_NAME + ": " +
                "service instance with other parameters is created already");

        return instance;
    }

    private void setParams() {
        if (params == null || "disable".equals(params)) {
            noLifeTimeJfr = true;
            logger().info("lifetime recording is disabled");
        } else if ("".equals(params)) {
            theRecording = new Recording();
            if (DEBUG)
                logger().info("started lifetime recording with empty configuration");
        } else {
            try {
                theRecording = new Recording(Configuration.create(new File(params).toPath()));
                logger().info("started lifetime recording with configuration from "+params);
            }
            catch (Exception ex) {
                logger().error("cannot read or parse specified JFR configuration file "+params+
                        ". recording stopped");
                noLifeTimeJfr = true;
            }
        }
        if (!noLifeTimeJfr) {
            theRecording.setName("lifetime recording");
            theRecording.start();
        }
    }

    public void start() {
        if (fr == null)
            return; // JFR is not available

        setParams();
        running = true;
    }

    private void send(Object chunk, Path path, Instant startTime, Instant endTime, long size, Set<RecordingMirror> recordings) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("startTime", startTime.toEpochMilli());
        attributes.put("endTime", endTime.toEpochMilli());
        attributes.put("sequenceNumber", Integer.toString(sequenceNumber++));

        if (DEBUG) logger().info("sending chunk data from "+path);
        // recordings.stream().map((r) -> r.id).collect(Collectors.toSet())
        // not sure this is the time I'd like to bring all _that_ into play
        Set<String> artifactsId = new HashSet<>(recordings.size());
        for (RecordingMirror r: recordings)
            artifactsId.add(Client.artifactIdToString(r.id));
        client.postVMArtifactChunk(
            artifactsId,
            attributes,
            path.toFile(),
            new Client.UploadListener<VMArtifactChunk>() {
                @Override
                public void uploadComplete(VMArtifactChunk request) {
                    release();
                }

                @Override
                public void uploadFailed(VMArtifactChunk request, Result<String[]> result) {
                    logger().error("Failed to send recording chunk %s: %s%s",
                        path, result, Client.isVMShutdownInitiated() ? "(expected during shutdown if timeout is exceeded)" : "");
                    release();
                }

                private void release() {
                    if (DEBUG) logger().trace("releasing chunk %s", chunk);
                    try {
                        access.releaseRepositoryChunk(chunk);
                    } catch (FlightRecorderAccess.AccessException shouldnothappen) {
                        shouldnothappen.printStackTrace();
                    }
                }
            });
    }

    private void send(RecordingMirror recording) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tags", Inventory.instanceTags());
        attributes.put("startTime", recording.startTime.toEpochMilli());
        attributes.put("state", recording.state.toString());
        if (recording.state == RecordingState.STOPPED || recording.state == RecordingState.CLOSED)
            attributes.put("stopTime", recording.stopTime.toEpochMilli());
        attributes.put("name", recording.name);
        attributes.put("destination", recording.destination);
        recording.id = client.createArtifactId();
        client.postVMArtifact(VMArtifact.Type.JFR, recording.id, attributes);
        logger().info("posted recording artifact " + recording.id);
    }

    private void patch(RecordingMirror original, RecordingMirror recording) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("state", recording.state.toString());
        if (recording.state == RecordingState.STOPPED || recording.state == RecordingState.CLOSED)
            attributes.put("stopTime", recording.stopTime.toEpochMilli());
        client.postVMArtifactPatch(VMArtifact.Type.JFR, original.id, attributes);
        logger().info("patching recording " + original.id);
    }

    public void stop(long deadline) {
        if (isJfrInitialized) {
            synchronized (shutdownJfrMonitor) {
                if (isJfrInitialized) {
                    if (DEBUG) logger().info("waiting for jfr to shutdown");
                    try {
                        shutdownJfrMonitor.wait();
                    }
                    catch (InterruptedException ignored) {}
                    if (DEBUG) logger().info("unblocked CRS client shutdown");
                }
            }
        }
    }

    @Override
    public void finishJoin() {
        // check if already shut down or not initialized
        if (!running)
            return;

        if (DEBUG) logger().info("shutting down JFR");

        if (!isJfrInitialized) {
            logger().error("invalid shutdown sequence. expecting functional JFR");
            return;
        }

        if (!noLifeTimeJfr && theRecording.getState() == RecordingState.RUNNING)
            theRecording.stop();

        running = false;
        executor.shutdown();
        try {
            if (!executor.isTerminated()) {
                if (DEBUG) {
                    logger().info("awaiting flush to cloud");
                    System.out.flush();
                }
            }
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        client.finishChunkPost();

        synchronized (shutdownJfrMonitor) {
            isJfrInitialized = false;
            shutdownJfrMonitor.notify();
        }
        if (DEBUG) logger().info("JFR tracking finished");
    }

    @Override
    public void recorderInitialized(FlightRecorder recorder) {
        try {
            access = FlightRecorderAccess.getAccess(recorder, JFRMonitor.this);
            isJfrInitialized = true;
        } catch (FlightRecorderAccess.AccessException ex) {
            JFRMonitor.this.logger().error("cannot install associate to JFR " + ex.getCause().toString());
        }
    }

    @Override
    public void recordingStateChanged(Recording recording) {
        if (DEBUG) logger().info("recording "+recording.getName()+" state changed "+ recording.getState());
        final RecordingMirror rm = new RecordingMirror(recording);
        // lazily initialize list of known recordings before we process any event from JFR
        if (knownRecordings == null)
            initKnownRecordins();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                if (DEBUG) JFRMonitor.this.logger().info("recording " + rm.name + " state changed " + rm.state);
                switch (rm.state) {
                    case RUNNING:
                        if (!knownRecordings.keySet().contains(rm)) {
                            knownRecordings.put(rm, rm);
                            JFRMonitor.this.send(rm);
                        }
                        break;
                    case STOPPED:
                    case CLOSED: // state can be changed from RUNNING to CLOSED without intermediate STOPPED
                        RecordingMirror original = knownRecordings.remove(rm);
                        if (original != null) {
                            JFRMonitor.this.patch(original, rm);
                        }
                        if (DEBUG) JFRMonitor.this.logger().info("stopped recording " + rm.name);
                        break;
                }
            }
        });
    }

    @Override
    public void nextChunk(final Object chunk, Path path, Instant startTime, Instant endTime, long size, Recording ignoreMe) {
        try {
            access.useRepositoryChunk(chunk);
        } catch (FlightRecorderAccess.AccessException shouldnothappen) {
            shouldnothappen.printStackTrace();
        }
        if (!isJfrInitialized) {
            logger().error("Out of order chunk notification. Ignored");
            return;
        }
        if (DEBUG) logger().info("scheduling chunk "+path);
        // lazily initialize list of known recordings before we process any event from JFR
        if (knownRecordings == null)
            initKnownRecordins();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                if (!isJfrInitialized) {
                    JFRMonitor.this.logger().error("out of order processing of chunk");
                    return;
                }
                Set<RecordingMirror> relatedRecordings = new HashSet<>(knownRecordings.size());
                for (RecordingMirror r : knownRecordings.keySet()) {
                    if (!r.equals(ignoreMe)) {
                        relatedRecordings.add(r);
                        if (DEBUG) JFRMonitor.this.logger().info("got chunk for recording " + r.name);
                    } else if (DEBUG) JFRMonitor.this.logger().info("got chunk for ignored recording " + r.name);
                }
                if (relatedRecordings.isEmpty())
                    JFRMonitor.this.logger().error("found chunk which does not relate to any of " + knownRecordings.size() + " recordings");
                else
                    JFRMonitor.this.send(chunk, path, startTime, endTime == null ? Instant.now() : endTime, size, relatedRecordings);
            }
        });
    }

    private synchronized void initKnownRecordins() {
        if (knownRecordings == null) {
            // register recordings which has started before this needs to be done lazily
            knownRecordings = new HashMap<>();
            // this will schedule task to populate knownRecordings with relevant info
            for (Recording r: fr.getRecordings()) {
                recordingStateChanged(r);
            }
        }
    }
}
