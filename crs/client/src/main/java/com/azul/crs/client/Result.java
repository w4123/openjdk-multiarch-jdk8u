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

import sun.security.validator.ValidatorException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.UnknownHostException;

public class Result<T> {
    private Response<T> response;
    private IOException exception;

    public Result(Response<T> response) {
        this.response = response;
    }

    public Result(IOException exception) {
        this.exception = exception;
    }

    public boolean hasException() { return exception != null; }
    public boolean hasResponse() { return response != null; }
    public Response<T> getResponse() { return response; }
    public IOException getException() { return exception; }

    public boolean successful() {
        return hasResponse() && response.successful();
    }

    public boolean canRetry() {
        if (hasResponse())
            return response.canRetry();

        if (exception instanceof UnknownHostException)
            return false;

        // server authentication failure is irrecoverable
        if (exception instanceof SSLHandshakeException &&
                exception.getCause() != null && exception.getCause() instanceof ValidatorException)
            return false;

        return true;
    }

    public String errorString() {
        return hasResponse() ? response.errorString() : exception.getMessage();
    }

    @Override
    public String toString() {
        return "Result{" +
            "response=" + response +
            ", exception=" + exception +
            '}';
    }
}
