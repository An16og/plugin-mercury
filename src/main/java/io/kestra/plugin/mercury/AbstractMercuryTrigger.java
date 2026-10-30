package io.kestra.plugin.mercury;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMercuryTrigger extends AbstractTrigger implements PollingTriggerInterface {

    public static final String DEFAULT_BASE_URL = "https://api.mercury.com/api/v1";
    private static final Pattern UUID_REGEX = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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

    @Schema(
        title = "Polling interval",
        description = """
            The interval between consecutive polls. Default is PT5M (5 minutes)."""
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private final Duration interval = Duration.ofMinutes(5);

    protected static String requireUuid(String value, String fieldName) {
        if (value == null || !UUID_REGEX.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Invalid %s '%s': must be a valid UUID".formatted(fieldName, value)
            );
        }
        return value;
    }
}
