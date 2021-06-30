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

import com.azul.crs.client.service.EventService;
import com.azul.crs.client.service.UploadService;
import static com.azul.crs.shared.Utils.currentTimeMillis;
import static com.azul.crs.shared.Utils.nextTimeCount;
import com.azul.crs.shared.models.Payload;
import com.azul.crs.shared.models.VMArtifact;
import com.azul.crs.shared.models.VMArtifactChunk;
import com.azul.crs.shared.models.VMEvent;
import static com.azul.crs.shared.models.VMEvent.Type.*;
import com.azul.crs.shared.models.VMInstance;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/** CRS client provides functional API to CRS agents and hides CRS backend and infrastructure details */
public class Client {

    public interface ClientListener {
        void authenticated();
        void syncFailed(Result<String[]> reason);
    }

    public interface UploadListener<T extends Payload> {
        void uploadComplete(T request);
        void uploadFailed(T request, Result<String[]> result);
    }

    /** Recognized Client properties */
    public enum ClientProp {
        API_URL                    ("api.url", true),
        API_MAILBOX                ("api.mailbox", true),
        KS                         ("ks", false),
        HEAP_BUFFER_SIZE           ("heapBufferSize", false),
        FILE_SYSTEM_BUFFER_SIZE    ("fileSystemBufferSize", false),
        FILE_SYSTEM_BUFFER_LOCATION("fileSystemBufferLocation", false),
        NUM_CONCURRENT_CONNECTIONS ("numConcurrentConnections", false), // maximum number of simultaneous connection to the cloud
        BACKUP_JFR_CHUNKS          ("backupJfrChunks", false), // bool, backup JFR data which is pending send to cloud, otherwise the data is marked as "used" and counts toward JFR own file space quotas
        VM_SHUTDOWN_DELAY          ("delayShutdownInternal", true); // how much time to wait for data to be sent to the cloud during VM shutdown

        private final Object value;
        private final boolean mandatory;
        ClientProp(String value, boolean mandatory) { this.value = value; this.mandatory = mandatory; }
        Object value() { return value; }
        boolean isMandatory() { return mandatory; }
    }

    private final ConnectionManager connectionManager;
    private final UploadService uploadService;
    private final EventService eventService;
    private final AtomicInteger nextArtifactId = new AtomicInteger();
    private final AtomicLong nextArtifactChunkId = new AtomicLong();
    private static volatile long vmShutdownDeadline;
    private long vmShutdownDelay;

    private String vmId;

    /** Validates whether all client properties are provided */
    private void validateProps(Map<ClientProp, Object> props) {
        for (ClientProp p : ClientProp.values())
            if (p.isMandatory() && props.get(p) == null)
                throw new IllegalArgumentException(
                    "Invalid CRS properties file: missing value for " + p.value());
    }

    /** Constructs CRS client with cloud properties provided in a given file */
    public Client(Map<ClientProp, Object> props, final ClientListener listener) {
        validateProps(props);

        vmShutdownDelay = (long) props.get(ClientProp.VM_SHUTDOWN_DELAY);
        eventService = EventService.getInstance(this);
        connectionManager = new ConnectionManager(props, this, new ConnectionManager.ConnectionListener() {

            @Override
            public void authenticated() {
                vmId = connectionManager.getVmId();
                listener.authenticated();
            }

            @Override
            public void syncFailed(Result<String[]> reason) {
                // TODO remember and report the problem to the cloud, avoid infinite recursion
                listener.syncFailed(reason);
            }
        });
        uploadService = new UploadService(this);
    }

    /** Posts VM start event to register in CRS new VM instance with given inventory */
    public void postVMStart(Map<String, Object> inventory, long startTime) throws IOException {
        postVMEvent(new VMEvent<VMInstance>()
            .eventType(VMEvent.Type.VM_CREATE)
            .eventPayload(new VMInstance()
                .agentVersion(getClientVersion())
                .owner(connectionManager.getMailbox())
                .inventory(inventory)
                .startTime(startTime)
                )
        );
    }

