package io.kestra.plugin.mercury.accounts;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.kestra.plugin.mercury.model.Account;
import io.kestra.plugin.mercury.model.AccountPage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Mercury accounts",
    description = """
        Retrieve all Mercury bank accounts with automatic cursor-based pagination."""
)
@Plugin(
    examples = {
        @Example(
            title = "List all Mercury accounts into memory",
            full = true,
            code = """
                id: list_mercury_accounts
                namespace: company.team

                tasks:
                  - id: list_accounts
                    type: io.kestra.plugin.mercury.accounts.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "Stream all Mercury accounts to internal storage",
            full = true,
            code = """
                id: store_mercury_accounts
                namespace: company.team

                tasks:
                  - id: store_accounts
                    type: io.kestra.plugin.mercury.accounts.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractMercuryConnection implements RunnableTask<List.Output> {

    private static final TypeReference<AccountPage> ACCOUNT_PAGE_TYPE_REF = new TypeReference<>() {};

    public enum Order {
        ASC,
        DESC
    }

    @Schema(
        title = "Fetch mode",
        description = """
            Controls how results are returned: FETCH to return all rows in memory, FETCH_ONE for the first row, STORE to stream records as ION into internal storage, or NONE to skip collecting outputs."""
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(
        title = "Results per page",
        description = """
            Maximum number of accounts to return per page. Minimum 1, maximum 1000. Default is 1000."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) @Max(1000) Integer> limit = Property.ofValue(1000);

    @Schema(
        title = "Sort order",
        description = """
            Sort order for accounts. Allowed values: ASC or DESC. Default is ASC."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Order> order = Property.ofValue(Order.ASC);

    @Schema(
        title = "Maximum pages to retrieve",
        description = """
            Safety cap on the number of pages fetched during pagination. Minimum 1. Default is 100."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<@Min(1) Integer> pageCap = Property.ofValue(DEFAULT_MAX_PAGE_CAP);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(1000);
        if (rLimit < 1 || rLimit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got: " + rLimit);
        }

        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(DEFAULT_MAX_PAGE_CAP);
        if (rPageCap < 1) {
            throw new IllegalArgumentException("pageCap must be at least 1, got: " + rPageCap);
        }

        var rOrder = runContext.render(this.order).as(Order.class).orElse(Order.ASC);

        try (var client = createClient(runContext)) {
            PageFetcher<Account> fetcher = cursor -> {
                var queryParams = new HashMap<String, Object>();
                queryParams.put("limit", rLimit);
                queryParams.put("order", rOrder.name().toLowerCase());
                if (cursor != null && !cursor.isBlank()) {
                    queryParams.put("start_after", cursor);
                }

                var page = request(client, runContext, "/accounts", queryParams, ACCOUNT_PAGE_TYPE_REF);
                if (page == null) {
                    return new PageResult<>(java.util.List.of(), null);
                }

                var items = page.getAccounts() != null ? page.getAccounts() : java.util.List.<Account>of();
                var nextCursor = page.getPage() != null ? page.getPage().getNextPage() : null;
                return new PageResult<>(items, nextCursor);
            };

            return switch (rFetchType) {
                case FETCH -> {
                    var rows = new ArrayList<Account>();
                    paginate(runContext, rPageCap, "accounts", fetcher, account -> {
                        rows.add(account);
                        return true;
                    });
                    yield Output.builder()
                        .size(rows.size())
                        .rows(rows)
                        .build();
                }
                case FETCH_ONE -> {
                    var firstAccount = new AtomicReference<Account>();
                    paginate(runContext, rPageCap, "accounts", fetcher, account -> {
                        firstAccount.set(account);
                        return false;
                    });
                    var found = firstAccount.get();
                    yield Output.builder()
                        .size(found != null ? 1 : 0)
                        .row(found)
                        .build();
                }
                case STORE -> {
                    var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                    var storedCount = new AtomicInteger(0);
                    try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                        paginate(runContext, rPageCap, "accounts", fetcher, account -> {
                            try {
                                FileSerde.write(output, JacksonMapper.toMap(account));
                                storedCount.incrementAndGet();
                                return true;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed writing account record to ION storage", e);
                            }
                        });
                    }
                    var uri = runContext.storage().putFile(tempFile);
                    yield Output.builder()
                        .size(storedCount.get())
                        .uri(uri)
                        .build();
                }
                case NONE -> {
                    paginate(runContext, rPageCap, "accounts", fetcher, account -> true);
                    yield Output.builder()
                        .size(0)
                        .build();
                }
            };
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The total number of accounts returned",
            description = """
                For FETCH this matches the size of rows, for FETCH_ONE 0 or 1, for STORE the count of stored records, and 0 for NONE."""
        )
        private final Integer size;

        @Schema(
            title = "The accounts fetched",
            description = """
                List of retrieved accounts. Only populated when fetchType is FETCH."""
        )
        private final java.util.List<Account> rows;

        @Schema(
            title = "The first account fetched",
            description = """
                The first account retrieved. Only populated when fetchType is FETCH_ONE."""
        )
        private final Account row;

        @Schema(
            title = "URI of stored results",
            description = """
                The URI of the file containing ION-formatted records in internal storage. Only populated when fetchType is STORE."""
        )
        private final URI uri;
    }
}
