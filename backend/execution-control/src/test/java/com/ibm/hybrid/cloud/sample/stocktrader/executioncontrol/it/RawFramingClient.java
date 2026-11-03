/*
       Copyright 2020-2021 IBM Corp, All Rights Reserved
       Copyright 2022-2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.it;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;


/* Not a test class - the name is deliberately outside Failsafe's *IT pattern so it is compiled
   with the integration tests and never run as one.

   The Jakarta REST client the other IT classes use decides a request's framing itself: it sets a
   Content-Length from the entity it was given and offers no way to send a chunked body or a
   content-coded one. Both are exactly what the refusals asserted through this class are about - a
   chunked message's length is unknown until its body is read, which is why its size refusal
   arrives through the application rather than at header-parse time, and a coded body is refused
   before it is read at all. java.net.HttpURLConnection can frame both, needs no dependency the
   build does not already have, and keeps that framing in one place instead of in each test. */
/** Sends requests whose HTTP framing or content coding the Jakarta REST client cannot express. */
final class RawFramingClient {

    //Small on purpose: several chunks of an oversize body cross the wire before the channel
    //refuses one, which is the condition under test rather than a single huge chunk header.
    private static final int CHUNK_SIZE = 1024;

    private static final int TIMEOUT_MILLIS = 30_000;

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String JSON_MEDIA_TYPE = "application/json";

    private RawFramingClient() {
    }

    //Transfer-Encoding cannot be set as a header on HttpURLConnection - it is one of the fields the
    //implementation reserves - so chunked framing is requested through setChunkedStreamingMode,
    //which is what makes the connection write chunk headers instead of a Content-Length.
    static Result sendChunked(String url, String method, String jsonBody, String authorization) {
        return send(url, method, jsonBody.getBytes(StandardCharsets.UTF_8), true, null, authorization);
    }

    static Result sendContentCoded(String url, String method, byte[] codedBody, String coding,
            String authorization) {
        return send(url, method, codedBody, false, coding, authorization);
    }

    /** Compresses a body the way a caller exploiting a content coding would, at maximum ratio. */
    static byte[] gzip(String body) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException impossible) {
            //An in-memory stream has nothing to fail on; reported rather than swallowed so a
            //future change that gives it something to fail on cannot hide it.
            throw new IllegalStateException("Could not gzip a request body in memory", impossible);
        }

        return compressed.toByteArray();
    }

    private static Result send(String url, String method, byte[] body, boolean chunked,
            String coding, String authorization) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty(AUTHORIZATION_HEADER, authorization);
            connection.setRequestProperty(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE);
            connection.setRequestProperty(ACCEPT_HEADER, JSON_MEDIA_TYPE);

            if (coding != null) {
                connection.setRequestProperty(CONTENT_ENCODING_HEADER, coding);
            }

            if (chunked) {
                connection.setChunkedStreamingMode(CHUNK_SIZE);
            } else {
                connection.setFixedLengthStreamingMode(body.length);
            }

            writeBody(connection, body);

            int status = connection.getResponseCode();

            return new Result(status, readBody(connection), headersOf(connection));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read the answer to a " + method + " on "
                    + url, failure);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    //A refusal can arrive while the body is still being written - the server answers and closes the
    //connection part way through an oversize upload - and the write then fails locally. The
    //response is the point of the call, so the failed write is stepped over and the status is read
    //anyway; a write that fails for any other reason surfaces on the read that follows.
    private static void writeBody(HttpURLConnection connection, byte[] body) throws IOException {
        try (OutputStream entityStream = connection.getOutputStream()) {
            entityStream.write(body);
            entityStream.flush();
        } catch (IOException refusedMidUpload) {
            return;
        }
    }

    //getErrorStream, not getInputStream, once the status is a failure: HttpURLConnection serves the
    //error entity only from the former, and these tests assert on the body of a refusal.
    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream entityStream = (connection.getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST)
                ? connection.getInputStream() : connection.getErrorStream();

        if (entityStream == null) {
            return "";
        }

        try (InputStream stream = entityStream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    //The status line arrives as a field whose name is null, which would break any case-insensitive
    //lookup over the map, so it is dropped here rather than guarded against at every call site.
    private static Map<String, List<String>> headersOf(HttpURLConnection connection) {
        Map<String, List<String>> headers = new HashMap<>();

        for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
            if (field.getKey() != null) {
                headers.put(field.getKey(), new ArrayList<>(field.getValue()));
            }
        }

        return headers;
    }

    /** One raw-framed answer, in the parts the tests assert on. */
    static final class Result {

        private final int status;
        private final String body;
        private final Map<String, List<String>> headers;

        private Result(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = (body == null) ? "" : body;
            this.headers = headers;
        }

        int status() {
            return status;
        }

        String body() {
            return body;
        }

        Map<String, List<String>> headers() {
            return headers;
        }

        //Null for an absent header rather than an empty string, so a missing header is reported as
        //missing instead of as a value that failed to match.
        String header(String name) {
            for (Map.Entry<String, List<String>> field : headers.entrySet()) {
                //HTTP field names are case-insensitive and the container chooses their casing.
                if (field.getKey().equalsIgnoreCase(name)) {
                    return field.getValue().isEmpty() ? null : field.getValue().get(0);
                }
            }

            return null;
        }
    }
}
