/*
 * Copyright 2019-2021 Azul Systems,
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
package com.azul.crs.shared.models;

import java.util.*;

/**
 * The class represents binary artifact associated with VM instance, e.g. GC log file or JFR recording
 * Artifact consists of one or many chunks represented with VMArtifactChunk model. Binary content of an
 * artifact is not part of VM artifact / chunk models, it is hosted by a suitable storage and referenced
 * by the models
 *
 * Artifact chunks can be uploaded with one shot, or with a number of appends. Appended data can be combined
 * for efficiency and cost reason. Artifact chunks are consolidated into snapshot when consumer requests
 * artifact content as a single binary blob
 */
public class VMArtifact extends Payload {
    public enum Type {
        GC_LOG,  // GC log
        VM_LOG,  // VM log
        JFR,     // Java Flight Recording
        DUMMY,   // Dummy VM artifact created implicitly
    }

    private String artifactId;            // VM artifact ID
    private Type artifactType;            // VM artifact type
    private Map<String, Object> metadata; // VM artifact metadata without schema
    private String vmId;                  // VM instance associated with this artifact
    private List<VMArtifactChunk> chunks; // Chunks with binary data of the artifact
    private Long createTime;              // Epoch time of the artifact creation

    transient private String snapshot;   // Transient URL presigned for artifact snapshot download
    transient private Long size;         // Calculated total size of artifact chunks

    public String getArtifactId() { return artifactId; }
    public Type getArtifactType() { return artifactType; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getVmId() { return vmId; }
    public List<VMArtifactChunk> getChunks() { return chunks; }
    public String getSnapshot() { return snapshot; }
    public Long getCreateTime() { return createTime; }
    public Long getSize() { return size; }

    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public void setArtifactType(Type artifactType) { this.artifactType = artifactType; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public void setVmId(String vmId) { this.vmId = vmId; }
    public void setChunks(List<VMArtifactChunk> chunks) { this.chunks = chunks; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }
    public void setSize(Long size) { this.size = size; }

    public VMArtifact artifactId(String artifactId) { setArtifactId(artifactId); return this; }
    public VMArtifact artifactType(Type artifactType) { setArtifactType(artifactType); return this; }
    public VMArtifact artifactType(String artifactType) { if (artifactType != null) setArtifactType(Type.valueOf(artifactType)); return this; }
    public VMArtifact metadata(Map<String, Object> metadata) { setMetadata(metadata); return this; }
    public VMArtifact vmId(String vmId) { setVmId(vmId); return this; }
    public VMArtifact snapshot(String snapshot) { setSnapshot(snapshot); return this; }
    public VMArtifact createTime(Long createTime) { setCreateTime(createTime); return this; }
    public VMArtifact chunks(List<VMArtifactChunk> chunks) { setChunks(chunks); return this; }
    public VMArtifact size(Long size) { setSize(size); return this; }

    /** Gets last chunk of the artifact or null */
    public VMArtifactChunk getLastChunk() {
        return chunks != null && !chunks.isEmpty() ?
            chunks.get(chunks.size() - 1) :
            null;
    }

    /** Gets artifact chunk with requested id, or null */
    public VMArtifactChunk getChunk(String chunkId) {
        return chunks.stream()
            .filter(c -> c.getChunkId().equals(chunkId))
            .findFirst()
            .orElse(null);
    }

    /** Sets key-value pair to VM artifact metadata */
    public VMArtifact metadata(String key, Object value) {
        if (metadata == null)
            metadata = new HashMap<>();
        metadata.put(key, value);
        return this;
    }

    /** Adds new chunk reference to the end of chunk list */
    public VMArtifact chunk(VMArtifactChunk chunk) {
        if (chunks == null)
            chunks = new LinkedList<>();
        chunks.add(chunk);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        // Transient and computed fields are not compared
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VMArtifact that = (VMArtifact) o;
        return Objects.equals(artifactId, that.artifactId) &&
            artifactType == that.artifactType &&
            Objects.equals(metadata, that.metadata) &&
            Objects.equals(vmId, that.vmId) &&
            Objects.equals(chunks, that.chunks) &&
            Objects.equals(createTime, that.createTime)
        ;
    }

    @Override
    public int hashCode() {
        // Transient and computed fields are not hashed
        return Objects.hash(artifactId, artifactType, metadata, vmId, chunks, createTime);
    }

    /** Makes a copy of this model */
    public VMArtifact copy() {
        // Transient and computed fields are not copied
        return new VMArtifact()
            .artifactId(artifactId)
            .artifactType(artifactType)
            .metadata(metadata)
            .vmId(vmId)
            .chunks(chunks)
            .createTime(createTime)
        ;
    }
}
