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
package com.azul.crs.jfr.access;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.internal.FlightRecorderAssociate;
import java.nio.file.Path;
import jdk.jfr.internal.SecuritySupport;

public final class FlightRecorderAccess {

    private static final AccessException initException;
    private static final Method chunkUseMethod;
    private static final Method chunkReleaseMethod;
    private static final Method recorderSetAssociateMethod;

    static {
        final AtomicReference<AccessException> ex = new AtomicReference<>();
        final AtomicReference<Method> chunkUeMethodRef = new AtomicReference<>();
        final AtomicReference<Method> chunkReleaseMethodRef = new AtomicReference<>();
        final AtomicReference<Method> recorderSetAssociateMethodRef = new AtomicReference<>();

        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                try {
                    Method m = FlightRecorder.class.getDeclaredMethod("setAssociate", FlightRecorderAssociate.class);
                    m.setAccessible(true);
                    recorderSetAssociateMethodRef.set(m);

                    Class<?> chunkClass = Class.forName("jdk.jfr.internal.RepositoryChunk");
                    m = chunkClass.getDeclaredMethod("use");
                    m.setAccessible(true);
                    chunkUeMethodRef.set(m);

                    m = chunkClass.getDeclaredMethod("release");
                    m.setAccessible(true);
                    chunkReleaseMethodRef.set(m);
                } catch (ClassNotFoundException | NoSuchMethodException e) {
                    ex.set(new AccessException(e));
                }
                return null;
            }
        });

        initException = ex.get();
        chunkUseMethod = chunkUeMethodRef.get();
        chunkReleaseMethod = chunkReleaseMethodRef.get();
        recorderSetAssociateMethod = recorderSetAssociateMethodRef.get();
    }

    private FlightRecorderAccess() {
    }

    public void useRepositoryChunk(final Object chunk) throws AccessException {
        try {
            chunkUseMethod.invoke(chunk);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw new AccessException(ex);
        }
    }

    public void releaseRepositoryChunk(final Object chunk) throws AccessException {
        try {
            chunkReleaseMethod.invoke(chunk);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw new AccessException(ex);
        }
    }

    public static FlightRecorderAccess getAccess(final FlightRecorder fr, final FlightRecorderCallbacks callbacks) throws AccessException {
        if (initException != null) {
            throw initException;
        }

        try {
            recorderSetAssociateMethod.invoke(fr, new FlightRecorderAssociate() {
                @Override
                public void nextChunk(Object chunk, SecuritySupport.SafePath path, Instant startTime, Instant endTime, long size, Recording ignoreMe) {
                    callbacks.nextChunk(chunk, path.toPath(), startTime, endTime, size, ignoreMe);
                }

                @Override
                public void finishJoin() {
                    callbacks.finishJoin();
                }
            });
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw new AccessException(ex);
        }

        return new FlightRecorderAccess();
    }

    public static interface FlightRecorderCallbacks {

        public void nextChunk(Object chunk, Path path, Instant startTime, Instant endTime, long size, Recording ignoreMe);

        public void finishJoin();
    }

    public static final class AccessException extends Exception {

        private static final long serialVersionUID = -3710493080622598156L;

        private AccessException(Throwable cause) {
            super(cause);
        }
    }
}

