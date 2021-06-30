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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import static java.lang.String.format;
import com.azul.crs.util.logging.LogChannel;

/**
 * GC log rotation by JVM is done in a way unusable for external tailers to follow the changes:
 * A set of log files <logName>.<logNum> is used in a circle, additional marker suffix 'current'
 * is added to currently active log file.
 *
 * The class bellow implements heuristic tailing of GC log files starting from a log marked
 * as current. On each check the tailer reports new available bytes to a listener, and tries
 * to run after the latest current log file reporting to a listener everything on the way.
 * In the case of too frequent rotation with a very few log files the tailer won't be able
 * to report all the changes and will loose some of them. A slow reporting listener will lose
 * quickly changing log changes so well.
 *
 * Said above the feature is experimental, an appropriate listening to GC log records should
 * be done over more tight integration with JVM runtime.
 */
@LogChannel("service.gclog")
public class GCRotatingLogTailer extends FileTailer {

    // Marker suffix of the current log file on rotation
    private static final String CURRENT = "current";

    private long startTime; // start time to ignore any older log files (epoch millis)
    private int logCount;   // number of log files on rotation

    /**
     * Creates a GCRotatingLogTailer for the given file with a specified buffer size
     *
     * @param file the file to follow
     * @param listener the TailerListener to use
     * @param delayTimeout the delay between checks of the file for new content in milliseconds
     * @param bufSize input buffer size
     * @param logCount number of log files on rotation
     * @param startTime start time of VM to ignore older log leftovers
     */
    protected GCRotatingLogTailer(
            String serviceName,
            File file, FileTailerListener listener,
            long delayTimeout, boolean completeOnStop,
            int bufSize, int logCount, long startTime) {

        super(serviceName, file, listener,
              delayTimeout, false /* fromEnd */,
              completeOnStop, bufSize);

        this.logCount = logCount;
        this.startTime = startTime;
    }

    public static class Builder extends FileTailer.Builder<Builder> {
        private int logCount;
        private long startTime;

        public Builder(File file) { super(file); }
        public Builder logCount(int logCount) { this.logCount = logCount; return this; }
        public Builder startTime(long startTime) { this.startTime = startTime; return this; }

        public GCRotatingLogTailer build() {
            return new GCRotatingLogTailer(
                serviceName, file, listener,
                delayTimeout, completeOnStop, bufSize,
                logCount, startTime);
        }
    }

    /** Follows changes in rotating log files calling listener for each new bytes */
    protected void run() {
        FileInputStream reader = null;
        try {
            String logName = file.getPath();

            int logNum = 0;
            File logFile = null;
            long checkTime = startTime;

            // Find and open log file with CURRENT suffix, ignore any other logs so far
            logger().info("looking for current file of GC log %s", logName);
            while (running) {
                try {
                    logNum = 0;
                    logFile = new File(format("%s.%d.%s", logName, logNum, CURRENT));
                    if (logFile.lastModified() > checkTime) {
                        reader = new FileInputStream(logFile);
                        checkTime = logFile.lastModified();
                        // Read file bytes on opening even if service stop is requested already
                        readBytes(reader);
                        break;
                    }
                } catch (FileNotFoundException e) {
                    listener.fileNotFound();
                }
                logNum = (logNum + 1) % logCount;
                Thread.sleep(delayTimeout / logCount);
            }

            // Main loop to tail rotating logs
            logger().info("tailing GC log starting from file %s", logFile.getName());
            while (running) {

                readBytes(reader);

                // Log rotation happened, try to find next CURRENT log and
                // read all next new but not CURRENT logs on the way
                if (!logFile.exists())
                    while (running) {
                        logNum = (logNum + 1) % logCount;

                        // Check whether CURRENT marker moved to next file
                        logFile = new File(format("%s.%d.%s", logName, logNum, CURRENT));
                        if (logFile.lastModified() >  checkTime) {
                            try (FileInputStream saved = reader) {
                                reader = new FileInputStream(logFile);
                                checkTime = logFile.lastModified();
                                listener.fileRotated("current log number " + logNum);
                                readBytes(reader);
                                break;
                            } catch (FileNotFoundException e) {}
                        }
                        // Check whether next file is modified but has no CURRENT marker already,
                        // if so read next log file and move forward to look for CURRENT marker
                        logFile = new File(format("%s.%d", logName, logNum));
                        if (logFile.lastModified() >  checkTime) {
                            try (FileInputStream saved = reader) {
                                reader = new FileInputStream(logFile);
                                checkTime = logFile.lastModified();
                                listener.fileRotated("next log number " + logNum);
                                readBytes(reader);
                                continue;
                            } catch (FileNotFoundException e) {
                                listener.fileNotFound();
                            }
                        }
                        // Neither next CURRENT log, nor next newer log are found
                        // Stay with the same log and wait until next one appears
                        logNum = (logNum - 1) % logCount;
                    }

                // Sleep till next check of the logs
                Thread.sleep(delayTimeout);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.interrupted();
        } catch (final Exception e) {
            listener.handle(e);
        } finally {
            closeReader(reader);
            running = false;
        }
    }
}
