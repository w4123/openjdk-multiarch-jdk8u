/*
 * Copyright (c) 2020, Azul Systems, Inc. All rights reserved.
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

/**
 * @test
 * @bug 8245527
 * @run main/othervm LdapCBPropertiesTest true com.sun.jndi.ldap.tls.cbtype tls-server-end-point com.sun.jndi.ldap.connect.timeout 2000
 * @run main/othervm LdapCBPropertiesTest false com.sun.jndi.ldap.tls.cbtype tls-server-end-point com.sun.jndi.ldap.connect.timeout 0
 * @run main/othervm LdapCBPropertiesTest false com.sun.jndi.ldap.tls.cbtype tls-server-end-point com.sun.jndi.ldap.connect.timeout -1
 * @run main/othervm LdapCBPropertiesTest false com.sun.jndi.ldap.tls.cbtype tls-server-end-point com.sun.jndi.ldap.connect.timeout unknown
 * @run main/othervm LdapCBPropertiesTest false com.sun.jndi.ldap.tls.cbtype tls-server-end-point
 * @run main/othervm LdapCBPropertiesTest false com.sun.jndi.ldap.tls.cbtype tls-unique com.sun.jndi.ldap.connect.timeout 2000
 * @run main/othervm LdapCBPropertiesTest false com.sun.jndi.ldap.tls.cbtype tls-unknown com.sun.jndi.ldap.connect.timeout 2000
 * @run main/othervm LdapCBPropertiesTest false jdk.internal.sasl.tlschannelbinding value com.sun.jndi.ldap.connect.timeout 2000
 * @run main/othervm LdapCBPropertiesTest false jdk.internal.sasl.tlschannelbinding value
 * @summary test new JNDI property to control the Channel Binding data
 */

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Hashtable;

import org.ietf.jgss.GSSException;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.security.sasl.SaslException;

public class LdapCBPropertiesTest {
    /*
     * Where do we find the keystores?
     */
    static String pathToStores = "../../../../javax/net/ssl/etc";
    static String keyStoreFile = "keystore";
    static String trustStoreFile = "truststore";
    static String passwd = "passphrase";

    static boolean debug = false;

    public static void main(String[] args) throws Exception {
        String keyFilename =
                System.getProperty("test.src", "./") + "/" + pathToStores +
                        "/" + keyStoreFile;
        String trustFilename =
                System.getProperty("test.src", "./") + "/" + pathToStores +
                        "/" + trustStoreFile;

        System.setProperty("javax.net.ssl.keyStore", keyFilename);
        System.setProperty("javax.net.ssl.keyStorePassword", passwd);
        System.setProperty("javax.net.ssl.trustStore", trustFilename);
        System.setProperty("javax.net.ssl.trustStorePassword", passwd);
        // set disableEndpointIdentification to disable hostname verification
        System.setProperty("com.sun.jndi.ldap.object.disableEndpointIdentification", "true");

        if (debug)
            System.setProperty("javax.net.debug", "all");

        /*
         * Start the tests.
         */
        new LdapCBPropertiesTest(args);
    }

    /*
     * Primary constructor, used to drive remainder of the test.
     */
    LdapCBPropertiesTest(String[] args) throws Exception {
        DummySSLServer server = new DummySSLServer();
        try {
            doClientSide(server.getServerPort(), args);
        } finally {
            server.close();
        }
    }

    /*
     * Define the client side of the test.
     *
     * The server should start at this time already
     */
    void doClientSide(int serverPort, String[] args) throws Exception {
        boolean passed = false;
        boolean shouldPass = Boolean.parseBoolean(args[0]);

        // Set up the environment for creating the initial context
        Hashtable env = new Hashtable(11);
        env.put(Context.PROVIDER_URL, "ldaps://localhost:" +
                serverPort);
        env.put(Context.INITIAL_CONTEXT_FACTORY,
                "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.SECURITY_AUTHENTICATION, "GSSAPI");

        // read properties
        for (int i = 1; i < args.length; i += 2) {
            env.put(args[i], args[i + 1]);
            if (debug)
                System.out.println("Env=" + args[i] + "=" + args[i + 1]);
        }

        try {
            DirContext ctx = new InitialDirContext(env);
            passed = shouldPass;
            ctx.close();
        } catch (NamingException ne) {
            // only NamingException is allowed
            if (debug)
                System.out.println("Exception=" + ne + " cause=" + ne.getRootCause());
            passed = handleNamingException(ne, shouldPass);
        } catch(Exception e) {
            System.err.println("Failed: caught an unexpected Exception - " + e);
            throw e;
        } finally {
            // test if internal property accessible to application
            if(shouldPass &&
                    env.get("jdk.internal.sasl.tlschannelbinding") != null) {
                throw new Exception(
                        "Test FAILED: jdk.internal.sasl.tlschannelbinding should not be accessible");
            }
        }
        if (!passed) {
            throw new Exception(
                    "Test FAILED: NamingException exception should be thrown");
        }
        System.out.println("Test PASSED");
    }

    private static boolean handleNamingException(NamingException ne, boolean shouldPass)
        throws NamingException {
        if (ne instanceof AuthenticationException &&
            ne.getRootCause() instanceof SaslException) {
            SaslException saslEx = (SaslException) ne.getRootCause();
            if (saslEx.getCause() instanceof GSSException) {
                // SSL connection successful, expected exception from SaslClient
                if (shouldPass)
                    return true;
            }
        }
        if (!shouldPass &&
                (ne.getRootCause() == null || ne.getRootCause() instanceof NumberFormatException)) {
            // Expected exception caused by Channel Binding parameter inconsistency
            return true;
        }
        throw ne;
    }

}

class DummySSLServer extends Thread {
    final SSLServerSocket sslServerSocket;
    final int serverPort;

    public DummySSLServer() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        SSLServerSocketFactory sslssf =
                (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        sslServerSocket =
                (SSLServerSocket) sslssf.createServerSocket(0, 0, loopback);
        serverPort = sslServerSocket.getLocalPort();
        start();
    }

    public void run() {
        try {
            try (SSLSocket sslSocket = (SSLSocket) sslServerSocket.accept()) {
                InputStream sslIS = sslSocket.getInputStream();
                sslIS.readAllBytes();
            }
        } catch (Exception e) {
            System.out.println("Should be an expected exception: " + e);
        } finally {
            try {
                sslServerSocket.close();
            } catch(IOException ioe) {
                // just ignore
            }
        }
    }
    public void close() {
        try {
            if (!sslServerSocket.isClosed())
                sslServerSocket.close();
        } catch(IOException ioe) {
            // just ignore
        }
    }
    public int getServerPort() {
        return serverPort;
    }
}
