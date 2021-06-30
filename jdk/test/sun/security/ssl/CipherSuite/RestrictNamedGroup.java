/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8226374
 * @library /javax/net/ssl/templates
 * @summary Restrict signature algorithms and named groups
 * @run main/othervm RestrictNamedGroup secp256r1
 * @run main/othervm RestrictNamedGroup secp384r1
 * @run main/othervm RestrictNamedGroup secp521r1
 */

import java.security.Security;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLException;

public class RestrictNamedGroup extends SSLSocketTemplate {

    private static volatile int index;
    // Servers are configured before clients, increment test case after.
    @Override
    protected void configureClientSocket(SSLSocket socket) {
        socket.setEnabledCipherSuites(new String[] {
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"});
    }

    @Override
    protected void configureServerSocket(SSLServerSocket serverSocket) {
        serverSocket.setEnabledCipherSuites(new String[] {
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"});
    }

    /*
     * Run the test case.
     */
    public static void main(String[] args) throws Exception {
        Security.setProperty("jdk.tls.disabledAlgorithms", args[0]);
        System.setProperty("jdk.tls.namedGroups", args[0]);

            try {
                (new RestrictNamedGroup()).run();
                throw new Exception("The named group " + args[0] + " should be disabled");
            } catch (SSLException | IllegalStateException ssle) {
                // The named group should be restricted.
            }
    }
}
