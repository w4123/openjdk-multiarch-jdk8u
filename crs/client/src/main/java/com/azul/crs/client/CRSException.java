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

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Formattable;
import java.util.Formatter;

public class CRSException extends IOException {
    public static final int REASON_NO_ENDPOINT = -1;
    public static final int AUTHENTICATION_FAILURE = -2;
    public static final int REASON_GENERIC = -3;
    public static final int REASON_INTERNAL_ERROR = -4;

    private static final String MESSAGE_NO_ENDPOINT = "No CRS endpoint found.\nPlease specify via command line arguments or verify if your DNS has CRS record provisioned";
    private static final String MESSAGE_ENDPOINT_AUTHENTICATION_FAILED = "CRS endpoint authentication error.\nPlease ensure you have proper endpoint address specified in command line or your DNS settings.";
    private static final String MESSAGE_ENDPOINT_ADDRESS = "\n API endpoint address configured: ";

    private final int reason;
    private final Result result;
    private final Client client;

    public CRSException(int reason) {
        this(null, reason, null, null);
    }

    public CRSException(int reason, String message, Throwable cause) {
        super(message, cause);
        this.client = null;
        this.reason = reason;
        this.result = null;
    }

    public CRSException(Client client, int reason, String message, Result result) {
        super(message);
        this.client = client;
        this.reason = reason;
        this.result = result;
        if (result.hasException())
            initCause(result.getException());
    }

    /**
     * This is very special implementation which uses heuristics to get human-readable message
     * about the problem.
     *
     * @return the message for the human which could be used to understand the problem
     */
    @Override public String toString() {
        StringBuilder message = new StringBuilder();
        switch (reason) {
            case REASON_NO_ENDPOINT:
                message.append(MESSAGE_NO_ENDPOINT);
                break;
            case AUTHENTICATION_FAILURE: {
                if (getCause() != null) {
                    if (getCause() instanceof SSLHandshakeException)
                        message.append(MESSAGE_ENDPOINT_AUTHENTICATION_FAILED);
                    else if (getCause() instanceof UnknownHostException)
                        message.append(MESSAGE_NO_ENDPOINT);
                    else
                        message.append(getMessage()).append(result.errorString());
                } else {
                    message.append(getMessage());
                    if (result != null)
                        message.append(result.errorString());
                }
                if (client != null && client.getRestAPI() != null)
                    message.append(MESSAGE_ENDPOINT_ADDRESS).append(client.getRestAPI());
            }
            break;
            default:
                message = new StringBuilder(getMessage());
                if (getCause() != null)
                    message.append("\nCaused by: ").append(getCause().toString());
        }
        return message.toString();
    }
}
