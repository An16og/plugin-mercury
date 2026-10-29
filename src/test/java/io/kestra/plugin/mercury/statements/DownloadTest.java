package io.kestra.plugin.mercury.statements;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.storages.StorageInterface;
import io.kestra.storage.local.LocalStorage;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.kestra.plugin.mercury.WireMockTestUtils.stubGetBinary;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class DownloadTest {

    private static final String STATEMENT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private ModelValidator modelValidator;

    @Inject
    private StorageInterface storageInterface;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testDownloadBinaryWithNonUtf8BytesIdenticalStorage() throws Exception {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);

        var header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        var binaryPayload = new byte[header.length + 256];
        System.arraycopy(header, 0, binaryPayload, 0, header.length);
        for (var i = 0; i < 256; i++) {
            binaryPayload[header.length + i] = (byte) i;
        }

        stubGetBinary(
            wireMockServer,
            path,
            200,
            "application/pdf",
            "attachment; filename=\"statement-2024-01.pdf\"",
            binaryPayload
        );

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is((long) binaryPayload.length));
        assertThat(output.getFileName(), is("statement-2024-01.pdf"));
        assertThat(output.getUri(), notNullValue());

        try (InputStream is = runContext.storage().getFile(output.getUri())) {
            var storedBytes = is.readAllBytes();
            assertThat(storedBytes.length, is(binaryPayload.length));
            assertArrayEquals(binaryPayload, storedBytes);
        }
    }

    @Test
    void testFileNameParsingTable() {
        assertThat(
            Download.parseFileName("attachment; filename=statement.pdf", STATEMENT_ID),
            is("statement.pdf")
        );

        assertThat(
            Download.parseFileName("attachment; filename=\"statement 2024.pdf\"", STATEMENT_ID),
            is("statement 2024.pdf")
        );

        assertThat(
            Download.parseFileName("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''statement%202024.pdf", STATEMENT_ID),
            is("statement 2024.pdf")
        );

        assertThat(
            Download.parseFileName("attachment; filename=\"../../evil/path/statement.pdf\"", STATEMENT_ID),
            is("statement.pdf")
        );
        assertThat(
            Download.parseFileName("attachment; filename=\"..\\\\..\\\\statement.pdf\"", STATEMENT_ID),
            is("statement.pdf")
        );

        assertThat(
            Download.parseFileName(null, STATEMENT_ID),
            is("statement-%s.pdf".formatted(STATEMENT_ID))
        );
        assertThat(
            Download.parseFileName("", STATEMENT_ID),
            is("statement-%s.pdf".formatted(STATEMENT_ID))
        );
        assertThat(
            Download.parseFileName("attachment", STATEMENT_ID),
            is("statement-%s.pdf".formatted(STATEMENT_ID))
        );

        assertThat(
            Download.parseFileName("attachment; filename*=UTF-8''%C3%A9t%C3%A9%202024.pdf", STATEMENT_ID),
            is("été 2024.pdf")
        );

        assertThat(
            Download.parseFileName("attachment; filename=monthly-statement", STATEMENT_ID),
            is("monthly-statement.pdf")
        );

        var longName = "%s.pdf".formatted("a".repeat(300));
        var parsedLong = Download.parseFileName("attachment; filename=\"%s\"".formatted(longName), STATEMENT_ID);
        assertThat(parsedLong.length(), is(255));
        assertThat(parsedLong.endsWith(".pdf"), is(true));

        assertThat(
            Download.parseFileName("attachment; filename=\"stat\u0000e\u001Fment.pdf\"", STATEMENT_ID),
            is("statement.pdf")
        );
    }

    @Test
    void testNonPdfContentTypeFailsNothingReachesStorage() throws Exception {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);

        stubGetBinary(
            wireMockServer,
            path,
            200,
            "text/html; charset=UTF-8",
            "attachment; filename=\"statement.pdf\"",
            "<html><body>Error</body></html>".getBytes(StandardCharsets.UTF_8)
        );

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var beforeCount = countStoredFiles();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Expected Content-Type 'application/pdf' but received 'text/html; charset=UTF-8'"));

        var afterCount = countStoredFiles();
        assertThat(afterCount, is(beforeCount));

        try (var stream = Files.list(runContext.workingDir().path())) {
            var tempFiles = stream.filter(Files::isRegularFile).count();
            assertThat(tempFiles, is(0L));
        }
    }

    @Test
    void testEmptyBodyFailsNothingReachesStorage() throws Exception {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);

        stubGetBinary(
            wireMockServer,
            path,
            200,
            "application/pdf",
            "attachment; filename=\"statement.pdf\"",
            new byte[0]
        );

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var beforeCount = countStoredFiles();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Statement PDF response body is empty for statementId '%s'".formatted(STATEMENT_ID)));

        var afterCount = countStoredFiles();
        assertThat(afterCount, is(beforeCount));

        try (var stream = Files.list(runContext.workingDir().path())) {
            var tempFiles = stream.filter(Files::isRegularFile).count();
            assertThat(tempFiles, is(0L));
        }
    }

    @Test
    void testConnectionResetMidBodyFailsWithMercuryRetryMessage() {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/pdf")
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Failed to download file from Mercury:"));
        assertThat(ex.getMessage(), containsString("Retry the task."));
        assertThat(ex.getCause(), notNullValue());
    }

    @Test
    void testHttp404Fails() {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Statement not found\"}")));

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Not found (404)"));
    }

    @Test
    void testHttp401Fails() {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Unauthorized\"}")));

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Authentication failed (401)"));
    }

    @Test
    void testHttp429RetryThenSuccess() throws Exception {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);
        var pdfBytes = "%PDF-1.4 test binary data".getBytes(StandardCharsets.UTF_8);

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .inScenario("429-retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "0")
                .withBody("{\"error\": \"Rate limited\"}"))
            .willSetStateTo("succeeded"));

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .inScenario("429-retry")
            .whenScenarioStateIs("succeeded")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/pdf")
                .withBody(pdfBytes)));

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is((long) pdfBytes.length));
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo(path)));
    }

    @Test
    void testHttp3xxRedirectRejectedWithoutCallingTarget() {
        var path = "/statements/%s/pdf".formatted(STATEMENT_ID);
        var redirectLocation = "%s/external-leak-target".formatted(wireMockServer.baseUrl());

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", redirectLocation)));

        wireMockServer.stubFor(get(urlPathEqualTo("/external-leak-target"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("should never be reached")));

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Unexpected redirect (302)"));

        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/external-leak-target")));
    }

    @Test
    void testInvalidStatementIdRejectedWithNoRequest() {
        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue("invalid-uuid-string"))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Invalid statementId 'invalid-uuid-string': must be a valid UUID"));

        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testModelValidator() {
        var validTask = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();
        assertThat(modelValidator.isValid(validTask).isEmpty(), is(true));

        var missingStatementId = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();
        assertThat(modelValidator.isValid(missingStatementId).isPresent(), is(true));
    }

    @Test
    void testFlowYamlValidation() {
        var flowYaml = """
            id: flow_validating_download
            namespace: io.kestra.tests
            tasks:
              - id: download_statement
                type: io.kestra.plugin.mercury.statements.Download
                apiToken: "secret-token:valid-token"
                statementId: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            """;
        var flow = YamlParser.parse(flowYaml, Flow.class);
        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void testPluginExamplesFlowValidation() {
        var plugin = Download.class.getAnnotation(Plugin.class);
        assertThat(plugin, notNullValue());
        for (var example : plugin.examples()) {
            var code = String.join("\n", example.code());
            var flow = YamlParser.parse(code, Flow.class);
            assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
        }
    }

    private long countStoredFiles() throws Exception {
        if (storageInterface instanceof LocalStorage localStorage) {
            var basePath = localStorage.getBasePath();
            if (basePath != null && Files.exists(basePath)) {
                try (var stream = Files.walk(basePath)) {
                    return stream.filter(Files::isRegularFile).count();
                }
            }
        }
        return 0;
    }
}
