Connect to Mercury, the digital business bank, to manage and inspect bank accounts within Kestra workflows.

## Authentication

Mercury uses Bearer token authentication. Generate an API token from your Mercury dashboard with the required read scopes. The token typically begins with the `secret-token:` prefix.

Store your token in Kestra secrets and access it using `{{ secret('MERCURY_API_TOKEN') }}`.

## Available Tasks

- `io.kestra.plugin.mercury.accounts.List`: List all Mercury bank accounts with automatic cursor-based pagination and flexible fetch types (`FETCH`, `FETCH_ONE`, `STORE`, `NONE`).
- `io.kestra.plugin.mercury.accounts.Get`: Retrieve full details of a specific Mercury bank account by its ID.
