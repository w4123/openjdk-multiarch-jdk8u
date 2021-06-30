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
package com.azul.crs.client;

import com.azul.crs.client.service.*;
import com.azul.crs.client.util.DnsDetect;
import static com.azul.crs.shared.Utils.currentTimeCount;
import static com.azul.crs.shared.Utils.elapsedTimeMillis;
import static com.azul.crs.shared.Utils.nextTimeCount;
import com.azul.crs.shared.models.VMEvent;
import sun.launcher.LauncherHelper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.azul.crs.util.logging.Logger;
import static java.lang.System.currentTimeMillis;
import com.azul.crs.util.logging.LogChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@LogChannel("id")
class Agent001 {
    private static final int CRS_DISABLED = -1;
    private static final int CRS_NOT_YET_DECIDED = 0;
    private static final int CRS_LAUNCHER_DETECTED = 1;
    private static final int CRS_ENABLED_FORCED = 2;
    private static final int CRS_ENABLED_MAIN_KNOWN = 3;

    private static HeartbeatService heartbeatService;
    // Still used by Zulu, until vmlogs support is added
    private static GCLogMonitor gclogMonitor;
    // Used by Zing
    private static VMLogMonitor vmlogMonitor;
    private static JFRMonitor jfrMonitor;
    private static ClassLoadMonitor classLoadMonitor;
    private static FirstCallMonitor firstCallMonitor;
    private static Client client;
    private static volatile int useCRS = CRS_NOT_YET_DECIDED;
    private static Thread startThread;
    private static volatile boolean launcherDetected;

    private static final int FLUSH_THREAD_DEFAULT_PERIOD_MS = 1_000;
    private static final int FLUSH_THREAD_FORCE_DEFAULT_PERIOD_MS = 30*60*1000; // 30 minutes
    private static final long DEFAULT_SHUTDOWN_DELAY = 120_000; // 2 minutes
    private static final Object flushThreadLock = new Object();
    private static boolean flushThreadStop;
    private static volatile Thread flushThread;
    private static int forceFlushTimeout = FLUSH_THREAD_FORCE_DEFAULT_PERIOD_MS;
    private static long delayShutdown = DEFAULT_SHUTDOWN_DELAY;

    /** Event constants are used by native code */
    private static final int DRAIN_NATIVE_QUEUE_AND_STOP = -101;
    private static final int DRAIN_NATIVE_QUEUE = -100;
    private static final int EVENT_USE_CRS = -99;
    private static final int EVENT_TO_JAVA_CALL = -98;
    private static final int EVENT_CLASS_LOAD = 0;
    private static final int EVENT_FIRST_CALL = 1;
    // private static final int EVENT_GCLOG = ; // not yet implemented

    private final static Logger logger = Logger.getLogger(Agent001.class);

    private static boolean connectionEstablished;

