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
import com.azul.crs.shared.models.VMArtifact;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.azul.crs.shared.models.VMArtifact.Type.GC_LOG;
import com.azul.crs.util.logging.LogChannel;

@LogChannel("service.gclog")
public class GCLogMonitor implements ClientService {
    private static long CHECK_DELAY = 1000;       // delay between GC log file checks for new data (ms)
    private static int BUFFER_SIZE = 100 * 1024;  // input buffer size to read GC log data (bytes)

    enum Option {
        /** Option pattern must have one or two groups, the 1st group must be an option name */
        LOG_GC                   ("-X(loggc):(\\S+)"),
        XLOG                     ("-X(log):(\\S+)"),
        PRINT_GC                 ("-XX:\\+(PrintGC)"),
        PRINT_GC_DETAILS         ("-XX:\\+(PrintGCDetails)"),
        PRINT_GC_TIME_STAMPS     ("-XX:\\+(PrintGCTimeStamps)"),
        PRINT_GC_DATE_STAMPS     ("-XX:\\+(PrintGCDateStamps)"),
        PRINT_HEAP_AT_GC         ("-XX:\\+(PrintHeapAtGC)"),
        USE_GC_LOG_FILE_ROTATION ("-XX:\\+(UseGCLogFileRotation)"),
        NUMBER_OF_GC_LOG_FILES   ("-XX:(NumberOfGCLogFiles)=(\\S+)"),
        GC_LOG_FILE_SIZE         ("-XX:(GCLogFileSize)=(\\S+)");

        private final Pattern pattern;
        private final String flag;

        Option(String regex) {
            this.flag = regex.substring(regex.indexOf('(') + 1, regex.indexOf(')'));
            this.pattern = Pattern.compile(regex);
        }
        public Pattern pattern() { return pattern; }
        public String flag() { return flag; }

        public boolean matchAndSet(String s, Map<String, Object> options) {
            Matcher matcher = pattern.matcher(s);
            if (matcher.matches()) {
                String name = matcher.group(1);
                Object value = matcher.groupCount() > 1 ? matcher.group(2) : true;
                options.put(name, value);
                return true;
            }
            return false;
        }
    }

    private final Client client;
    private final long startTime;
    private final AtomicLong reported;

    private FileTailer tailer;
    private volatile boolean running;

    @Override
    public String serviceName() {
        return "client.service.GCLog";
    }

    private GCLogMonitor(Client client, long startTime) {
        this.client = client;
        this.startTime = startTime;
        this.reported = new AtomicLong();
    }
    public static GCLogMonitor getInstance(Client client, long startTime) {
        return new GCLogMonitor(client, startTime);
    }

    private static Map<String,?> gclogOptions() {
        Map<String, Object> options = new HashMap<>();
        List<String> jvmArgs = Inventory.jvmArgs();
        loop: for (String arg : jvmArgs)
            for (Option o : Option.values())
                if (o.matchAndSet(arg, options))
                    continue loop;

        return options;
    }

    private FileTailerListener gclogListener(int artifactId) {
        return new FileTailerListener() {
            @Override
            public void handle(byte[] data, int size) {
                client.postVMArtifactData(VMArtifact.Type.GC_LOG, artifactId, data, size);
                long reported = GCLogMonitor.this.reported.addAndGet(size);
                logger().info("appended GC log artifact %s: size=%,d bytes, reported=%,d bytes", artifactId, size, reported);
            }
            @Override
            public void handle(Exception ex) {
                logger().error("failed to tail GC log file: %s", ex.toString());
            }

            @Override public void fileRotated(String details) { logger().info("GC log file rotated: " + details); }
            @Override public void fileNotFound() { logger().info("GC log file not found"); }
            @Override public void interrupted() { logger().info("GC log tailing interrupted"); }
        };
    }

    private String getGClogFileName11(String xlogArgs) {
      String res = null;
      String w = xlogArgs;

      if (w != null && w.matches("^[^:]*(gc|all)[^:]*:.*$")) {
        // this is gc log option
        // remove level info
        w = w.replaceFirst("^[^:]*:", "");
        w = w.replaceFirst("file=", "");
        if (w.startsWith("\"")) {
          w = w.substring(1, w.indexOf("\"", 1));
        } else {
          int until = w.indexOf(":");
          if (System.getProperty("os.name").contains("Windows")) {
            // Handling Windows paths such as C:\...
            // take a look to src/hotspot/share/logging/logConfiguration.cpp
            // LogConfiguration::parse_command_line_arguments(const char* opts)
            if (":\\".equals(w.substring(until, until + 2))) {
              until = w.indexOf(":", until + 1);
            }
          }
          w = w.substring(0, until);
        }
        res = w;
      }
      return res;
    }

    @Override
    public synchronized void start() {
        if (running) throw new IllegalStateException(serviceName() + " is running already");
        Map<String,?> options = gclogOptions();
        String gclogFileName = null;

        // 1.8.0 case
        gclogFileName = (String) options.get(Option.LOG_GC.flag());
        // check 11+ case
        if (gclogFileName == null)
          gclogFileName = getGClogFileName11((String) options.get(Option.XLOG.flag()));

        if (gclogFileName == null) return;

        if (gclogFileName.indexOf("%t") >= 0 || gclogFileName.indexOf("%p") >= 0) {
            logger().info("unsupported '%' macros in GC log file name");
            return;
        }

        int artifactId = client.createArtifactId();
        logger().info("created VM artifact: " + artifactId);

        File gclogFile = new File(gclogFileName);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", gclogFile.getName());
        metadata.put("tags", Inventory.instanceTags());
        metadata.put("options", options);

        client.postVMArtifact(GC_LOG, artifactId, metadata);

        FileTailerListener listener = gclogListener(artifactId);

        // On GC logs rotation use dedicated file tailer
        if (Boolean.TRUE.equals(options.get(Option.USE_GC_LOG_FILE_ROTATION.flag()))) {
            String logCountStr = (String) options.get(Option.NUMBER_OF_GC_LOG_FILES.flag());
            int logCount = Integer.parseInt(logCountStr);

            logger().info("GC log rotation requested: logCount=" + logCount);
            tailer = new GCRotatingLogTailer.Builder(gclogFile)
                .serviceName(serviceName())
                .listener(listener)
                .delayTimeout(CHECK_DELAY)
                .bufSize(BUFFER_SIZE)
                .logCount(logCount)
                .startTime(startTime)
                .build();
        } else
            tailer = new FileTailer.Builder(gclogFile)
                .serviceName(serviceName())
                .listener(listener)
                .delayTimeout(CHECK_DELAY)
                .bufSize(BUFFER_SIZE)
                .build();

        running = true;
        tailer.start();
    }

    @Override
    public synchronized void stop(long deadline) {
        if (!running) return;
        tailer.stop(deadline);
        running = false;
        logger().info("GC log monitor stopped: reported=%,d bytes", reported.get());
    }
}
