package org.learning.mldsa.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Thin HTTP client for the standalone Rust zero-knowledge STARK proof service
 * (see the top-level zk-compliance-service/ directory, run separately with
 * `cargo run --release`, default port 7878).
 *
 * IMPORTANT: this class is intentionally not called from anywhere in the existing
 * payslip / file-transfer flow. Nothing in SlipController, FileTransferService, or
 * FileTransfer wires into it. It is only reachable through ComplianceController's own
 * endpoints, so the live payslip-send flow is completely unaffected by its presence.
 * That is a deliberate prototype-stage choice, not an oversight -- see the class comment
 * on ComplianceController.
 *
 * Uses classic java.net.HttpURLConnection (JDK built-in) rather than the newer
 * java.net.http.HttpClient: HttpClient was tried first and, in the sandbox this was built
 * and tested in, its async NIO implementation hung on every request to a plain local HTTP
 * server (confirmed the request never even reached the server, while curl to the same URL
 * succeeded instantly) -- almost certainly an interaction between that JDK client's
 * non-blocking socket code and the container's syscall sandboxing, not anything specific to
 * this service. HttpURLConnection's plain blocking sockets were tested against the same
 * server and worked correctly on every call, so that's what ships here. If this ever
 * surfaces the same symptom on a different machine (calls hang for no visible reason even
 * though the proof service is up and curl can reach it), that history is why.
 *
 * JSON is hand-rolled for these small, fixed-shape request/response bodies rather than
 * pulling in Jackson explicitly here -- this mirrors the Rust service's own
 * minimal-dependency philosophy (hand-rolled base64, no web framework) and keeps this
 * client fully self-contained. Field names below must match the Rust service's serde
 * rename attributes exactly (earningsCents, deductionsCents, netPayCents, numEntries,
 * proofBase64, proofSizeBytes, valid, error) -- see zk-compliance-service/src/main.rs for
 * the authoritative contract.
 */
@Service
public class ZkComplianceService {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int CALL_READ_TIMEOUT_MS = 10000;
    private static final int HEALTH_READ_TIMEOUT_MS = 3000;

    private final String baseUrl;

    public ZkComplianceService(@Value("${app.zk-proof-service-url:http://localhost:7878}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static class ProveResult {
        public final long netPayCents;
        public final int numEntries;
        public final String proofBase64;
        public final int proofSizeBytes;

        public ProveResult(long netPayCents, int numEntries, String proofBase64, int proofSizeBytes) {
            this.netPayCents = netPayCents;
            this.numEntries = numEntries;
            this.proofBase64 = proofBase64;
            this.proofSizeBytes = proofSizeBytes;
        }
    }

    public static class VerifyResult {
        public final boolean valid;
        public final String error;

        public VerifyResult(boolean valid, String error) {
            this.valid = valid;
            this.error = error;
        }
    }

    /** Thrown when the Rust service can't be reached or returns something unexpected. */
    public static class ZkServiceException extends RuntimeException {
        public ZkServiceException(String message) {
            super(message);
        }

        public ZkServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Requests a proof that {@code sum(earningsCents) - sum(deductionsCents)} equals some
     * net pay, without exposing the individual line items in the response -- only the net
     * pay total, the entry count, and the proof bytes come back.
     */
    public ProveResult prove(List<Long> earningsCents, List<Long> deductionsCents) {
        String requestBody = "{\"earningsCents\":" + longArrayJson(earningsCents)
                + ",\"deductionsCents\":" + longArrayJson(deductionsCents) + "}";
        String responseBody = post("/prove", requestBody, CALL_READ_TIMEOUT_MS);
        return parseProveResponse(responseBody);
    }

    /** Verifies a previously-issued proof against a claimed net pay and entry count. */
    public VerifyResult verify(String proofBase64, long netPayCents, int numEntries) {
        String requestBody = "{\"proofBase64\":\"" + jsonEscape(proofBase64) + "\""
                + ",\"netPayCents\":" + netPayCents
                + ",\"numEntries\":" + numEntries + "}";
        String responseBody = post("/verify", requestBody, CALL_READ_TIMEOUT_MS);
        return parseVerifyResponse(responseBody);
    }

    /** Best-effort reachability check against GET /health. Never throws. */
    public boolean isHealthy() {
        try {
            get("/health", HEALTH_READ_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String post(String path, String jsonBody, int readTimeoutMs) {
        try {
            return exchange(path, "POST", jsonBody, readTimeoutMs);
        } catch (IOException e) {
            throw new ZkServiceException("Could not reach ZK proof service at " + baseUrl + path
                    + " -- is `cargo run --release` running in zk-compliance-service/?", e);
        }
    }

    private String get(String path, int readTimeoutMs) {
        try {
            return exchange(path, "GET", null, readTimeoutMs);
        } catch (IOException e) {
            throw new ZkServiceException("Could not reach ZK proof service at " + baseUrl + path, e);
        }
    }

    private String exchange(String path, String method, String jsonBody, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection(Proxy.NO_PROXY);
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod(method);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = stream == null ? "" : readAll(stream);
            if (status != 200) {
                throw new ZkServiceException("ZK proof service returned HTTP " + status + " for " + path
                        + ": " + body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ---- minimal hand-rolled JSON helpers (fixed, known-shape payloads only) ----

    private static String longArrayJson(List<Long> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.append(']').toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ProveResult parseProveResponse(String json) {
        long netPayCents = extractLong(json, "netPayCents");
        int numEntries = (int) extractLong(json, "numEntries");
        String proofBase64 = extractString(json, "proofBase64");
        int proofSizeBytes = (int) extractLong(json, "proofSizeBytes");
        return new ProveResult(netPayCents, numEntries, proofBase64, proofSizeBytes);
    }

    private static VerifyResult parseVerifyResponse(String json) {
        boolean valid = extractBoolean(json, "valid");
        String error = extractNullableString(json, "error");
        return new VerifyResult(valid, error);
    }

    private static long extractLong(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            throw new ZkServiceException("Missing field '" + key + "' in ZK service response: " + json);
        }
        int start = idx + marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static boolean extractBoolean(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            throw new ZkServiceException("Missing field '" + key + "' in ZK service response: " + json);
        }
        int start = idx + marker.length();
        return json.startsWith("true", start);
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            throw new ZkServiceException("Missing field '" + key + "' in ZK service response: " + json);
        }
        int i = idx + marker.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length() && json.charAt(i) != '"') {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                c = json.charAt(i);
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String extractNullableString(String json, String key) {
        // The Rust side annotates VerifyResponse.error with
        // #[serde(skip_serializing_if = "Option::is_none")], so a None value omits the field
        // entirely rather than serializing it as "error":null -- both cases mean "no error"
        // here, so both must resolve to null rather than only the explicit-null case.
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        if (json.startsWith("null", start)) {
            return null;
        }
        return extractString(json, key);
    }
}