    /** Sets VM shutdown hook to interrupt heartbeat thread and to report VM termination to CRS */
    private static void addShutdownHook(long startTime) {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                long shutdownDeadline = nextTimeCount(delayShutdown);
                Client.setVMShutdownInitiated(shutdownDeadline);

                long shutdownStartTime = currentTimeCount();

                try {
                    if (delayShutdown > 0) {
                        logger.trace("checking if startup is complete and waiting for it to finish (%d ms)", delayShutdown);
                        startThread.join();

                        // do not do anything if startup has failed
                        if (useCRS == CRS_DISABLED)
                            return;

                        logger.debug("drain native queue");
                        // ensure all native events are processed
                        flushThreadStop = true;
                        synchronized (flushThreadLock) {
                            flushThreadLock.notifyAll();
                        }
                        try {
                            flushThread.join();
                        } catch (InterruptedException ignored) {
                        }

                        stopServices(shutdownDeadline,
                                heartbeatService,
                                jfrMonitor,
                                vmlogMonitor,
                                gclogMonitor,
                                classLoadMonitor,
                                firstCallMonitor);

                        Map perfMonData = PerformanceMetrics.logPreShutdown(elapsedTimeMillis(shutdownStartTime));
                        postVMShutdown(client, perfMonData);
                        client.shutdown(shutdownDeadline);
                    }

                    if (client != null) {
                        PerformanceMetrics.logShutdown(elapsedTimeMillis(shutdownStartTime));
                        PerformanceMetrics.report();
                        logger.info("Agent terminated: vmId=%s, runningTime=%d", client.getVmId(),
                            elapsedTimeMillis(startTime));
                    } else
                        logger.info("Agent shut down during startup. Data is discarded");
                } catch (InterruptedException e) {
                    // here we likely do not have Client properly initialized
                    logger.error("Agent failed to process shutdown during startup. Data is discarded");
                }
            }
        }));
    }

    /** Posts VM start and gets created VM instance from response */
    private static void postVMStart(long startTime, String mainMethod) throws Exception {
        Map<String, Object> inventory = new Inventory()
            .populate()
            .mainMethod(mainMethod)
            .toMap();

        logger.trace("Post VM start to CRS service");
        client.postVMStart(inventory, startTime);
    }

    private static void sendMainMethodName(String mainMethod) {
        Map<String, Object> inventory = new Inventory()
            .mainMethod(mainMethod)
            .toMap();
        client.patchInventory(inventory);
    }

    private static void sendNetworkInformation() {
        Map<String, Object> inventory = new Inventory()
            .networkInformation()
            .toMap();
        client.patchInventory(inventory);
    }

    /** Posts shutdown event directly to CRS service not using event queue that could be full or stopped already */
    private static void postVMShutdown(Client client, Map perfMonData) {
        logger.trace("Post VM shutdown to CRS service");
        List<VMEvent> trailingEvents = new ArrayList<>();
        trailingEvents.add(new VMEvent<Map>()
            .eventType(VMEvent.Type.VM_PERFORMANCE_METRICS)
            .randomEventId()
            .eventTime(currentTimeMillis())
            .eventPayload(perfMonData));
        client.postVMShutdown(trailingEvents);
    }

    /**
     * Entry point to CRS Java agent.
     */
    public static void startAgent(String args) {
        Options.read(args);
        PerformanceMetrics.init();

        // a special case, if CRS agent is started but there is no UseCRS option specified
        // it's an indication that CRS was enabled with VM command line flag -XX:+UseCRS alone
        // for backward compatibility this is equivalent to UseCRS=yes option
        if ("force".equals(Options.useCRS.get()) || !Options.useCRS.isSet()) {
            stateEvent(CRS_ENABLED_FORCED, null);
        }
        if (Options.forceSyncTimeout.isSet())
            forceFlushTimeout = Options.forceSyncTimeout.getInt()*1000;
        if (Options.delayShutdown.isSet())
            delayShutdown = Options.delayShutdown.getLong();
        if (Options.noDelayShutdown.isSet())
            delayShutdown = 0;
        System.setProperty("com.azul.crs.instance.options.delayShutdown", Long.toString(delayShutdown));
    }

    private static synchronized void stateEvent(int state, String mainMethod) {
        switch (state) {
            case CRS_ENABLED_FORCED:
                assert(useCRS != CRS_DISABLED);
                if (useCRS == CRS_NOT_YET_DECIDED || useCRS == CRS_LAUNCHER_DETECTED) {
                    useCRS = CRS_ENABLED_FORCED;
                    activateAgent("");
                }
                break;
            case CRS_LAUNCHER_DETECTED: {
                if (useCRS == CRS_NOT_YET_DECIDED)
                    useCRS = CRS_LAUNCHER_DETECTED;
                // else useCRS == CRS_ENABLED_FORCED do not change state
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Class<?> applicationClass;
                        // let the Launcher code run for a while to determine the
                        // main class of the application. there is no way to be notified
                        // that it has done the work, so do poll
                        do {
                            applicationClass = LauncherHelper.getApplicationClass();
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                return;
                            }
                        } while (applicationClass == null);
                        stateEvent(CRS_ENABLED_MAIN_KNOWN, applicationClass.getName().replace('.', '/') + ".main");
                    }
                });
                t.setDaemon(true);
                t.start();
            }
                break;
            case CRS_ENABLED_MAIN_KNOWN:
                assert(mainMethod != null);
                if (useCRS == CRS_NOT_YET_DECIDED || useCRS == CRS_LAUNCHER_DETECTED) {
                    // check if we want to avoid activation of Connected Runtime daemon
                    if (mainMethod.startsWith("com/sun/tools")
                    ) {
                        stateEvent(CRS_DISABLED, null);
                    } else {
                        useCRS = CRS_ENABLED_MAIN_KNOWN;
                        activateAgent(mainMethod);
                    }
                } else if (useCRS == CRS_ENABLED_FORCED) {
                    assert(startThread != null);
                    useCRS = CRS_ENABLED_MAIN_KNOWN;
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                startThread.join();
                            } catch (InterruptedException e) {
                                return;
                            }
                            if (useCRS != CRS_DISABLED)
                                sendMainMethodName(mainMethod);
                        }
                    });
                    t.setDaemon(false);
                    t.start();
                }
                break;
            case CRS_DISABLED:
                useCRS = CRS_DISABLED;
                setNativeEventFilter(EVENT_USE_CRS, false);
                break;

        }
    }

    private static void activateAgent(final String mainMethod) {
        long startTime = currentTimeMillis();
        long startTimeStamp = currentTimeCount();
        if (logger.isEnabled(Logger.Level.DEBUG))
            logger.debug("CRS agent started. VM uptime %dms", ManagementFactory.getRuntimeMXBean().getUptime());
        startThread = new Thread(new Runnable() {
            @Override
            public void run() {
                addShutdownHook(startTimeStamp);

                VMCRSCapabilities capabilities = VMCRSCapabilities.init();

                try {
                    // Connect CRS client to cloud
                    client = new Client(getClientProps(), new Client.ClientListener() {

                        @Override
                        public void authenticated() {
                            if (client.getVmId() != null) {
                                logger.info("Agent authenticated: vmId=%s", client.getVmId());
                                if (logger.isEnabled(Logger.Level.DEBUG))
                                    logger.debug(" VM uptime %dms", ManagementFactory.getRuntimeMXBean().getUptime());
                                if (!connectionEstablished) {
                                    client.connectionEstablished();
                                }
                                connectionEstablished = true;
                            } else {
                                disableCRS("Backend malfunction, invalid vmId received", null);
                            }
                        }

                        @Override
                        public void syncFailed(Result<String[]> reason) {
                            logger.error("Data synchronization to the CRS cloud has failed: %s",
                                    reason.errorString());
                        }
                    });

                    jfrMonitor = JFRMonitor.getInstance(client, Options.lifetimejfr.get());
                    jfrMonitor.start();

                    postVMStart(startTime, mainMethod);

                    // Create client service instances
                    heartbeatService = HeartbeatService.getInstance(client);


                    if (capabilities.has(VMCRSCapability.POST_VM_LOG_EVENTS)) {
                        vmlogMonitor = VMLogMonitor.getInstance(client);
                    } else {
                        gclogMonitor = GCLogMonitor.getInstance(client, startTime);
                    }

                    if (capabilities.has(VMCRSCapability.POST_CLASS_LOAD_EVENTS)) {
                        classLoadMonitor = ClassLoadMonitor.getInstance(client);
                    }

                    if (capabilities.has(VMCRSCapability.POST_FIRST_CALL_EVENTS)) {
                       firstCallMonitor = FirstCallMonitor.getInstance(client);
                    }

                    // Start client services
                    client.startup();

                    startServices(
                            heartbeatService,
                            vmlogMonitor,
                            gclogMonitor,
                            classLoadMonitor,
                            firstCallMonitor);

                    flushThread = new Thread(Agent001::flushThreadMain);
                    flushThread.setDaemon(true);
                    flushThread.setName("CRSEventFlush");
                    flushThread.start();

                    // CRS connectivity is set up and can get established once server is reachable
                    // can perform long-running activities without delaying connection
                    sendNetworkInformation();
                } catch (Exception e) {
                    disableCRS("CRS failed to start: %s", e);
                }
            }
        });
        startThread.setDaemon(delayShutdown == 0);
        startThread.setName("CRSStartThread");
        startThread.start();
    }

    private static void startServices(ClientService... services) {
        for (ClientService service : services) {
            if (service != null) {
                try {
                    service.start();
                } catch (Exception ex) {
                    logger.error("Agent failed to start " + service.serviceName() + ". Data is discarded");
                }
            }
        }
    }

    private static void stopServices(long shutdownDeadline, ClientService... services) {
        for (ClientService service : services) {
            if (service != null) {
                try {
                    service.stop(shutdownDeadline);
                } catch (Exception ex) {
                    logger.error("Agent failed to stop " + service.serviceName() + ". Data is discarded");
                }
            }
        }
    }

    private static void disableCRS(String cause, Exception ex) {
        if (client != null)
            client.cancel();
        useCRS = CRS_DISABLED;
        logger.error(cause, ex);
        if (ex.getCause() != null)
            logger.trace("caused by: %s", ex.getCause());
        // TODO release resources, shutdown native
    }

    private static Map<Client.ClientProp, Object> getClientProps() throws CRSException {
        Map<Client.ClientProp, Object> clientProps = Options.getClientProps();
        boolean hasEndpointConfig = clientProps.get(Client.ClientProp.API_URL) != null;
        boolean hasMailboxConfig = clientProps.get(Client.ClientProp.API_MAILBOX) != null;
        if (!hasEndpointConfig || !hasMailboxConfig) {
            try {
                DnsDetect detector = new DnsDetect(Options.stackRecordId.get());
                Logger.getLogger(ConnectionManager.class).info("querying DNS record%s",
                    detector.getRecordNamePostfix().length() > 0 ? " (postfix "+detector.getRecordNamePostfix()+")" : "");
                if (!hasEndpointConfig)
                    clientProps.put(Client.ClientProp.API_URL, "https://" + detector.queryEndpoint());
                if (!hasMailboxConfig)
                    clientProps.put(Client.ClientProp.API_MAILBOX, detector.queryMailbox());
            } catch (IOException ex) {
                throw new CRSException(CRSException.REASON_NO_ENDPOINT, "DNS query error and not enough configuration supplied", ex);
            }
        }
        clientProps.put(Client.ClientProp.VM_SHUTDOWN_DELAY, delayShutdown);
        return clientProps;
    }

    static void flushThreadMain() {
        long previousForceFlushTime = currentTimeCount();
        while (true) {
            synchronized (flushThreadLock) {
                try {
                    flushThreadLock.wait(FLUSH_THREAD_DEFAULT_PERIOD_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (flushThreadStop)
                break;
            boolean forceFlush = elapsedTimeMillis(previousForceFlushTime) >= forceFlushTimeout;
            if (forceFlush)
                previousForceFlushTime = currentTimeCount();
            setNativeEventFilter(DRAIN_NATIVE_QUEUE, forceFlush);
        }

        setNativeEventFilter(DRAIN_NATIVE_QUEUE_AND_STOP, true);
    }

    /**
     * VM callback invoked when java method is called from native.
     *
     * @param name the name of the holder class of a method '.' method name
     */
    public static void notifyToJavaCall(String name) {
        if (useCRS != CRS_NOT_YET_DECIDED && useCRS != CRS_ENABLED_FORCED) {
            // no more calls are necessary
            setNativeEventFilter(EVENT_TO_JAVA_CALL, false);
            return;
        }

        if (name.startsWith("sun/launcher/LauncherHelper.checkAndLoadMain")) {
            launcherDetected = true;
            stateEvent(CRS_LAUNCHER_DETECTED, null);
        } else if (!launcherDetected) { // launcher takes precedence over direct invocation
            // ignore calls which are not about to indicate that application is being started
            if (name.startsWith("java/lang/Thread")
                || name.startsWith("sun/launcher")
                || name.startsWith("java/")
                || name.startsWith("javax/")
                || name.startsWith("sun/")
                || name.startsWith("com/sun/")
                || name.startsWith("com/fasterxml") // used by CRS itself
                || name.startsWith("org/jcp")
                || name.startsWith("com/azul/crs")
                || name.startsWith("jdk/jfr")
            )
                return;

            // use of sun launcher was not detected, assuming direct call from custom native code
            // in this case the best we can do is to use the name of the first method called
            stateEvent(CRS_ENABLED_MAIN_KNOWN, name);
        }
    }

    /**
     * VM callback invoked when java method was called the first time.
     *
     * @param classId id of the class this method belongs to
     * @param name the name of the holder class of a method '.' method name
     */
    public static void notifyFirstCall(int classId, String name) {
        firstCallMonitor.notifyMethodFirstCalled(classId, name);
    }

    /**
     * VM callback invoked each time class is loaded.
     *
     * className name of the loaded class
     * hash SHA-256 hash of the class file
     * classId the unique id of the class
     */
    public static void notifyClassLoad(String className, byte[] hash, int classId, int loaderId, String source) {
        classLoadMonitor.notifyClassLoad(className, hash, classId, loaderId, source);
    }

    private static enum VMCRSCapability {
        POST_CLASS_LOAD_EVENTS,
        POST_FIRST_CALL_EVENTS,
        POST_NOTIFY_TO_JAVA_CALLS,
        POST_VM_LOG_EVENTS;
    }

    private static final class VMCRSCapabilities {

        private final Set<VMCRSCapability> capabilities;

        private VMCRSCapabilities(Set<VMCRSCapability> vmcrsCapabilities) {
            this.capabilities = Collections.unmodifiableSet(vmcrsCapabilities);
            logger.trace("Active VMCRSCapabilities: " + capabilities);
        }

        boolean has(VMCRSCapability cap) {
            return capabilities.contains(cap);
        }

        static VMCRSCapabilities init() {
            try {
                String[] reported = (String[]) getVMCRSCapabilities();
                Set<VMCRSCapability> active = new HashSet<>();
                for (String vmcrsCapability : reported) {
                    try {
                        active.add(VMCRSCapability.valueOf(vmcrsCapability));
                    } catch (IllegalArgumentException e) {
                        logger.trace("VM reported unknown capability: " + vmcrsCapability);
                        // skip
                    }
                }
                return new VMCRSCapabilities(active);
            } catch (java.lang.UnsatisfiedLinkError ex) {
                // Assume default capabilities...
                return new VMCRSCapabilities(new HashSet<>(
                        Arrays.asList(
                                VMCRSCapability.POST_CLASS_LOAD_EVENTS,
                                VMCRSCapability.POST_FIRST_CALL_EVENTS,
                                VMCRSCapability.POST_NOTIFY_TO_JAVA_CALLS)));
            }
        }
    }

    /**
     * VM callback invoked each time log entry if reported by VM.
     */
    public static void notifyVMLogEntry(String logName, String entry) {
        vmlogMonitor.notifyVMLogEntry(logName, entry);
    }

    /**
     * Sets filtering of particular CRS events or switches CRS altogether.
     * Deprecated. Consider using CompilerCommand, depending on licensing.
     *
     * @param event the event to filter or {@link #EVENT_USE_CRS} to control CRS
     *              or {@link #DRAIN_NATIVE_QUEUE} to flush native queues
     * @param enabled whether event shall be enabled or not, for {@code}DRAIN_NATIVE_QUEUE{@code}
     *                the meaning is whether flush shall be forced, i.e. flushes
     *                also unfinished buffers
     */
    @Deprecated
    private static native void setNativeEventFilter(int event, boolean enabled);

    /**
     * Fetch list of VMCRSCapabilities of the current VM.
     * @return array of strings that should be attributed to the VM CRS capabilities.
     * Unknown entries in the returned array should be ignored.
     */
    private static native String[] getVMCRSCapabilities();
}
