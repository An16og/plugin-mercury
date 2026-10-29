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
        var rStatementId = runContext.render(this.statementId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("statementId is required"));
        var statementId = requireUuid(rStatementId, "statementId");

        try (var client = createClient(runContext)) {
            var binary = downloadBinary(client, runContext, "/statements/" + statementId + "/pdf", null);

            if (binary.size() == 0) {
                throw new IllegalStateException("Statement PDF response body is empty for statementId '%s'".formatted(statementId));
            }

            var contentType = binary.contentType();
            if (!isPdfContentType(contentType)) {
                throw new IllegalStateException(
                    "Expected Content-Type 'application/pdf' but received '%s' for statementId '%s'"
                        .formatted(contentType != null ? contentType : "none", statementId)
                );
            }

            var fileName = parseFileName(binary.contentDisposition(), statementId);

            return Output.builder()
                .uri(binary.uri())
                .size(binary.size())
                .contentType(contentType)
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
                    // fall through to standard filename attribute
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

        // Keep only the last path segment (handling both / and \)
        var lastSlash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            candidate = candidate.substring(lastSlash + 1);
        }

        // Strip path separators, quotes, and control characters
        candidate = candidate.replaceAll("[\"'/\\\\]", "");
        candidate = candidate.replaceAll("\\p{Cntrl}", "");

        // Strip ".."
        candidate = candidate.replace("..", "");
        candidate = candidate.trim();

        if (candidate.isEmpty() || candidate.equalsIgnoreCase(".pdf") || candidate.equals(".")) {
            return defaultName;
        }

        // Ensure .pdf extension and cap length
        if (!candidate.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            if (candidate.length() > MAX_FILE_NAME_LENGTH - 4) {
                candidate = candidate.substring(0, MAX_FILE_NAME_LENGTH - 4);
            }
            candidate = candidate + ".pdf";
        } else {
            if (candidate.length() > MAX_FILE_NAME_LENGTH) {
                var base = candidate.substring(0, candidate.length() - 4);
                if (base.length() > MAX_FILE_NAME_LENGTH - 4) {
                    base = base.substring(0, MAX_FILE_NAME_LENGTH - 4);
                }
                candidate = base + ".pdf";
            }
        }

        return candidate;
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
            title = "Content type",
            description = """
                The MIME content type header returned by the server, such as 'application/pdf'."""
        )
        private final String contentType;

        @Schema(
            title = "File name",
            description = """
                The sanitized metadata file name derived from the Content-Disposition header, ending with .pdf."""
        )
        private final String fileName;
    }
}
