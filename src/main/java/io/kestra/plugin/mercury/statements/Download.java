package io.kestra.plugin.mercury.statements;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Download Mercury statement PDF",
    description = """
        Download an official statement PDF for a Mercury depository bank account directly to internal storage."""
)
@Plugin(
    examples = {
        @Example(
            title = "Download a statement PDF to internal storage",
            full = true,
            code = """
                id: download_statement_pdf
                namespace: company.team

                tasks:
                  - id: download_statement
                    type: io.kestra.plugin.mercury.statements.Download
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    statementId: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                """
        ),
        @Example(
            title = "List and download statements for an account",
            full = true,
            code = """
                id: list_and_download_statements
                namespace: company.team

                tasks:
                  - id: list_statements
                    type: io.kestra.plugin.mercury.statements.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                    fetchType: FETCH

                  - id: download_statements
                    type: io.kestra.plugin.core.flow.ForEach
                    values: "{{ outputs.list_statements.rows }}"
                    tasks:
                      - id: download_item
                        type: io.kestra.plugin.mercury.statements.Download
                        apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                        statementId: "{{ taskrun.value.id }}"
                """
        )
    }
)
public class Download extends AbstractMercuryConnection implements RunnableTask<Download.Output> {

    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final Pattern FILENAME_STAR_PATTERN = Pattern.compile("(?i)(?:^|[;\\s])filename\\*\\s*=\\s*([^;]+)");
    private static final Pattern QUOTED_FILENAME_PATTERN = Pattern.compile("(?i)(?:^|[;\\s])filename\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern UNQUOTED_FILENAME_PATTERN = Pattern.compile("(?i)(?:^|[;\\s])filename\\s*=\\s*([^;\\s]+)");

    @Schema(
        title = "Statement ID",
        description = """
            The unique identifier (UUID) of the statement to download."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> statementId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rStatementId = requireUuid(
            runContext.render(this.statementId).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("statementId is required")),
            "statementId"
        );

        try (var client = createClient(runContext)) {
            var binary = downloadBinary(
                client,
                runContext,
                "/statements/%s/pdf".formatted(rStatementId),
                null,
                httpResponse -> {
                    var contentType = httpResponse.contentType();
                    if (contentType == null && httpResponse.getHeaders() != null) {
                        contentType = httpResponse.getHeaders().firstValue("Content-Type").orElse(null);
                    }
                    if (!isPdfContentType(contentType)) {
                        throw new IllegalStateException(
                            "Expected Content-Type 'application/pdf' but received '%s' for statementId '%s'"
                                .formatted(contentType != null ? contentType : "none", rStatementId)
                        );
                    }
                },
                size -> {
                    if (size == 0) {
                        throw new IllegalStateException(
                            "Statement PDF response body is empty for statementId '%s'".formatted(rStatementId)
                        );
                    }
                }
            );

            var fileName = parseFileName(binary.contentDisposition(), rStatementId);

            return Output.builder()
                .uri(binary.uri())
                .size(binary.size())
                .fileName(fileName)
                .build();
        }
    }

    static boolean isPdfContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        var mime = contentType.split(";")[0].trim();
        return mime.equalsIgnoreCase("application/pdf");
    }

    static String parseFileName(String contentDisposition, String statementId) {
        var defaultName = "statement-%s.pdf".formatted(statementId);
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return defaultName;
        }

        String candidate = null;

        var starMatcher = FILENAME_STAR_PATTERN.matcher(contentDisposition);
        if (starMatcher.find()) {
            var rawValue = starMatcher.group(1).trim();
            var parts = rawValue.split("'", 3);
            if (parts.length == 3) {
                var charsetName = parts[0].trim();
                var encoded = parts[2].trim();
                try {
                    var charset = charsetName.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
                    candidate = URLDecoder.decode(encoded, charset);
                } catch (Exception ignored) {
                }
            }
        }

        if (candidate == null || candidate.isBlank()) {
            var quotedMatcher = QUOTED_FILENAME_PATTERN.matcher(contentDisposition);
            if (quotedMatcher.find()) {
                candidate = quotedMatcher.group(1);
            } else {
                var unquotedMatcher = UNQUOTED_FILENAME_PATTERN.matcher(contentDisposition);
                if (unquotedMatcher.find()) {
                    candidate = unquotedMatcher.group(1);
                }
            }
        }

        if (candidate == null || candidate.isBlank()) {
            return defaultName;
        }

        var segment = lastPathSegment(candidate);
        var sanitized = sanitizeFileName(segment);

        if (sanitized.isEmpty() || sanitized.equalsIgnoreCase(".pdf") || sanitized.equals(".")) {
            return defaultName;
        }

        return ensurePdfExtension(sanitized);
    }

    private static String lastPathSegment(String path) {
        var lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static String sanitizeFileName(String name) {
        var clean = name.replaceAll("[\"'/\\\\]", "");
        clean = clean.replaceAll("\\p{Cntrl}", "");
        clean = clean.replace("..", "");
        return clean.trim();
    }

    private static String ensurePdfExtension(String name) {
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            var base = name.length() > MAX_FILE_NAME_LENGTH - 4
                ? name.substring(0, MAX_FILE_NAME_LENGTH - 4)
                : name;
            return base + ".pdf";
        }

        if (name.length() > MAX_FILE_NAME_LENGTH) {
            var base = name.substring(0, name.length() - 4);
            if (base.length() > MAX_FILE_NAME_LENGTH - 4) {
                base = base.substring(0, MAX_FILE_NAME_LENGTH - 4);
            }
            return base + ".pdf";
        }

        return name;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Internal storage URI",
            description = """
                The URI of the downloaded PDF file stored in Kestra internal storage."""
        )
        private final URI uri;

        @Schema(
            title = "File size",
            description = """
                The size of the downloaded statement PDF file in bytes."""
        )
        private final Long size;

        @Schema(
            title = "File name",
            description = """
                The sanitized metadata file name derived from the Content-Disposition header, ending with .pdf."""
        )
        private final String fileName;
    }
}
