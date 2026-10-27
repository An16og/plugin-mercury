package io.kestra.plugin.mercury;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public final class WireMockTestUtils {

    private WireMockTestUtils() {
    }

    public static void stubGet(WireMockServer server, String path, String responseBody) {
        server.stubFor(get(urlPathEqualTo(path))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody)));
    }

    public static void stubGet(WireMockServer server, String path, Map<String, String> queryParams, String responseBody) {
        stubGet(server, path, queryParams, Map.of(), responseBody);
    }

    public static void stubGet(WireMockServer server, String path, Map<String, String> queryParams, Map<String, String> headers, String responseBody) {
        var builder = get(urlPathEqualTo(path));
        if (queryParams != null) {
            queryParams.forEach((k, v) -> builder.withQueryParam(k, equalTo(v)));
        }
        headers.forEach((k, v) -> builder.withHeader(k, equalTo(v)));
        server.stubFor(builder
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody)));
    }

    public static String recipientsPageJson(String recipientsJson, String nextPage, String previousPage, int total) {
        var next = nextPage != null ? "\"%s\"".formatted(nextPage) : "null";
        var prev = previousPage != null ? "\"%s\"".formatted(previousPage) : "null";
        return """
            {
              "total": %d,
              "recipients": [%s],
              "page": { "nextPage": %s, "previousPage": %s }
            }
            """.formatted(total, recipientsJson, next, prev);
    }

    public static String cardsPageJson(String cardsJson, String nextPage, String previousPage) {
        var next = nextPage != null ? "\"%s\"".formatted(nextPage) : "null";
        var prev = previousPage != null ? "\"%s\"".formatted(previousPage) : "null";
        return """
            {
              "cards": [%s],
              "page": { "nextPage": %s, "previousPage": %s }
            }
            """.formatted(cardsJson, next, prev);
    }
}
