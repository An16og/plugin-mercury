package io.kestra.plugin.mercury;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVStore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMercuryTrigger extends AbstractTrigger implements PollingTriggerInterface {

    @Schema(
        title = "Mercury API base URL",
        description = """
            Base URL for the Mercury API. Override for the Mercury sandbox (https://api-sandbox.mercury.com/api/v1). Must use HTTPS, except for loopback hosts (localhost, 127.0.0.1, ::1) during local testing."""
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> baseUrl = Property.ofValue(AbstractMercuryConnection.DEFAULT_BASE_URL);

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

    /**
     * Reads the persisted trigger state. A missing key returns null (first poll). A failed read or an
     * unparseable value is rethrown: treating it as a first poll would silently skip or replay events.
     */
    protected static <S> S readState(RunContext runContext, KVStore kv, String key, Class<S> type) throws Exception {
        try {
            var kvValue = kv.getValue(key);
            if (kvValue.isEmpty() || kvValue.get().value() == null) {
                return null;
            }
            var raw = kvValue.get().value();
            if (raw instanceof String rawStr) {
                return rawStr.isBlank() ? null : JacksonMapper.ofJson().readValue(rawStr, type);
            }
            return JacksonMapper.ofJson().convertValue(raw, type);
        } catch (Exception e) {
            runContext.logger().warn("Unable to read trigger state from KV key '{}', this poll is skipped and retried at the next interval", key, e);
            throw e;
        }
    }
}
