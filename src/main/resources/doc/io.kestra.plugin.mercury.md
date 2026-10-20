# How to use the Mercury plugin

Connect to Mercury, the digital business bank, to manage and inspect bank accounts within Kestra workflows using the [Mercury API](https://docs.mercury.com).

## Authentication

Mercury uses Bearer token authentication. Your `apiToken` is sensitive and should be stored securely as a [secret](https://kestra.io/docs/concepts/secret).

The `baseUrl` parameter is optional and defaults to `https://api.mercury.com/api/v1`. For sandbox testing, set `baseUrl` to `https://api-sandbox.mercury.com/api/v1` either on the task or globally via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

### `io.kestra.plugin.mercury.accounts.List`

List all Mercury bank accounts with automatic cursor-based pagination.

- **Required fields:**
  - `apiToken`
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
  - `fetchType` (default: `FETCH`; options: `FETCH`, `FETCH_ONE`, `STORE`, `NONE`)
  - `limit` (default: `1000`, min: `1`, max: `1000`)
  - `order` (default: `ASC`; options: `ASC`, `DESC`)
  - `pageCap` (default: `100`, min: `1`)

### `io.kestra.plugin.mercury.accounts.Get`

Retrieve full details of a single Mercury bank account by its unique identifier.

- **Required fields:**
  - `apiToken`
  - `accountId` (UUID)
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
