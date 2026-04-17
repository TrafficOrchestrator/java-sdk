# traffic-orchestrator-java

Official Java SDK for [Traffic Orchestrator](https://trafficorchestrator.com) — enterprise-grade software license management.

## Install

### Maven

```xml
<dependency>
  <groupId>com.trafficorchestrator</groupId>
  <artifactId>sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.trafficorchestrator:sdk:1.0.0'
```

## Quick Start

```java
TrafficOrchestrator to = new TrafficOrchestrator.Builder()
    .apiKey(System.getenv("TO_API_KEY"))
    .timeout(Duration.ofSeconds(5))
    .maxRetries(3)
    .build();

ValidationResult result = to.validateLicense("LK-xxxx", "example.com");
if (result.isValid()) {
    System.out.println("Plan: " + result.getPlanId());
}
```

## API Methods

### Core License Operations

| Method | Description |
|--------|-------------|
| `validateLicense(token, domain)` | Validate a license key against a domain |
| `verifyOffline(token)` | Verify license locally using Ed25519 signatures (no API call) |
| `listLicenses()` | List all licenses for the authenticated user |
| `createLicense(planId, domains)` | Create a new license |
| `rotateLicense(licenseId)` | Rotate a license key (revoke old, generate new) |
| `addDomain(licenseId, domain)` | Add a domain to a license |
| `removeDomain(licenseId, domain)` | Remove a domain from a license |
| `deleteLicense(licenseId)` | Delete (revoke) a license |
| `getUsage()` | Get current usage statistics |

### Portal & Enterprise Methods

| Method | Description |
|--------|-------------|
| `getAnalytics(days)` | Detailed analytics for the specified period |
| `getDashboard()` | Full dashboard overview |
| `getSLA()` | SLA compliance data |
| `exportAuditLogs(format)` | Export audit logs as JSON or CSV |
| `getWebhookDeliveries(page)` | Webhook delivery history |
| `batchLicenseOperation(op, ids)` | Batch suspend/activate/extend licenses |
| `getIPAllowlist(licenseId)` | Get IP allowlist for a license |
| `setIPAllowlist(licenseId, ips)` | Set IP allowlist for a license |
| `healthCheck()` | Check API health status |

## Error Handling

```java
try {
    to.validateLicense(token, domain);
} catch (TOApiException e) {
    System.err.printf("API Error: %s (code: %s, status: %d)%n",
        e.getMessage(), e.getCode(), e.getStatus());
} catch (TONetworkException e) {
    System.err.println("Network error: " + e.getMessage());
}
```

## Multi-Environment Keys

```java
// Development
TrafficOrchestrator dev = new TrafficOrchestrator.Builder()
    .apiKey(System.getenv("TO_API_KEY_DEV"))
    .baseUrl("https://api-staging.trafficorchestrator.com")
    .build();

// Production
TrafficOrchestrator prod = new TrafficOrchestrator.Builder()
    .apiKey(System.getenv("TO_API_KEY"))
    .build();
```

## Retry (OkHttp Interceptor)

Built-in retry interceptor with exponential backoff for 5xx and network errors. Configurable via `.maxRetries(n)`.

## Offline Verification (Enterprise)

Validate licenses locally without API calls using Ed25519 JWT signatures:

```java
TrafficOrchestrator to = new TrafficOrchestrator.Builder()
    .publicKey(System.getenv("TO_PUBLIC_KEY"))
    .build();

ValidationResult result = to.verifyOffline(licenseToken);
if (result.isValid()) {
    System.out.println("Plan: " + result.getPlanId());
    System.out.println("Domains: " + result.getDomains());
}
```

## Requirements

- Java 11+
- OkHttp 4.x, Jackson 2.x

## Documentation

- [API Reference](https://trafficorchestrator.com/docs)
- [Java SDK Guide](https://trafficorchestrator.com/docs/sdk/java)
- [OpenAPI Spec](https://api.trafficorchestrator.com/api/v1/openapi.json)

## License

MIT
