package io.kestra.plugin.mercury.accounts;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.kestra.plugin.mercury.model.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;
import java.util.UUID;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a Mercury account",
    description = "Retrieve details for a single Mercury bank account by its ID."
)
@Plugin(
    examples = {
        @Example(
            title = "Get a Mercury account by ID",
            full = true,
            code = """
                id: get_mercury_account
                namespace: company.team

                tasks:
                  - id: get_account
                    type: io.kestra.plugin.mercury.accounts.Get
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "d0e567d8-76a7-11e9-a22d-0b33e9de0755"
                """
        )
    }
)
public class Get extends AbstractMercuryConnection implements RunnableTask<Get.Output> {

    private static final TypeReference<Account> ACCOUNT_TYPE_REF = new TypeReference<>() {};

    @Schema(
        title = "Account ID",
        description = "The unique identifier (UUID) of the Mercury account to retrieve."
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> accountId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAccountId = runContext.render(this.accountId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("accountId is required"));

        try {
            UUID.fromString(rAccountId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid accountId '" + rAccountId + "': must be a valid UUID", e);
        }

        try (var client = createClient(runContext)) {
            Account account = request(client, runContext, "/account/" + rAccountId.trim(), Map.of(), ACCOUNT_TYPE_REF);
            if (account == null) {
                throw new IllegalStateException("Not found (404): The requested Mercury resource does not exist or is not visible to this token.");
            }

            return Output.builder()
                .account(account)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Account details",
            description = "The retrieved Mercury bank account."
        )
        private final Account account;
    }
}
