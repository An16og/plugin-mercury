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
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.File;
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
    description = "Retrieve all Mercury bank accounts with automatic cursor-based pagination."
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

    @Schema(
        title = "Fetch mode",
        description = "Controls how results are returned: FETCH to return all rows in memory, FETCH_ONE for the first row, STORE to stream records as ION into internal storage, or NONE to skip collecting outputs."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(
        title = "Results per page",
        description = "Maximum number of accounts to return per page. Allowed range is 1 to 1000. Default is 1000."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> limit = Property.ofValue(1000);

    @Schema(
        title = "Sort order",
        description = "Sort order for accounts. Allowed values: 'asc' or 'desc'. Default is 'asc'."
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<String> order = Property.ofValue("asc");

    @Schema(
        title = "Maximum pages to retrieve",
        description = "Safety cap on the number of pages fetched during pagination. Default is 100."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<Integer> pageCap = Property.ofValue(DEFAULT_MAX_PAGE_CAP);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(1000);
        var clampedLimit = Math.max(1, Math.min(rLimit, 1000));
        var rOrder = runContext.render(this.order).as(String.class).orElse("asc");
        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(DEFAULT_MAX_PAGE_CAP);

        try (var client = createClient(runContext)) {
            PageFetcher<Account> fetcher = cursor -> {
                Map<String, Object> queryParams = new HashMap<>();
                queryParams.put("limit", clampedLimit);
                queryParams.put("order", rOrder);
                if (cursor != null && !cursor.isBlank()) {
                    queryParams.put("start_after", cursor);
                }

                AccountPage page = request(client, runContext, "/accounts", queryParams, ACCOUNT_PAGE_TYPE_REF);
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
                    paginate(runContext, rPageCap, fetcher, account -> {
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
                    paginate(runContext, rPageCap, fetcher, account -> {
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
                    File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                    var storedCount = new AtomicInteger(0);
                    try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                        paginate(runContext, rPageCap, fetcher, account -> {
                            try {
                                FileSerde.write(output, JacksonMapper.toMap(account));
                                storedCount.incrementAndGet();
                                return true;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed writing account record to ION storage", e);
                            }
                        });
                    }
                    URI uri = runContext.storage().putFile(tempFile);
                    yield Output.builder()
                        .size(storedCount.get())
                        .uri(uri)
                        .build();
                }
                case NONE -> {
                    var count = paginate(runContext, rPageCap, fetcher, account -> true);
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
            title = "The total number of accounts returned.",
            description = "For FETCH this matches the size of rows, for FETCH_ONE 0 or 1, for STORE the count of stored records, and 0 for NONE."
        )
        private final Integer size;

        @Schema(
            title = "The accounts fetched.",
            description = "List of retrieved accounts. Only populated when fetchType is FETCH."
        )
        private final java.util.List<Account> rows;

        @Schema(
            title = "The first account fetched.",
            description = "The first account retrieved. Only populated when fetchType is FETCH_ONE."
        )
        private final Account row;

        @Schema(
            title = "URI of stored results.",
            description = "The URI of the file containing ION-formatted records in internal storage. Only populated when fetchType is STORE."
        )
        private final URI uri;
    }
}
