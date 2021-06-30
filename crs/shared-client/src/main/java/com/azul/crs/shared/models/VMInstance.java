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

public class VMInstance extends Payload {
    public enum State {
        /** VM instance is started but not running yet */
        STARTED,
        /** VM instance is running */
        RUNNING,
        /** VM instance is terminated */
        TERMINATED,
        /** VM instance is external and just registered */
        REGISTERED,
        /** VM instance does not respond for a long time (disconnected, crashed or any other reason) */
        OFFLINE
    }

    private String vmId;
    private String agentVersion;
    private Map<String, Object> inventory;
    private Long startTime;
    private Long lastHeardTime;
    private String owner;
    private State state;
    private State effectiveState;
    private Map<String, Object> annotations;
    private Set<String> tags;
    private String imageId;

    public String getVmId() { return vmId; }
    public String getAgentVersion() { return agentVersion; }
    public Map<String, Object> getInventory() {
        if (inventory == null) {
            inventory = new HashMap<>();
        }
        return inventory;
    }
    public Long getStartTime() { return startTime; }
    public Long getLastHeardTime() { return lastHeardTime; }
    public String getOwner() { return owner; }
    public State getState() { return state; }
    public State getEffectiveState() { return effectiveState; }
    public Map<String, Object> getAnnotations() {
        if (annotations == null)
            annotations = new HashMap<>();
        return annotations;
    }
    public Set<String> getTags() {
        if (tags == null)
            tags = new HashSet<>();
        return tags;
    }
    public String getImageId() { return imageId; }

    public void setVmId(String vmId) { this.vmId = vmId; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public void setInventory(Map<String, Object> inventory) { this.inventory = inventory; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }
    public void setLastHeardTime(Long lastHeardTime) { this.lastHeardTime = lastHeardTime; }
    public void setOwner(String owner) { this.owner = owner; }
    public void setState(State state) { this.state = state; }
    public void setEffectiveState(State effectiveState) { this.effectiveState = effectiveState; }
    public void setAnnotations(Map<String, Object> annotations) { this.annotations = annotations; }
    public void setTags(Set<String> tags) { this.tags = tags; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    /** Builder setters */
    public VMInstance vmId(String vmId) { setVmId(vmId); return this; }
    public VMInstance agentVersion(String agentVersion) { setAgentVersion(agentVersion); return this; }
    public VMInstance inventory(Map<String, Object> inventory) { setInventory(inventory); return this; }
    public VMInstance startTime(Long startTime) { setStartTime(startTime); return this; }
    public VMInstance lastHeardTime(Long lastHeardTime) { setLastHeardTime(lastHeardTime); return this; }
    public VMInstance owner(String owner) { setOwner(owner); return this; }
    public VMInstance state(State state) { setState(state); return this; }
    public VMInstance state(String state) { setState(State.valueOf(state)); return this; }
    public VMInstance effectiveState(State effectiveState) { setEffectiveState(effectiveState); return this; }
    public VMInstance annotations(Map<String, Object> annotations) { setAnnotations(annotations); return this; }
    public VMInstance tags(Set<String> tags) { setTags(tags); return this; }
    public VMInstance imageId(String imageId) { setImageId(imageId); return this; }

    /** Sets key-value pair to VM instance inventory */
    public VMInstance inventory(String key, Object value) {
        if (inventory == null)
            inventory = new HashMap<>();
        inventory.put(key, value);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VMInstance instance = (VMInstance) o;
        return Objects.equals(vmId, instance.vmId) &&
               Objects.equals(agentVersion, instance.agentVersion) &&
               Objects.equals(getInventory(), instance.getInventory()) &&
               Objects.equals(startTime, instance.startTime) &&
               Objects.equals(lastHeardTime, instance.lastHeardTime) &&
               Objects.equals(owner, instance.owner) &&
               state == instance.state &&
               Objects.equals(getAnnotations(), instance.getAnnotations()) &&
               Objects.equals(getTags(), instance.getTags()) &&
               Objects.equals(imageId, instance.imageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vmId, agentVersion, getInventory(), startTime, lastHeardTime, owner, state,
            getAnnotations(), getTags(), imageId);
    }

    /** Makes a copy of this instance */
    public VMInstance copy() {
        return new VMInstance()
            .vmId(vmId)
            .agentVersion(agentVersion)
            .inventory(new HashMap<>(getInventory()))
            .startTime(startTime)
            .lastHeardTime(lastHeardTime)
            .owner(owner)
            .state(state)
            .annotations(new HashMap<>(getAnnotations()))
            .tags(new HashSet<>(getTags()))
            .imageId(imageId);
    }
}
