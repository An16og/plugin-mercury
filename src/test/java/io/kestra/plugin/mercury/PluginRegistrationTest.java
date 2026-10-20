package io.kestra.plugin.mercury;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.plugins.PluginRegistry;
import io.kestra.plugin.mercury.accounts.Get;
import io.kestra.plugin.mercury.accounts.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class PluginRegistrationTest {

    @Inject
    private PluginRegistry pluginRegistry;

    @Test
    void testPluginRegistration() {
        assertThat(pluginRegistry, notNullValue());

        var plugins = pluginRegistry.plugins();
        assertThat(plugins, notNullValue());

        boolean hasListTask = plugins.stream()
            .flatMap(p -> p.getTasks().stream())
            .anyMatch(taskClass -> taskClass.equals(List.class));

        boolean hasGetTask = plugins.stream()
            .flatMap(p -> p.getTasks().stream())
            .anyMatch(taskClass -> taskClass.equals(Get.class));

        assertThat("accounts.List must be registered as a Kestra task plugin", hasListTask, is(true));
        assertThat("accounts.Get must be registered as a Kestra task plugin", hasGetTask, is(true));

        assertThat(List.class.getAnnotation(Plugin.class), notNullValue());
        assertThat(Get.class.getAnnotation(Plugin.class), notNullValue());
    }
}