    public void patchInventory(Map<String, Object> inventory) {
        postVMEvent(new VMEvent<VMInstance>()
            .eventType(VM_PATCH)
            .eventPayload(new VMInstance()
                .inventory(inventory))
        );
    }

    /** Posts VM event associated with VM instance known to CRS */
    public void postVMEvent(VMEvent event) {
        eventService.add(event
            .randomEventId()
        );
    }

    /** Posts VM shutdown event for VM instance known to CRS */
    public void postVMShutdown(Collection<VMEvent> trailingEvents) {
        eventService.addAll(trailingEvents);
        eventService.add(new VMEvent()
            .eventType(VM_SHUTDOWN)
            .eventTime(currentTimeMillis()));
    }

    public int createArtifactId() {
        return nextArtifactId.incrementAndGet();
    }

    public long createArtifactChunkId() {
        return nextArtifactChunkId.incrementAndGet();
    }

    public void postVMArtifact(VMArtifact.Type type, int artifactId, Map<String, Object> attributes) {
        postVMEvent(new VMEvent<VMArtifact>()
            .eventType(VM_ARTIFACT_CREATE)
            .eventPayload(new VMArtifact()
                .artifactType(type)
                .artifactId(artifactIdToString(artifactId))
                .metadata(attributes)));
    }

    public void postVMArtifactPatch(VMArtifact.Type type, int artifactId, Map<String, Object> attributes) {
        postVMEvent(new VMEvent<VMArtifact>()
            .eventType(VM_ARTIFACT_PATCH)
            .eventPayload(new VMArtifact()
                .artifactType(type)
                .artifactId(artifactIdToString(artifactId))
                .metadata(attributes)));
    }

    public void postVMArtifactData(VMArtifact.Type type, int artifactId, byte[] data, int size) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("artifactId", artifactIdToString(artifactId));
        payload.put("data", new String(data, 0, 0, size)); // TODO send artifact data in proper encoding, remember AWS does not accept real binary
        postVMEvent(new VMEvent<Map>()
            .eventType(VM_ARTIFACT_DATA)
            .eventPayload(payload));
        PerformanceMetrics.logArtifactBytes(size);
    }

    public void postVMArtifactChunk(Set<String> artifactIds, Map<String, Object> attributes, File chunkData,
                                    UploadListener<VMArtifactChunk> listener) {
        uploadService.post(new VMArtifactChunk()
            .artifactIds(artifactIds)
            .metadata(attributes), chunkData, listener);
    }

    public void finishChunkPost() {
        // this method might race with shutdown notification from the Agent. need to support both sequences
        long shutdownDeadline = vmShutdownDeadline;
        uploadService.sync(shutdownDeadline > 0 ? shutdownDeadline : nextTimeCount(vmShutdownDelay));
    }

    public static String artifactIdToString(int artifactId) {
        return Integer.toString(artifactId, 36);
    }

    public void startup() throws IOException {
        connectionManager.start();
        eventService.start();
        uploadService.start();
    }

    public void connectionEstablished() {
        eventService.connectionEstablished();
        uploadService.connectionEstablished();
    }

    public static boolean isVMShutdownInitiated() { return vmShutdownDeadline > 0; }

    public static void setVMShutdownInitiated(long deadline) { vmShutdownDeadline = deadline; }

    public static long getVMShutdownDeadline() { return vmShutdownDeadline; }

    /**
     * Proper shutdown of Client operation.
     * @param deadline the time shutdown shall complete
     */
    public void shutdown(long deadline) {
        eventService.stop(deadline);
        uploadService.stop(deadline);
    }

    /**
     * Forcibly cancel pending operations. To be used in case of abnormal termination of CRS agent.
     */
    public void cancel() {
        eventService.cancel();
        uploadService.cancel();
    }

    public String getVmId() { return vmId; }

    public String getClientVersion() throws IOException { return new Version().clientVersion(); }

    public String getMailbox() { return connectionManager.getMailbox(); }

    public String getRestAPI() { return connectionManager.getRestAPI(); }

    public ConnectionManager getConnectionManager() { return connectionManager; }
}
