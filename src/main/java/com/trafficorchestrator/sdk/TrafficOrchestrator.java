package com.trafficorchestrator.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Official Java client for Traffic Orchestrator API.
 *
 * <pre>{@code
 * var client = TrafficOrchestrator.builder()
 *         .apiKey("sk_live_xxxxx")
 *         .build();
 *
 * var result = client.validateLicense("LK-xxxx-xxxx", "example.com");
 * if (result.isValid()) {
 *     System.out.println("License valid until: " + result.getExpiresAt());
 * }
 * }</pre>
 */
public class TrafficOrchestrator {

    public static final String VERSION = "2.0.0";
    private static final String DEFAULT_API_URL = "https://api.trafficorchestrator.com/api/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiUrl;
    private final String apiKey;
    private final int timeoutMs;
    private final int retries;
    private final HttpClient httpClient;

    private TrafficOrchestrator(Builder builder) {
        this.apiUrl = builder.apiUrl.replaceAll("/$", "");
        this.apiKey = builder.apiKey;
        this.timeoutMs = builder.timeoutMs;
        this.retries = builder.retries;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Core: License Validation ────────────────────────────────────────────

    /**
     * Validate a license key against the API server.
     */
    public ValidationResult validateLicense(String token, String domain) throws TrafficOrchestratorException {
        var body = Map.of("token", token, "domain", domain);
        var data = request("POST", "/validate", body);
        return MAPPER.convertValue(data, ValidationResult.class);
    }

    /**
     * Verify license offline using Ed25519 public key verification.
     */
    public static ValidationResult verifyOffline(String token, String publicKeyPem, String domain) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return ValidationResult.invalid("Invalid token format");
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = MAPPER.readValue(payloadBytes, Map.class);

            // Verify expiration
            if (payload.containsKey("exp")) {
                long exp = ((Number) payload.get("exp")).longValue();
                if (Instant.now().getEpochSecond() > exp) {
                    return ValidationResult.invalid("Token expired");
                }
            }

            // Verify domain
            if (domain != null && payload.containsKey("dom")) {
                @SuppressWarnings("unchecked")
                var domains = (java.util.List<String>) payload.get("dom");
                boolean match = domains.stream().anyMatch(domain::contains);
                if (!match) {
                    return ValidationResult.invalid("Domain mismatch");
                }
            }

            // Verify Ed25519 signature
            String pemContent = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(pemContent);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pubKey = kf.generatePublic(keySpec);

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pubKey);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] sigBytes = Base64.getUrlDecoder().decode(parts[2]);

            if (!sig.verify(sigBytes)) {
                return ValidationResult.invalid("Invalid signature");
            }

            return ValidationResult.valid(payload);
        } catch (Exception e) {
            return ValidationResult.invalid(e.getMessage());
        }
    }

    // ── License Management ──────────────────────────────────────────────────

    /** List all licenses for the authenticated user. */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listLicenses() throws TrafficOrchestratorException {
        Map<String, Object> data = request("GET", "/portal/licenses", null);
        return (java.util.List<Map<String, Object>>) data.getOrDefault("licenses", java.util.List.of());
    }

    /** Create a new license. */
    public Map<String, Object> createLicense(String appName, String domain, String planId)
            throws TrafficOrchestratorException {
        var body = new java.util.HashMap<String, String>();
        body.put("appName", appName);
        if (domain != null)
            body.put("domain", domain);
        if (planId != null)
            body.put("planId", planId);
        return request("POST", "/portal/licenses", body);
    }

    /** Rotate a license key (revoke old, generate new). */
    public Map<String, Object> rotateLicense(String licenseId) throws TrafficOrchestratorException {
        return request("POST", "/portal/licenses/" + licenseId + "/rotate", null);
    }

    /** Add a domain to a license. */
    public Map<String, Object> addDomain(String licenseId, String domain) throws TrafficOrchestratorException {
        return request("POST", "/portal/licenses/" + licenseId + "/domains", Map.of("domain", domain));
    }

    /** Remove a domain from a license. */
    public Map<String, Object> removeDomain(String licenseId, String domain) throws TrafficOrchestratorException {
        return request("DELETE", "/portal/licenses/" + licenseId + "/domains", Map.of("domain", domain));
    }

    /** Delete (revoke) a license. */
    public Map<String, Object> deleteLicense(String licenseId) throws TrafficOrchestratorException {
        return request("DELETE", "/portal/licenses/" + licenseId, null);
    }

    // ── Usage & Analytics ───────────────────────────────────────────────────

    /** Get current usage statistics. */
    public Map<String, Object> getUsage() throws TrafficOrchestratorException {
        return request("GET", "/portal/stats", null);
    }

    // ── Health ──────────────────────────────────────────────────────────────

    /** Check API health status. */
    public Map<String, Object> healthCheck() throws TrafficOrchestratorException {
        return request("GET", "/health", null);
    }

    // ── Analytics & SLA ────────────────────────────────────────────────────

    /** Get detailed analytics for the specified number of days. */
    public Map<String, Object> getAnalytics(int days) throws TrafficOrchestratorException {
        return request("GET", "/portal/analytics?days=" + days, null);
    }

    /** Get a full dashboard overview. */
    public Map<String, Object> getDashboard() throws TrafficOrchestratorException {
        return request("GET", "/portal/dashboard", null);
    }

    /** Get SLA compliance data. */
    public Map<String, Object> getSLA(int days) throws TrafficOrchestratorException {
        return request("GET", "/portal/sla?days=" + days, null);
    }

    // ── Audit & Webhooks ───────────────────────────────────────────────────

    /** Export audit logs in the specified format (json/csv). */
    public Map<String, Object> exportAuditLogs(String format, String since) throws TrafficOrchestratorException {
        String path = "/portal/audit-logs/export?format=" + format;
        if (since != null && !since.isEmpty())
            path += "&since=" + since;
        return request("GET", path, null);
    }

    /** Get webhook delivery history. */
    public Map<String, Object> getWebhookDeliveries(int limit, String status) throws TrafficOrchestratorException {
        String path = "/portal/webhooks/deliveries?limit=" + limit;
        if (status != null && !status.isEmpty())
            path += "&status=" + status;
        return request("GET", path, null);
    }

    // ── Batch Operations ──────────────────────────────────────────────────

    /** Perform a batch operation (suspend/activate/extend) on multiple licenses. */
    public Map<String, Object> batchLicenseOperation(String action, java.util.List<String> licenseIds, Integer days)
            throws TrafficOrchestratorException {
        var body = new java.util.HashMap<String, Object>();
        body.put("action", action);
        body.put("licenseIds", licenseIds);
        if (days != null && days > 0)
            body.put("days", days);
        return request("POST", "/portal/licenses/batch", body);
    }

    // ── IP Allowlist ──────────────────────────────────────────────────────

    /** Get the IP allowlist for a license. */
    public Map<String, Object> getIPAllowlist(String licenseId) throws TrafficOrchestratorException {
        return request("GET", "/portal/licenses/" + licenseId + "/ip-allowlist", null);
    }

    /** Set the IP allowlist for a license. */
    public Map<String, Object> setIPAllowlist(String licenseId, java.util.List<String> allowedIps)
            throws TrafficOrchestratorException {
        return request("PUT", "/portal/licenses/" + licenseId + "/ip-allowlist",
                Map.of("allowedIps", allowedIps));
    }

    // ── Internal ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, String path, Object body)
            throws TrafficOrchestratorException {
        Exception lastError = null;

        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                var reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + path))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json");

                if (apiKey != null && !apiKey.isEmpty()) {
                    reqBuilder.header("Authorization", "Bearer " + apiKey);
                }

                if ("POST".equals(method)) {
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(
                            body != null ? MAPPER.writeValueAsString(body) : "{}"));
                } else if ("PUT".equals(method)) {
                    reqBuilder.PUT(HttpRequest.BodyPublishers.ofString(
                            body != null ? MAPPER.writeValueAsString(body) : "{}"));
                } else if ("DELETE".equals(method)) {
                    if (body != null) {
                        reqBuilder.method("DELETE",
                                HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
                    } else {
                        reqBuilder.DELETE();
                    }
                } else {
                    reqBuilder.GET();
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                Map<String, Object> data = MAPPER.readValue(resp.body(), Map.class);

                if (resp.statusCode() >= 400 && resp.statusCode() < 500) {
                    throw new TrafficOrchestratorException(
                            (String) data.getOrDefault("error", "HTTP " + resp.statusCode()),
                            (String) data.getOrDefault("code", "UNKNOWN"),
                            resp.statusCode());
                }

                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return data;
                }

                throw new TrafficOrchestratorException("HTTP " + resp.statusCode(), "SERVER_ERROR",
                        resp.statusCode());

            } catch (TrafficOrchestratorException e) {
                if (e.getStatus() < 500)
                    throw e;
                lastError = e;
            } catch (Exception e) {
                lastError = e;
            }

            if (attempt < retries) {
                try {
                    Thread.sleep(Math.min(1000L * (1L << attempt), 5000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        throw new TrafficOrchestratorException(
                lastError != null ? lastError.getMessage() : "Request failed",
                "NETWORK_ERROR", 0);
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    public static class Builder {
        private String apiUrl = DEFAULT_API_URL;
        private String apiKey;
        private int timeoutMs = 10_000;
        private int retries = 2;

        public Builder apiUrl(String url) {
            this.apiUrl = url;
            return this;
        }

        public Builder apiKey(String key) {
            this.apiKey = key;
            return this;
        }

        public Builder timeout(int ms) {
            this.timeoutMs = ms;
            return this;
        }

        public Builder retries(int n) {
            this.retries = n;
            return this;
        }

        public TrafficOrchestrator build() {
            return new TrafficOrchestrator(this);
        }
    }

    // ── Nested Types ────────────────────────────────────────────────────────

    public static class ValidationResult {
        private boolean valid;
        private String message;
        private String plan;
        private String expiresAt;
        private Map<String, Object> payload;

        public ValidationResult() {
        }

        public static ValidationResult valid(Map<String, Object> payload) {
            var r = new ValidationResult();
            r.valid = true;
            r.payload = payload;
            r.plan = payload != null ? (String) payload.get("plan") : null;
            return r;
        }

        public static ValidationResult invalid(String message) {
            var r = new ValidationResult();
            r.valid = false;
            r.message = message;
            return r;
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public String getPlan() {
            return plan;
        }

        public String getExpiresAt() {
            return expiresAt;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public void setPlan(String plan) {
            this.plan = plan;
        }

        public void setExpiresAt(String expiresAt) {
            this.expiresAt = expiresAt;
        }

        public void setPayload(Map<String, Object> payload) {
            this.payload = payload;
        }
    }

    public static class TrafficOrchestratorException extends Exception {
        private final String code;
        private final int status;

        public TrafficOrchestratorException(String message, String code, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String getCode() {
            return code;
        }

        public int getStatus() {
            return status;
        }
    }
}
