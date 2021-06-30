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
 * The class represents chunk of binary data associated with one or many artifacts
 * For example, JFR chunk can belong to multiple JFR recordings, while GC log chunk
 * contributes to a single GC log artifact only
 */
public class VMArtifactChunk extends Payload {
    private String chunkId;               // Artifact chunk ID
    private Set<String> artifactIds;      // List of artifacts the chunk belongs to
    private Map<String, Object> metadata; // Artifact chunk metadata without schema
    private Long createTime;              // Epoch time of artifact chunk creation

    transient private String location;    // Transient URL presigned for chunk upload
    transient private Long size;          // Calculated chunk size

    public String getChunkId() { return chunkId; }
    public Set<String> getArtifactIds() { return artifactIds; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Long getCreateTime() { return createTime; }
    public String getLocation() { return location; }
    public Long getSize() { return size; }

    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public void setArtifactIds(Set<String> artifactIds) { this.artifactIds = artifactIds; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }
    public void setLocation(String location) { this.location = location; }
    public void setSize(Long size) { this.size = size; }

    public VMArtifactChunk chunkId(String artifactId) { setChunkId(artifactId); return this; }
    public VMArtifactChunk artifactIds(Set<String> artifactIds) { setArtifactIds(artifactIds); return this; }
    public VMArtifactChunk metadata(Map<String, Object> metadata) { setMetadata(metadata); return this; }
    public VMArtifactChunk createTime(Long createTime) { setCreateTime(createTime); return this; }
    public VMArtifactChunk location(String location) { setLocation(location); return this; }
    public VMArtifactChunk size(Long size) { setSize(size); return this; }

    /** Associates this chunk with VM artifact */
    public VMArtifactChunk artifactId(String artifactId) {
        if (artifactIds == null)
            artifactIds = new HashSet<>();
        artifactIds.add(artifactId);
        return this;
    }

    /** Sets key-value pair to chunk metadata */
    public VMArtifactChunk metadata(String key, Object value) {
        if (metadata == null)
            metadata = new HashMap<>();
        metadata.put(key, value);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VMArtifactChunk that = (VMArtifactChunk) o;
        return Objects.equals(chunkId, that.chunkId) &&
            Objects.equals(artifactIds, that.artifactIds) &&
            Objects.equals(metadata, that.metadata) &&
            Objects.equals(createTime, that.createTime)
        ;
    }

    @Override
    public int hashCode() {
        // Transient and computed fields are not hashed
        return Objects.hash(chunkId, artifactIds, metadata, createTime);
    }

    /** Makes a copy of this model */
    public VMArtifactChunk copy() {
        // Transient and computed fields are not copied
        return new VMArtifactChunk()
            .chunkId(chunkId)
            .metadata(metadata)
            .createTime(createTime)
            .artifactIds(artifactIds)
        ;
    }
}
