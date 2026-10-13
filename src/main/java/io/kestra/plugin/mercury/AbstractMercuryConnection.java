package io.kestra.plugin.mercury;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BearerAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMercuryConnection extends Task {

    public static final String DEFAULT_BASE_URL = "https://api.mercury.com/api/v1";
    public static final int DEFAULT_MAX_PAGE_CAP = 100;
    static final int MAX_RETRY_ATTEMPTS = 5;

    @Schema(
        title = "Mercury API base URL",
        description = "Base URL for the Mercury API."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "Mercury API token",
        description = "API token for authentication with the Mercury API."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @NotNull
    private Property<String> apiToken;

    @Builder.Default
    @ToString.Exclude
    long initialBackoffMs = 1_000L;

    @Builder.Default
    @ToString.Exclude
    long maxBackoffMs = 30_000L;

    public record PageResult<T>(List<T> items, String nextCursor) {}

    @FunctionalInterface
    public interface PageFetcher<T> {
        PageResult<T> fetch(String cursor) throws Exception;
    }

    protected HttpClient createClient(RunContext runContext) throws Exception {
        var rToken = runContext.render(this.apiToken).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("apiToken is required"));

        var httpConfiguration = HttpConfiguration.builder()
            .auth(BearerAuthConfiguration.builder()
                .token(Property.ofValue(rToken))
                .build())
            .build();

        return new HttpClient(runContext, httpConfiguration);
    }

    protected <T> T request(HttpClient client, RunContext runContext, String path,
                            Map<String, Object> queryParams, TypeReference<T> typeRef) throws Exception {
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
        var uri = buildUri(rBaseUrl, path, queryParams);
        var request = HttpRequest.builder()
            .method("GET")
            .uri(uri)
            .build();

        return executeWithRetry(client, runContext, request, typeRef);
    }

    protected <T> int paginate(RunContext runContext, int pageCap,
                               PageFetcher<T> fetcher, Predicate<T> itemConsumer) throws Exception {
        var logger = runContext.logger();
        String currentCursor = null;
        var pageCount = 0;
        var totalItems = 0;
        Set<String> seenCursors = new HashSet<>();

        while (pageCount < pageCap) {
            pageCount++;
            var page = fetcher.fetch(currentCursor);
            if (page == null || page.items() == null || page.items().isEmpty()) {
                logger.debug("Pagination stopped: empty page encountered on page {}", pageCount);
                break;
            }

            var stopRequested = false;
            for (var item : page.items()) {
                totalItems++;
                if (!itemConsumer.test(item)) {
                    stopRequested = true;
                    break;
                }
            }

            if (stopRequested) {
                break;
            }

            var nextCursor = page.nextCursor();
            if (nextCursor == null || nextCursor.isBlank()) {
                logger.debug("Pagination stopped: no next cursor on page {}", pageCount);
                break;
            }

            if (!seenCursors.add(nextCursor)) {
                logger.warn("Pagination stopped: repeated cursor '{}' detected on page {}", nextCursor, pageCount);
                break;
            }

            currentCursor = nextCursor;
        }

        if (pageCount >= pageCap) {
            logger.warn("Pagination reached maximum page cap of {}", pageCap);
        }

        return totalItems;
    }

    private <T> T executeWithRetry(HttpClient client, RunContext runContext,
                                   HttpRequest request, TypeReference<T> typeRef) throws Exception {
        var logger = runContext.logger();
        var backoffMs = this.initialBackoffMs;

        for (var attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.request(request, String.class);
                int statusCode = response.getStatus() != null ? response.getStatus().getCode() : 200;
                if (statusCode >= 400) {
                    throw mapError(statusCode, new HttpClientResponseException("HTTP " + statusCode, response));
                }
                return parseBody(response.getBody(), typeRef);
            } catch (HttpClientResponseException e) {
                var status = extractStatusCode(e);
                if (status == 429 && attempt < MAX_RETRY_ATTEMPTS) {
                    var waitMs = extractRetryAfterMs(e, backoffMs);
                    logger.warn("Rate limited (429), retry {}/{} after {}ms", attempt, MAX_RETRY_ATTEMPTS, waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during rate limit backoff", ie);
                    }
                    backoffMs = Math.min(backoffMs * 2, this.maxBackoffMs);
                    continue;
                }
                throw mapError(status, e);
            } catch (HttpClientException e) {
                throw mapError(0, e);
            }
        }
        throw new IllegalStateException("Exhausted all " + MAX_RETRY_ATTEMPTS + " retry attempts after repeated 429 responses");
    }

    private <T> T parseBody(String body, TypeReference<T> typeRef) throws IOException {
        if (body == null || body.isBlank()) {
            return null;
        }
        return JacksonMapper.ofJson().readValue(body, typeRef);
    }

    private long extractRetryAfterMs(HttpClientResponseException e, long defaultBackoffMs) {
        try {
            var response = e.getResponse();
            if (response != null && response.getHeaders() != null) {
                var retryAfter = response.getHeaders().get("Retry-After");
                if (retryAfter != null && !retryAfter.isBlank()) {
                    return Long.parseLong(retryAfter.trim()) * 1_000L;
                }
            }
        } catch (Exception ignored) {
            // header missing or unparseable — fall back to default
        }
        return defaultBackoffMs;
    }

    private Exception mapError(int statusCode, HttpClientException e) {
        return switch (statusCode) {
            case 401 -> new IllegalStateException(
                "Authentication failed (401): Invalid or expired Mercury API token. " +
                "Verify the token is correct and has not been revoked.", e);
            case 403 -> new IllegalStateException(
                "Access forbidden (403): The API token does not have permission for this resource. " +
                "Check the token's scopes in your Mercury dashboard.", e);
            case 404 -> new IllegalStateException(
                "Not found (404): The requested Mercury resource does not exist " +
                "or is not visible to this token.", e);
            case 429 -> new IllegalStateException(
                "Rate limited (429): Too many requests to Mercury API. " +
                "All retry attempts exhausted. Try again later.", e);
            default -> {
                if (statusCode >= 500 && statusCode <= 599) {
                    yield new IllegalStateException(
                        "Mercury server error (" + statusCode + "): " + e.getMessage() +
                        ". Retry later or contact Mercury support if the problem persists.", e);
                }
                yield new IllegalStateException(
                    "Mercury API error (" + statusCode + "): " + e.getMessage(), e);
            }
        };
    }

    static int extractStatusCode(HttpClientException e) {
        if (e instanceof HttpClientResponseException responseException
            && responseException.getResponse() != null
            && responseException.getResponse().getStatus() != null) {
            return responseException.getResponse().getStatus().getCode();
        }
        return 0;
    }

    URI buildUri(String baseUrl, String path, Map<String, Object> queryParams) {
        var sb = new StringBuilder(baseUrl);
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            sb.setLength(sb.length() - 1);
        } else if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            sb.append("/");
        }
        sb.append(path);

        if (queryParams != null && !queryParams.isEmpty()) {
            sb.append("?");
            var first = new boolean[]{true};
            queryParams.forEach((key, value) -> {
                if (value != null) {
                    if (!first[0]) {
                        sb.append("&");
                    }
                    sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                      .append("=")
                      .append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
                    first[0] = false;
                }
            });
        }

        return URI.create(sb.toString());
    }
}
