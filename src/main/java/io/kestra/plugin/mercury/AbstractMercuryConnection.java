package io.kestra.plugin.mercury;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.AbstractAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.message.BasicHeader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMercuryConnection extends Task {

    public static final String DEFAULT_BASE_URL = "https://api.mercury.com/api/v1";
    public static final int DEFAULT_MAX_PAGE_CAP = 100;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;

    @Schema(
        title = "Mercury API base URL",
        description = """
            Base URL for the Mercury API. Override for the Mercury sandbox (https://api-sandbox.mercury.com/api/v1). Must use HTTPS, except for loopback hosts (localhost, 127.0.0.1, ::1) during local testing."""
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "Mercury API token",
        description = """
            API token for authentication with the Mercury API."""
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @NotNull
    private Property<String> apiToken;

    public record PageResult<T>(List<T> items, String nextCursor) {}

    @FunctionalInterface
    public interface PageFetcher<T> {
        PageResult<T> fetch(String cursor) throws Exception;
    }

    @FunctionalInterface
    public interface FetchOutputFactory<T, O> {
        O create(Integer size, List<T> rows, T row, URI uri);
    }

    public record OffsetPageResult<T>(List<T> items, long total) {}

    @FunctionalInterface
    public interface OffsetPageFetcher<T> {
        OffsetPageResult<T> fetch(int offset) throws Exception;
    }

    public record BinaryResponse(URI uri, long size, String contentDisposition) {}

    protected HttpClient createClient(RunContext runContext) throws Exception {
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
        validateBaseUrl(rBaseUrl);

        var rToken = runContext.render(this.apiToken).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("apiToken is required"));

        var auth = new AbstractAuthConfiguration() {
            @Override
            public AuthType getType() {
                return AuthType.BEARER;
            }

            @Override
            public void configure(HttpClientBuilder builder, RunContext ctx) {
                builder.addRequestInterceptorFirst((request, entity, context) ->
                    request.setHeader(new BasicHeader("Authorization", "Bearer " + rToken))
                );
                builder.disableAutomaticRetries();
                builder.disableRedirectHandling();
            }
        };

        var httpConfiguration = HttpConfiguration.builder()
            .auth(auth)
            .followRedirects(Property.ofValue(false))
            .build();

        return new HttpClient(runContext, httpConfiguration);
    }

    protected <T> T request(HttpClient client, RunContext runContext, String path,
                            Map<String, Object> queryParams, TypeReference<T> typeRef) throws Exception {
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
        validateBaseUrl(rBaseUrl);

        var uri = buildUri(rBaseUrl, path, queryParams);
        var request = HttpRequest.builder()
            .method("GET")
            .uri(uri)
            .build();

        return executeWithRetry(client, runContext, request, typeRef);
    }

    protected BinaryResponse downloadBinary(HttpClient client, RunContext runContext, String path,
                                            Map<String, Object> queryParams,
                                            Consumer<HttpResponse<InputStream>> responseValidator,
                                            Consumer<Long> sizeValidator) throws Exception {
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
        validateBaseUrl(rBaseUrl);

        var uri = buildUri(rBaseUrl, path, queryParams);
        var request = HttpRequest.builder()
            .method("GET")
            .uri(uri)
            .build();

        return executeDownloadWithRetry(client, runContext, request, responseValidator, sizeValidator);
    }

    protected <T> int paginate(RunContext runContext, int pageCap, String entityName,
                               PageFetcher<T> fetcher, Predicate<T> itemConsumer) throws Exception {
        var logger = runContext.logger();
        String currentCursor = null;
        var pageCount = 0;
        var totalItems = 0;
        var seenCursors = new HashSet<String>();

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

            if (pageCount >= pageCap) {
                throw new IllegalStateException(
                    "Pagination reached the maximum pageCap of %d but more %s remain. Increase 'pageCap' on the task to fetch more pages."
                        .formatted(pageCap, entityName));
            }

            currentCursor = nextCursor;
        }

        return totalItems;
    }

    protected <T, O> O fetch(RunContext runContext, FetchType fetchType, int pageCap,
                             String entityName, PageFetcher<T> fetcher,
                             FetchOutputFactory<T, O> outputFactory) throws Exception {
        return switch (fetchType) {
            case FETCH -> {
                var rows = new ArrayList<T>();
                paginate(runContext, pageCap, entityName, fetcher, item -> {
                    rows.add(item);
                    return true;
                });
                yield outputFactory.create(rows.size(), rows, null, null);
            }
            case FETCH_ONE -> {
                var firstItem = new AtomicReference<T>();
                paginate(runContext, pageCap, entityName, fetcher, item -> {
                    firstItem.set(item);
                    return false;
                });
                var found = firstItem.get();
                yield outputFactory.create(found != null ? 1 : 0, null, found, null);
            }
            case STORE -> {
                var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                var storedCount = new AtomicInteger(0);
                try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                    paginate(runContext, pageCap, entityName, fetcher, item -> {
                        try {
                            FileSerde.write(output, JacksonMapper.toMap(item));
                            storedCount.incrementAndGet();
                            return true;
                        } catch (Exception e) {
                            throw new RuntimeException("Failed writing %s record to ION storage".formatted(entityName), e);
                        }
                    });
                }
                var uri = runContext.storage().putFile(tempFile);
                yield outputFactory.create(storedCount.get(), null, null, uri);
            }
            case NONE -> {
                paginate(runContext, pageCap, entityName, fetcher, item -> true);
                yield outputFactory.create(0, null, null, null);
            }
        };
    }

    protected <T> int paginateOffset(RunContext runContext, int pageCap,
                                     OffsetPageFetcher<T> fetcher, Predicate<T> itemConsumer) throws Exception {
        var logger = runContext.logger();
        var offset = 0;
        var pageCount = 0;
        var totalItems = 0;

        while (pageCount < pageCap) {
            pageCount++;
            var page = fetcher.fetch(offset);
            if (page == null || page.items() == null || page.items().isEmpty()) {
                logger.debug("Pagination stopped: empty page encountered on page {}", pageCount);
                break;
            }

            var items = page.items();
            var stopRequested = false;
            for (var item : items) {
                totalItems++;
                if (!itemConsumer.test(item)) {
                    stopRequested = true;
                    break;
                }
            }

            if (stopRequested) {
                break;
            }

            offset += items.size();

            if (offset >= page.total()) {
                logger.debug("Pagination stopped: offset {} reached or exceeded total {} on page {}", offset, page.total(), pageCount);
                break;
            }

            if (pageCount >= pageCap) {
                throw new IllegalStateException(
                    "Pagination reached the maximum pageCap of " + pageCap +
                    " but more transactions remain. Increase 'pageCap' on the task to fetch more pages.");
            }
        }

        return totalItems;
    }

    protected static String requireUuid(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        var trimmed = value.trim();
        try {
            UUID.fromString(trimmed);
            return trimmed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + field + " '" + value + "': must be a valid UUID", e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class DownloadFailure extends RuntimeException {
        private DownloadFailure(IllegalStateException cause) {
            super(cause);
        }

        @Override
        public synchronized IllegalStateException getCause() {
            return (IllegalStateException) super.getCause();
        }
    }

    private <T> T withRetry(RunContext runContext, ThrowingSupplier<T> action) throws Exception {
        var logger = runContext.logger();
        var backoffMs = INITIAL_BACKOFF_MS;

        for (var attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (HttpClientResponseException e) {
                var status = extractStatusCode(e);
                if (status == 429 && attempt < MAX_RETRY_ATTEMPTS) {
                    var waitMs = extractRetryAfterMs(runContext, e, backoffMs);
                    if (waitMs > MAX_BACKOFF_MS) {
                        logger.warn("Retry-After wait of {}ms exceeds max backoff cap of {}ms; failing request", waitMs, MAX_BACKOFF_MS);
                        throw new IllegalStateException(
                            "Rate limited (429): Mercury asked to wait " + waitMs + " ms (Retry-After), above the " + MAX_BACKOFF_MS + " ms cap. Try again later.", e);
                    }
                    logger.warn("Rate limited (429), retry {}/{} after {}ms", attempt, MAX_RETRY_ATTEMPTS, waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during rate limit backoff", ie);
                    }
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                    continue;
                }
                throw mapError(status, e);
            } catch (HttpClientException e) {
                throw mapError(0, e);
            }
        }
        throw new IllegalStateException(
            "Rate limited (429): Too many requests to Mercury API. All retry attempts exhausted. Try again later.");
    }

    private <T> T executeWithRetry(HttpClient client, RunContext runContext,
                                   HttpRequest request, TypeReference<T> typeRef) throws Exception {
        return withRetry(runContext, () -> {
            var response = client.request(request, String.class);
            var statusCode = response.getStatus() != null ? response.getStatus().getCode() : 200;
            if (statusCode >= 300) {
                throw mapError(statusCode, new HttpClientResponseException("HTTP " + statusCode, response));
            }
            return parseBody(response.getBody(), typeRef);
        });
    }

    private BinaryResponse executeDownloadWithRetry(HttpClient client, RunContext runContext,
                                                    HttpRequest request,
                                                    Consumer<HttpResponse<InputStream>> responseValidator,
                                                    Consumer<Long> sizeValidator) throws Exception {
        return withRetry(runContext, () -> {
            File tempFile = null;
            var success = false;
            try {
                tempFile = runContext.workingDir().createTempFile(".tmp").toFile();
                var capturedContentDisposition = new AtomicReference<String>();
                final var fileToWrite = tempFile;

                try {
                    var response = client.request(request, (HttpResponse<InputStream> httpResponse) -> {
                        var statusCode = httpResponse.getStatus() != null ? httpResponse.getStatus().getCode() : 200;
                        if (statusCode >= 300) {
                            throw new DownloadFailure((IllegalStateException) mapError(
                                statusCode, new HttpClientResponseException("HTTP " + statusCode, httpResponse)
                            ));
                        }

                        if (responseValidator != null) {
                            try {
                                responseValidator.accept(httpResponse);
                            } catch (IllegalStateException e) {
                                throw new DownloadFailure(e);
                            }
                        }

                        var headers = httpResponse.getHeaders();
                        if (headers != null) {
                            headers.firstValue("Content-Disposition").ifPresent(capturedContentDisposition::set);
                        }

                        try (var in = httpResponse.getBody();
                             var out = new BufferedOutputStream(new FileOutputStream(fileToWrite))) {
                            if (in != null) {
                                in.transferTo(out);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });

                    var statusCode = response.getStatus() != null ? response.getStatus().getCode() : 200;
                    if (statusCode >= 300) {
                        throw mapError(statusCode, new HttpClientResponseException("HTTP " + statusCode, response));
                    }

                    var size = tempFile.length();
                    if (sizeValidator != null) {
                        try {
                            sizeValidator.accept(size);
                        } catch (IllegalStateException e) {
                            throw new DownloadFailure(e);
                        }
                    }

                    var storageUri = runContext.storage().putFile(tempFile);
                    success = true;
                    return new BinaryResponse(storageUri, size, capturedContentDisposition.get());
                } catch (DownloadFailure df) {
                    throw df.getCause();
                } catch (HttpClientResponseException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(
                        "Failed to download file from Mercury: %s. Retry the task.".formatted(e.getMessage()), e
                    );
                }
            } finally {
                if (!success && tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        });
    }

    private <T> T parseBody(String body, TypeReference<T> typeRef) throws IOException {
        if (body == null || body.isBlank()) {
            return null;
        }
        return JacksonMapper.ofJson().readValue(body, typeRef);
    }

    private long extractRetryAfterMs(RunContext runContext, HttpClientResponseException e, long defaultBackoffMs) {
        try {
            var response = e.getResponse();
            if (response != null && response.getHeaders() != null) {
                var retryAfterOpt = response.getHeaders().firstValue("Retry-After");
                if (retryAfterOpt != null && retryAfterOpt.isPresent()) {
                    var raw = retryAfterOpt.get().trim();
                    if (!raw.isBlank()) {
                        return parseRetryAfterHeader(runContext, raw, defaultBackoffMs);
                    }
                }
            }
        } catch (Exception ignored) {
            // header missing or inaccessible — fall back to default
        }
        return defaultBackoffMs;
    }

    private long parseRetryAfterHeader(RunContext runContext, String raw, long defaultBackoffMs) {
        try {
            var seconds = Long.parseLong(raw);
            return Math.max(0L, seconds * 1_000L);
        } catch (NumberFormatException nfe) {
            try {
                var date = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME);
                var waitMs = Duration.between(Instant.now(), date.toInstant()).toMillis();
                return Math.max(0L, waitMs);
            } catch (DateTimeParseException dtpe) {
                runContext.logger().warn("Unparseable Retry-After header '{}', falling back to bounded exponential backoff", raw);
                return defaultBackoffMs;
            }
        }
    }

    private Exception mapError(int statusCode, HttpClientException e) {
        if (statusCode >= 300 && statusCode <= 399) {
            return new IllegalStateException(
                "Unexpected redirect (" + statusCode + "): HTTP redirects are disabled to prevent credential leakage.", e);
        }
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

    static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be empty");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid baseUrl URI '" + baseUrl + "': " + e.getMessage(), e);
        }

        var scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("baseUrl must specify an HTTP or HTTPS scheme: " + baseUrl);
        }

        var lowerScheme = scheme.toLowerCase();
        if ("https".equals(lowerScheme)) {
            return;
        }

        if ("http".equals(lowerScheme)) {
            var host = uri.getHost();
            if (isLoopbackHost(host)) {
                return;
            }
            throw new IllegalArgumentException(
                "Insecure HTTP baseUrl is only permitted for loopback hosts (localhost, 127.0.0.1, ::1). " +
                "Production and sandbox Mercury environments require HTTPS: " + baseUrl);
        }

        throw new IllegalArgumentException("Unsupported baseUrl scheme '" + scheme + "': must be https (or http for local testing)");
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        var lower = host.toLowerCase();
        return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "::1".equals(lower) || "[::1]".equals(lower);
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
