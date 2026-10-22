package io.kestra.plugin.mercury.transactions;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.kestra.plugin.mercury.model.Transaction;
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
    title = "Get a Mercury transaction",
    description = """
        Retrieve full details for a single Mercury bank transaction by its ID."""
)
@Plugin(
    examples = {
        @Example(
            title = "Get a Mercury transaction by ID",
            full = true,
            code = """
                id: get_mercury_transaction
                namespace: company.team

                tasks:
                  - id: get_transaction
                    type: io.kestra.plugin.mercury.transactions.Get
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "d0e567d8-76a7-11e9-a22d-0b33e9de0755"
                    transactionId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                """
        )
    }
)
public class Get extends AbstractMercuryConnection implements RunnableTask<Get.Output> {

    private static final TypeReference<Transaction> TRANSACTION_TYPE_REF = new TypeReference<>() {};

    @Schema(
        title = "Account ID",
        description = """
            The unique identifier (UUID) of the Mercury account that owns the transaction."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> accountId;

    @Schema(
        title = "Transaction ID",
        description = """
            The unique identifier (UUID) of the Mercury transaction to retrieve."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> transactionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAccountId = runContext.render(this.accountId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("accountId is required"));

        try {
            UUID.fromString(rAccountId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid accountId '" + rAccountId + "': must be a valid UUID", e);
        }

        var rTransactionId = runContext.render(this.transactionId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("transactionId is required"));

        try {
            UUID.fromString(rTransactionId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid transactionId '" + rTransactionId + "': must be a valid UUID", e);
        }

        try (var client = createClient(runContext)) {
            var path = "/account/" + rAccountId.trim() + "/transaction/" + rTransactionId.trim();
            var transaction = request(client, runContext, path, Map.of(), TRANSACTION_TYPE_REF);
            if (transaction == null) {
                throw new IllegalStateException("Empty response from the Mercury API for transaction '" + rTransactionId.trim() + "'");
            }

            return Output.builder()
                .transaction(transaction)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Transaction details",
            description = """
                The retrieved Mercury bank transaction."""
        )
        private final Transaction transaction;
    }
}
