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

import com.azul.crs.shared.models.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;

import static com.azul.crs.client.CRSException.AUTHENTICATION_FAILURE;
import static com.azul.crs.client.CRSException.REASON_INTERNAL_ERROR;
import static com.azul.crs.client.Client.ClientProp.*;
import static com.azul.crs.shared.Utils.*;
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

@LogChannel("connection")
public class ConnectionManager {
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    private static final Class<Payload> VOID = null;

    private static final String AUTH_TOKEN_RESOURCE       = "/crs/auth/rt/token";
    private static final String EVENT_RESOURCE            = "/crs/instance/{vmId}";
    private static final String ARTIFACT_CHUNK_RESOURCE   = "/crs/artifact/chunk";

    private static final String MEDIA_TYPE_TEXT_PLAIN       = "text/plain";
    private static final String MEDIA_TYPE_JSON             = "application/json";
    private static final String MEDIA_TYPE_BINARY           = "application/octet-stream";

    private static final String HEADER_AUTHORIZATION  = "Authorization";
    private static final String HEADER_CONTENT_TYPE   = "Content-Type";
    private static final String HEADER_STREAM_LENGTH  = "Stream-Length";
    private static final String HEADER_ACCEPT         = "Accept";

    private static final String METHOD_GET    = "GET";
    private static final String METHOD_POST   = "POST";
    private static final String METHOD_PUT    = "PUT";
    private static final String METHOD_PATCH  = "PATCH";

    /** Number of retries and sleep between retries for CRS requests */
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_SLEEP = 100;

    private static final String YEK_IPA = "r1fhe2lVGN1EgDHH0Eg8d94tjv12e0F7a78RNysB";

    private final Logger logger = Logger.getLogger(ConnectionManager.class);
    private final String restAPI;
    private final String mailbox;
    private final ConnectionListener listener;

    private static final long TOKEN_REFRESH_TIMEOUT_MS = 5 * 60_000; // 5 minutes

    private final Client client;
    private final String keystore;

    private String runtimeToken;
    private long nextRuntimeTokenRefreshTimeCount;
    private String vmId;
    private boolean unrecoverableError;
    private SSLSocketFactory sslSocketFactoryOne; // uses only CRS SSL certificate
    private SSLSocketFactory sslSocketFactoryTwo; // uses both default (CA) certs and CRS certificate

    interface ConnectionListener {
        void authenticated();

        void syncFailed(Result<String[]> reason);
    }

    ConnectionManager(Map<Client.ClientProp, Object> props, Client client, ConnectionListener listener) {
        this.client = client;
        this.listener = listener;

        restAPI = (String)props.get(API_URL);
        mailbox = (String)props.get(API_MAILBOX);
        keystore = (String)props.get(KS);
        logger.info("Using CRS endpoint configuration\n"+
            "   API url = %s\n"+
            "   mailbox = %s", restAPI, mailbox);

        if (keystore != null)
            logger.info("   auth override keystore = %s", keystore);
    }

    void start() throws IOException {
        createCustomTrustManagers();
        saveRuntimeToken(getRuntimeToken(client.getClientVersion(), this.mailbox));
    }

    private void saveRuntimeToken(String[] token) {
        if (token.length == 2) {
            runtimeToken = token[0];
            vmId = token[1];
            listener.authenticated();
        } else {
            listener.syncFailed(new Result<>(new IOException("Protocol failure, wrong auth response")));
        }
    }

    private X509TrustManager getX509TrustManager(KeyStore ks) throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory tmFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmFactory.init(ks);
        for (TrustManager tm : tmFactory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new NoSuchAlgorithmException();
    }

    private void createCustomTrustManagers() throws CRSException {
        try {
            char[] password = "crscrs".toCharArray();

            KeyStore ks = KeyStore.getInstance("JKS");
            try (InputStream keystoreStream = (keystore == null)
                    ? getClass().getResourceAsStream("crs.jks")
                    : new FileInputStream(keystore)) {
                ks.load(keystoreStream, password);
            }

            KeyManagerFactory kmFactory = KeyManagerFactory.getInstance("NewSunX509");
            kmFactory.init(ks, password);

            final X509TrustManager tmOne = getX509TrustManager(ks);
            final X509TrustManager tmDefault = getX509TrustManager(null);
            int icount1 = tmOne.getAcceptedIssuers().length;
            int icount2 = tmDefault.getAcceptedIssuers().length;

            final X509Certificate[] allIssuers = new X509Certificate[icount1 + icount2];
            System.arraycopy(tmOne.getAcceptedIssuers(), 0, allIssuers, 0, icount1);
            System.arraycopy(tmDefault.getAcceptedIssuers(), 0, allIssuers, icount1, icount2);

            X509TrustManager tmTwo = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("unsupported operation");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        tmOne.checkServerTrusted(chain, authType);
                    } catch (CertificateException ignored) {
                        tmDefault.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return allIssuers;
                }
            };

