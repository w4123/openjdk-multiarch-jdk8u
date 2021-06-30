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

import static com.azul.crs.shared.Utils.currentTimeCount;
import static com.azul.crs.shared.Utils.elapsedTimeMillis;
import com.azul.crs.util.logging.LogChannel;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * The class to tail file changes and to notify listener of new data arrived
 * Please check original sources under ASF license, Apache Commons I/O 2.6
 *   - org.apache.commons.io.input.Tailer
 *   - org.apache.commons.io.input.TailerListener
 */
@LogChannel("service.filetailer")
public class FileTailer implements ClientService {

    private static final int DEFAULT_DELAY_TIMEOUT = 1000;  // ms
    private static final int DEFAULT_BUFSIZE = 4096;        // bytes
    private static final int EOF = -1;

    protected final String serviceName;          // Service name to substitute default one
    protected final File file;                   // The file which will be tailed
    protected final byte inputBuf[];             // Input buffer to read tailed file bytes
    protected final long delayTimeout;           // The amount of time to wait for the file to be updated (ms)
    protected final boolean fromEnd;             // Whether to tail from the end or start of file
    protected final boolean completeOnStop;      // Whether to tail available file bytes on stop
    protected final FileTailerListener listener; // The listener to notify of events when tailing
    protected volatile boolean running;          // The tailer will run as long as this value is true
    protected volatile long deadlineTimeCount;   // The deadline time to stop tailer since stop has been requested (in Util.currentTimeCount() units)
    protected Thread thread;                     // The thread to listen for file changes

    /**
     * Creates a file tailer service for the given file with a specified parameters
     * @param file file to follow
     * @param listener tailer listener to use
     * @param delayTimeout delay between checks of file for new content (ms)
     * @param fromEnd true to tail from the end of file, false to tail from the beginning
     * @param completeOnStop true to complete file reading on tailer stop, false to stop immediately
     * @param bufSize input buffer size
     */
    protected FileTailer(
        String serviceName,
        File file, FileTailerListener listener,
        long delayTimeout, boolean fromEnd,
        boolean completeOnStop, int bufSize) {

        this.file = file;
        this.delayTimeout = delayTimeout;
        this.fromEnd = fromEnd;
        this.completeOnStop = completeOnStop;
        this.inputBuf = new byte[bufSize];
        this.listener = listener;
        this.serviceName = serviceName == null ?
            ClientService.super.serviceName() : serviceName;
    }

    /** Builder to create FileTailer instance */
    public static class Builder<T extends Builder> {
        protected File file;
        protected FileTailerListener listener;
        protected long delayTimeout = DEFAULT_DELAY_TIMEOUT;
        protected int bufSize = DEFAULT_BUFSIZE;
        protected boolean fromEnd = false;
        protected boolean completeOnStop = true;
        protected String serviceName;

        public Builder(File file) { this.file = file; }
        public T listener(FileTailerListener listener) { this.listener = listener; return (T)this; }
        public T delayTimeout(long delayTimeout) { this.delayTimeout = delayTimeout; return (T)this; }
        public T bufSize(int bufSize) { this.bufSize = bufSize; return (T)this; }
        public T fromEnd(boolean fromEnd) { this.fromEnd = fromEnd; return (T)this; }
        public T completeOnStop(boolean completeOnStop) { this.completeOnStop = completeOnStop; return (T)this; }
        public T serviceName(String serviceName) { this.serviceName = serviceName; return (T)this; }
        public FileTailer build() {
            return new FileTailer(
                serviceName, file, listener,
                delayTimeout, fromEnd, completeOnStop,
                bufSize);
        }
    }

    @Override
    public String serviceName() {
        return serviceName;
    }

    /** Start FileTailer in a new daemon thread */
    public synchronized void start() {
        if (running) throw new IllegalStateException(
            serviceName() + " is running already");

        running = true;
        thread = new Thread(this::run);
        thread.setDaemon(true);
        thread.setName("CRSFileTailer");
        thread.start();
    }

    /** Allows the tailer to complete its current loop and return
     * @param deadline*/
    public synchronized void stop(long deadline) {
        if (!running)
            throw new IllegalStateException("File tailer has not been started");

        try {
            running = false;
            thread.interrupt();
            long timeoutMs = -elapsedTimeMillis(deadlineTimeCount = deadline);
            if (timeoutMs > 0)
                thread.join(timeoutMs);
        } catch (InterruptedException ie) {}
    }

    /** Follows changes in the file calling listener for new bytes */
    protected void run() {
        FileInputStream reader = null;
        try {
            long checkTime = 0; // the last time the file was checked for changes
            long position = 0;  // position within the file

            // Open the file and set the position
            logger().info("looking for file %s", file.getName());
            while (running && reader == null) {
                try {
                    reader = new FileInputStream(file);
                } catch (final FileNotFoundException e) {
                    listener.fileNotFound();
                }
                if (reader == null) {
                    Thread.sleep(delayTimeout);
                } else try {
                    // Read file bytes on opening even if service stop is requested already
                    position = fromEnd ? reader.skip(file.length()) : readBytes(reader);
                    checkTime = file.lastModified();
                } catch (IOException ioe) { listener.handle(ioe); }
            }

            // Main loop of file tailing
            logger().info("tailing file %s", file.getName());
            while (running) {
                final boolean newer = file.lastModified() > checkTime;
                // Check the file length to see if it was rotated
                final long length = file.length();
                if (length < position) {
                    // File was rotated
                    listener.fileRotated("");
                    // Reopen the reader after rotation ensuring that the old
                    // file is closed iff we re-open it successfully
                    try (FileInputStream saved = reader) {
                        reader = new FileInputStream(file);
                        // At this point, we're sure that the old file is rotated
                        // Finish scanning the old file and then we'll start with the new one
                        readBytes(saved);
                        position = 0;
                    } catch (final FileNotFoundException e) {
                        // Continue to use the previous reader and position values
                        listener.fileNotFound();
                        Thread.sleep(delayTimeout);
                    }
                    continue;
                }

                // File was not rotated, see if it needs to be read again
                if (length > position) {
                    // File has more content than it did last time
                    position += readBytes(reader);
                    checkTime = file.lastModified();
                } else if (newer) {
                    // File is truncated or overwritten with the exact same length, reopen
                    try {
                        reader = new FileInputStream(file);
                        position = readBytes(reader);
                        checkTime = file.lastModified();

                    } catch (final FileNotFoundException e) {
                        // Continue to use the previous reader and position values
                        listener.fileNotFound();
                        Thread.sleep(delayTimeout);
                    }
                }
                Thread.sleep(delayTimeout);
            } // while (running)
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

    /** Optionally reads available data and closes reader */
    protected void closeReader(FileInputStream reader) {
        if (reader != null) {
            try {
                if (completeOnStop)
                    readBytes(reader);
                reader.close();
            } catch (final IOException e) {
                listener.handle(e);
            }
        }
    }

    /**
     * Reads new bytes and handles them to listener
     * @param reader the file to read
     * @return number of bytes read from the input stream
     */
    protected long readBytes(FileInputStream reader) {
        int total = 0;
        try {
            while (running || (completeOnStop && currentTimeCount() < deadlineTimeCount)) {
                int num = reader.available() > 0 ? reader.read(inputBuf) : EOF;
                if (num <= 0) break;
                total += num;
                listener.handle(inputBuf, num);
            }
            listener.eofReached();
            return total;
        } catch (IOException ioe) {
            listener.handle(ioe);
            return total;
        }
    }
}