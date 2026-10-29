package io.kestra.plugin.mercury.statements;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.YamlParser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
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
        var path = "/statements/" + STATEMENT_ID + "/pdf";

        // Construct binary body containing bytes invalid in UTF-8 (0x80-0xFF, 0x00, 0xC0) starting with %PDF-1.4
        var header = "%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var binaryPayload = new byte[header.length + 256];
        System.arraycopy(header, 0, binaryPayload, 0, header.length);
        for (int i = 0; i < 256; i++) {
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
        assertThat(output.getContentType(), is("application/pdf"));
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
        // Plain
        assertThat(
            Download.parseFileName("attachment; filename=statement.pdf", STATEMENT_ID),
            is("statement.pdf")
        );

        // Quoted
        assertThat(
            Download.parseFileName("attachment; filename=\"statement 2024.pdf\"", STATEMENT_ID),
            is("statement 2024.pdf")
        );

        // RFC 5987 filename* precedence
        assertThat(
            Download.parseFileName("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''statement%202024.pdf", STATEMENT_ID),
            is("statement 2024.pdf")
        );

        // Path traversal stripped
        assertThat(
            Download.parseFileName("attachment; filename=\"../../evil/path/statement.pdf\"", STATEMENT_ID),
            is("statement.pdf")
        );
        assertThat(
            Download.parseFileName("attachment; filename=\"..\\\\..\\\\statement.pdf\"", STATEMENT_ID),
            is("statement.pdf")
        );

        // Missing header falls back to statement-<id>.pdf
        assertThat(
            Download.parseFileName(null, STATEMENT_ID),
            is("statement-" + STATEMENT_ID + ".pdf")
        );
        assertThat(
            Download.parseFileName("", STATEMENT_ID),
            is("statement-" + STATEMENT_ID + ".pdf")
        );
        assertThat(
            Download.parseFileName("attachment", STATEMENT_ID),
            is("statement-" + STATEMENT_ID + ".pdf")
        );

        // Non-ASCII in filename*
        assertThat(
            Download.parseFileName("attachment; filename*=UTF-8''%C3%A9t%C3%A9%202024.pdf", STATEMENT_ID),
            is("été 2024.pdf")
        );

        // Name without .pdf extension gets .pdf appended
        assertThat(
            Download.parseFileName("attachment; filename=monthly-statement", STATEMENT_ID),
            is("monthly-statement.pdf")
        );

        // Over-long capped to 255 chars and ends with .pdf
        var longName = "a".repeat(300) + ".pdf";
        var parsedLong = Download.parseFileName("attachment; filename=\"" + longName + "\"", STATEMENT_ID);
        assertThat(parsedLong.length(), is(255));
        assertThat(parsedLong.endsWith(".pdf"), is(true));

        // Control characters stripped
        assertThat(
            Download.parseFileName("attachment; filename=\"stat\u0000e\u001Fment.pdf\"", STATEMENT_ID),
            is("statement.pdf")
        );
    }

    @Test
    void testNonPdfContentTypeFails() {
        var path = "/statements/" + STATEMENT_ID + "/pdf";

        stubGetBinary(
            wireMockServer,
            path,
            200,
            "text/html; charset=UTF-8",
            "attachment; filename=\"statement.pdf\"",
            "<html><body>Error</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        var task = Download.builder()
            .id(UUID.randomUUID().toString())
            .type(Download.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .statementId(Property.ofValue(STATEMENT_ID))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Expected Content-Type 'application/pdf' but received 'text/html; charset=UTF-8'"));
    }

    @Test
    void testEmptyBodyFails() {
        var path = "/statements/" + STATEMENT_ID + "/pdf";

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
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Statement PDF response body is empty for statementId '" + STATEMENT_ID + "'"));
    }

    @Test
    void testHttp404Fails() {
        var path = "/statements/" + STATEMENT_ID + "/pdf";

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
        var path = "/statements/" + STATEMENT_ID + "/pdf";

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
        var path = "/statements/" + STATEMENT_ID + "/pdf";
        var pdfBytes = "%PDF-1.4 test binary data".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .inScenario("429-retry")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
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
        var path = "/statements/" + STATEMENT_ID + "/pdf";
        var redirectLocation = wireMockServer.baseUrl() + "/external-leak-target";

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
}