            KeyManager[] keyManagers = kmFactory.getKeyManagers();
            sslSocketFactoryOne = createSocketFactory(tmOne, keyManagers);
            sslSocketFactoryTwo = createSocketFactory(tmTwo, keyManagers);
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | KeyManagementException ex) {
            unrecoverableError = true;
            throw new CRSException(REASON_INTERNAL_ERROR, "Unrecoverable internal error: ", ex);
        }
    }

    private SSLSocketFactory createSocketFactory(X509TrustManager tm, KeyManager[] keyManagers) throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers, new TrustManager[]{tm}, null);
        return sslContext.getSocketFactory();
    }

    /** Creates and configures HTTPS connection to given URL */
    private HttpsURLConnection createConnection(String url) throws IOException {
        if (unrecoverableError)
            throw new IOException("Unrecoverable error");

        URL endpoint = new URL(url);
        HttpsURLConnection con = (HttpsURLConnection) endpoint.openConnection();
        // See ZVM-18911
        // Make sure not to use caches for this connection (not to interfere with
        // non-default caches set by (java) user
        con.setUseCaches(false);
        con.setConnectTimeout(30_000);
        con.setReadTimeout(20_000);
        con.setDoOutput(true);
        con.setDoInput(true);

        con.setRequestProperty(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON);
        con.setRequestProperty(HEADER_ACCEPT, MEDIA_TYPE_TEXT_PLAIN);

        return con;
    }

    /** Operates with open or close HTTPS connection */
    private interface ConnectionConsumer { void consume(HttpsURLConnection con) throws IOException; }
    private static final ConnectionConsumer NONE = new ConnectionConsumer() {
        @Override
        public void consume(HttpsURLConnection con) throws IOException {}
    };

    /**
     * Requests any CRS REST API endpoint with proper authorization and returns response
     * On expired security token requests a fresh token and sends payload again
     *
     * @param resource - endpoint of CRS REST API to be used for request
     * @param method - HTTP request method (POST, GET, PUT, etc.)
     * @param headerWriter - makes changes to request properties before HTTPS connection is open
     * @param requestWriter - writes request data to open HTTPS connection
     * @return request response with expected payload type
     * @throws IOException
     */
    private Response<String[]> requestAny(
        String resource, String method,
        ConnectionConsumer headerWriter, ConnectionConsumer requestWriter) throws IOException {

        final long startTime = currentTimeCount();
        Response<String[]> response = new Response<>();
        HttpsURLConnection con = createConnection(resource);
        con.setSSLSocketFactory(sslSocketFactoryOne);
        if (method.equals(METHOD_PATCH)) {
            con.setRequestProperty("X-HTTP-Method-Override", method);
            method = METHOD_POST;
        }

        con.setRequestProperty(HEADER_AUTHORIZATION, "Bearer " + runtimeToken);
        con.setRequestMethod(method);

        headerWriter.consume(con);
        con.connect();
        PerformanceMetrics.logHandshakeTime(elapsedTimeMillis(startTime));

        try {
            requestWriter.consume(con);

            int code = con.getResponseCode();
            String message = con.getResponseMessage();
            response.code(code);
            response.message(message);
            logger.debug("requestAny response code %d", code);

            if (code < 200 || code >= 300) {
                if (code == 401) {
                    // Try to refresh token on unauthorized error
                    if (currentTimeCount() > nextRuntimeTokenRefreshTimeCount) {
                        saveRuntimeToken(refreshRuntimeToken(runtimeToken));
                        return requestAny(resource, method, headerWriter, requestWriter);
                    }
                }
                if (con.getErrorStream() != null) {
                    response.error(readStream(con.getErrorStream()));
                }
            } else {
                response.payload(readStreamToArray(con.getInputStream()));
            }
        } finally {
            PerformanceMetrics.logNetworkTime(elapsedTimeMillis(startTime));
            con.disconnect();
        }

        return response;
    }

    @FunctionalInterface
    public interface ResponseSupplier {
        Response<String[]> get() throws IOException;
    }

    /** Posts batch of VM events associated with VM instance known to CRS */
    public Response<String[]> sendVMEventBatch(Collection<VMEvent> events) throws IOException {
        return requestAnyJson(
            restAPI + EVENT_RESOURCE.replace("{vmId}", vmId),
            METHOD_POST, Payload.toJsonArray(events));
    }

    /** Tries to get request response with a number of retries on failure */
    public void requestWithRetries(
        ResponseSupplier request, String requestName,
        int maxRetries, long retrySleep) {

        Result<String[]> result = requestWithRetriesImpl(request, requestName, maxRetries, retrySleep);
        if (!result.successful())
            listener.syncFailed(result);
    }

    private Result<String[]> requestWithRetriesImpl(
        ResponseSupplier request, String requestName,
        int maxRetries, long retrySleep) {

        int attempt = 1;
        Result<String[]> result;
        long startTime = currentTimeCount();
        while (true) {
            try {
                result = new Result(request.get());
                if (result.successful()) {
                    logger.debug("Request %s succeeded on attempt %d, elapsed%s",
                        requestName, attempt, elapsedTimeString(startTime));
                    return result;
                }
            } catch (IOException ioe) {
                logger.debug("Request %s failed on attempt %s of %s, elapsed%s: %s",
                    requestName, attempt, maxRetries, elapsedTimeString(startTime), ioe.toString());
                result = new Result<>(ioe);
            }
            // No retries on fatal error, error reason to be resolved
            if (!result.canRetry() || ++attempt > maxRetries) break;
            sleep(retrySleep);
        }
        logger.debug("Request %s aborted after %d attempt, elapsed%s",
            requestName, attempt, elapsedTimeString(startTime));
        return result;
    }

    Response<String[]> requestAnyJson(String resource, String method, String payload) throws IOException {
        return requestAny(resource, method, NONE,
            new ConnectionConsumer() {
                @Override
                public void consume(HttpsURLConnection con) throws IOException {
                    logger.debug("%s %s\n", method, resource);
                    logger.trace("%s\n\n", payload);
                    writeData(con, payload.getBytes(UTF_8));
                }
            });
    }

    /** Reads input stream into a buffer and returns it as string */
    private String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int length;
        long totalLength = 0;
        byte[] readBuffer = new byte[1024];
        try {
            while ((length = inputStream.read(readBuffer)) != -1) {
                outputStream.write(readBuffer, 0, length);
                totalLength += length;
            }
        } finally {
            PerformanceMetrics.logBytes(totalLength, 0);
        }
        return outputStream.toString(UTF_8);
    }

    private String[] readStreamToArray(InputStream is) throws IOException {
        List<String> result = new LinkedList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        do {
            String s = reader.readLine();
            if (s == null)
                break;
            result.add(s);
        } while (true);
        return result.toArray(new String[0]);
    }

    /** Writes data to given open connection stream */
    private void writeData(URLConnection con, byte[] data, int size) throws IOException {
        try (OutputStream out = con.getOutputStream()) {
            out.write(data, 0, size);
            PerformanceMetrics.logBytes(0, size);
        }
    }

    /** Writes entire data to given open connection stream */
    private void writeData(URLConnection con, byte[] data) throws IOException {
        writeData(con, data, data.length);
    }

    /** Writes data stream fully to given open connection stream and returns written data size */
    private long writeData(URLConnection con, InputStream in) throws IOException {
        long written = 0;
        try (OutputStream out = con.getOutputStream()) {
            int length;
            byte[] buf = new byte[1024];
            while ((length = in.read(buf)) > 0) {
                out.write(buf, 0, length);
                written += length;
            }
        } finally {
            PerformanceMetrics.logBytes(0, written);
        }
        return written;
    }

    private Response putBinaryData(String location, ConnectionConsumer requestWriter) throws IOException {
        final long startTime = currentTimeCount();
        Response response = new Response();
        HttpsURLConnection con = createConnection(location);
        con.setSSLSocketFactory(sslSocketFactoryTwo);
        con.setRequestProperty(HEADER_CONTENT_TYPE, MEDIA_TYPE_BINARY);
        con.setRequestMethod(METHOD_PUT);

        con.connect();
        PerformanceMetrics.logHandshakeTime(elapsedTimeMillis(startTime));
        try {
            requestWriter.consume(con);
            response.code(con.getResponseCode());
            response.message(con.getResponseMessage());
            if (!response.successful()) {
                response.error(readStream(con.getErrorStream()));
            }
        } finally {
            PerformanceMetrics.logNetworkTime(elapsedTimeMillis(startTime));
            con.disconnect();
        }
        return response;
    }

    private Response putBinaryData(String location, InputStream is) throws IOException {
        return putBinaryData(location, new ConnectionConsumer() {
            @Override
            public void consume(HttpsURLConnection con) throws IOException {
                writeData(con, is);
            }
        });
    }

    public Response<String[]> sendVMArtifactChunk(VMArtifactChunk chunk, InputStream is) throws IOException {
        Response<String[]> createResponse = requestAnyJson(
            restAPI + ARTIFACT_CHUNK_RESOURCE,
            METHOD_POST, chunk.toJson());

        // Upload artifact data to presigned location URL
        if (createResponse.successful() && is != null) {
            String[] created = createResponse.getPayload();
            String location = created[0];
            Response uploadResponse = putBinaryData(location, is);
            if (!uploadResponse.successful())
                throw new IOException(
                    "Created VM artifact chunk failed to upload data: chunkId=" + created[1] +
                        ", error=" + uploadResponse.getError());
        }

        return createResponse;
    }

    /** Retrieves runtime token from CRS response */
    private Response<String[]> retrieveRuntimeToken(HttpsURLConnection con) throws IOException {
        Response<String[]> response = new Response<String[]>()
            .code(con.getResponseCode())
            .message(con.getResponseMessage());

        if (!response.successful()) {
            if (con.getErrorStream() != null)
                response.error(readStream(con.getErrorStream()));
            return response;
        }

        return response.payload(readStreamToArray(con.getInputStream()));
    }

    /** Requests CRS for new runtime auth token to send client data to CRS mailbox */
    private String[] getRuntimeToken(String clientVersion, String mailbox) throws CRSException {
        logger.info("Get runtime token: clientVersion=%s, mailbox=%s", clientVersion, mailbox);
        final long startTime = currentTimeCount();
        nextRuntimeTokenRefreshTimeCount = nextTimeCount(TOKEN_REFRESH_TIMEOUT_MS);
        Result<String[]> result = requestWithRetriesImpl(
            new ResponseSupplier() {
                @Override
                public Response<String[]> get() throws IOException {
                    final long attemptStartTime = currentTimeCount();
                    HttpsURLConnection con = createConnection(restAPI + AUTH_TOKEN_RESOURCE
                            + "?clientVersion=" + clientVersion + "&mailbox=" + mailbox);
                    con.setSSLSocketFactory(sslSocketFactoryOne);
                    con.setRequestProperty("x-api-key", YEK_IPA);
                    con.setRequestMethod(METHOD_GET);
                    con.connect();
                    PerformanceMetrics.logHandshakeTime(elapsedTimeMillis(attemptStartTime));
                    try {
                        return retrieveRuntimeToken(con);
                    } finally {
                        PerformanceMetrics.logNetworkTime(elapsedTimeMillis(startTime));
                        con.disconnect();
                    }
                }
            },
            "getRuntimeToken",
            MAX_RETRIES, RETRY_SLEEP);
        if (!result.successful())
            throw new CRSException(client, AUTHENTICATION_FAILURE,
                "Cannot get runtime token: ", result);
        return result.getResponse().getPayload();
    }

    /** Requests CRS to renew given runtime token, the token must be valid with expiration leeway predefined in CRS */
    private String[] refreshRuntimeToken(String runtimeToken) throws IOException {
        final long startTime = currentTimeCount();
        nextRuntimeTokenRefreshTimeCount = nextTimeCount(TOKEN_REFRESH_TIMEOUT_MS);
        logger.info("Refresh runtime token");
        Result<String[]> result = requestWithRetriesImpl(
            new ResponseSupplier() {
                @Override
                public Response<String[]> get() throws IOException {
                    final long attemptStartTime = currentTimeCount();
                    HttpsURLConnection con = createConnection(restAPI + AUTH_TOKEN_RESOURCE);
                    con.setSSLSocketFactory(sslSocketFactoryOne);
                    con.setRequestProperty("x-api-key", YEK_IPA);
                    con.setRequestProperty(HEADER_CONTENT_TYPE, MEDIA_TYPE_TEXT_PLAIN);
                    con.setRequestMethod(METHOD_POST);
                    con.connect();
                    PerformanceMetrics.logHandshakeTime(elapsedTimeMillis(attemptStartTime));
                    try {
                        con.getOutputStream().write(runtimeToken.getBytes());
                        return retrieveRuntimeToken(con);
                    } finally {
                        PerformanceMetrics.logNetworkTime(elapsedTimeMillis(startTime));
                        con.disconnect();
                    }
                }
            },
            "refreshRuntimeToken",
            MAX_RETRIES, RETRY_SLEEP);
        if (!result.successful())
            throw new CRSException(client, AUTHENTICATION_FAILURE,
                "Cannot refresh runtime token: ", result);
        return result.getResponse().getPayload();
    }

    public String getVmId() { return vmId; }

    public String getMailbox() {
        return mailbox;
    }

    public String getRestAPI() {
        return restAPI;
    }
}
