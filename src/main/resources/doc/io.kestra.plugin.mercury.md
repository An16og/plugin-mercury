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

### `io.kestra.plugin.mercury.transactions.List`

Retrieve transactions for a specific Mercury bank account with automatic offset-based pagination and filtering.

- **Required fields:**
  - `apiToken`
  - `accountId` (UUID)
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
  - `fetchType` (default: `FETCH`; options: `FETCH`, `FETCH_ONE`, `STORE`, `NONE`)
  - `limit` (default: `1000`, min: `1`, max: `1000`)
  - `order` (options: `ASC`, `DESC`; defaults to `DESC` in Mercury API)
  - `pageCap` (default: `100`, min: `1`)
  - `start` (date format: `YYYY-MM-DD` or ISO 8601 string; defaults to 30 days prior in Mercury API)
  - `end` (date format: `YYYY-MM-DD` or ISO 8601 string; defaults to current date in Mercury API)
  - `status` (options: `PENDING`, `SENT`, `CANCELLED`, `FAILED`, `REVERSED`, `BLOCKED`)
  - `search`

### `io.kestra.plugin.mercury.transactions.Get`

Retrieve full details for a single Mercury bank transaction by its ID.

- **Required fields:**
  - `apiToken`
  - `accountId` (UUID)
  - `transactionId` (UUID)
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)

### `io.kestra.plugin.mercury.recipients.List`

List payment recipients from Mercury with automatic cursor-based pagination. Output records contain sensitive banking information such as account and routing numbers, IBANs, and presigned attachment URLs.

- **Required fields:**
  - `apiToken`
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
  - `fetchType` (default: `FETCH`; options: `FETCH`, `FETCH_ONE`, `STORE`, `NONE`)
  - `limit` (default: `1000`, min: `1`, max: `1000`)
  - `order` (default: `ASC`; options: `ASC`, `DESC`)
  - `pageCap` (default: `100`, min: `1`)

### `io.kestra.plugin.mercury.cards.List`

Retrieve credit and debit cards across an organization from Mercury with automatic cursor-based pagination and filtering.

- **Required fields:**
  - `apiToken`
- **Optional fields:**
  - `accountId` (UUID)
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
  - `fetchType` (default: `FETCH`; options: `FETCH`, `FETCH_ONE`, `STORE`, `NONE`)
  - `isAgentCard` (boolean)
  - `kind` (options: `DEBIT`, `CREDIT`)
  - `limit` (default: `500`, min: `1`, max: `1000`)
  - `order` (default: `ASC`; options: `ASC`, `DESC`)
  - `pageCap` (default: `100`, min: `1`)
  - `status` (options: `ACTIVE`, `FROZEN`, `CANCELLED`, `INACTIVE`, `EXPIRED`, `SUSPENDED`)
  - `cardType` (options: `VIRTUAL`, `PHYSICAL`)
  - `userId`

### `io.kestra.plugin.mercury.statements.List`

Retrieve statements for a specific Mercury depository bank account with automatic cursor-based pagination and filtering. Output records contain sensitive banking information such as account and routing numbers, EINs, legal addresses, and document download URLs.

- **Required fields:**
  - `accountId` (UUID)
  - `apiToken`
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
  - `end` (date format: `YYYY-MM-DD`; filter on period start date; max 3-month range between start and end)
  - `fetchType` (default: `FETCH`; options: `FETCH`, `FETCH_ONE`, `STORE`, `NONE`)
  - `limit` (default: `1000`, min: `1`, max: `1000`)
  - `order` (default: `DESC`; options: `ASC`, `DESC`)
  - `pageCap` (default: `100`, min: `1`)
  - `start` (date format: `YYYY-MM-DD`; filter on period start date; max 3-month range between start and end)

### `io.kestra.plugin.mercury.statements.Download`

Download an official statement PDF for a Mercury depository bank account directly to internal storage. Returns the internal storage URI, file size in bytes, and sanitized file name. Never logs statement details.

- **Required fields:**
  - `apiToken`
  - `statementId` (UUID)
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)

## Triggers

### `io.kestra.plugin.mercury.transactions.TransactionTrigger`

Periodically polls the Mercury API for new transactions in a specific bank account, deduplicating records across polling intervals using a lookback window and the namespace KV store.

- **Required fields:**
  - `accountId` (UUID)
  - `apiToken`
- **Optional fields:**
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
  - `fetchType` (default: `FETCH`; options: `FETCH`, `FETCH_ONE`, `STORE`, `NONE`)
  - `interval` (default: `PT5M`)
  - `limit` (default: `1000`, min: `1`, max: `1000`)
  - `lookback` (default: `PT24H`)
  - `pageCap` (default: `100`, min: `1`)
  - `search`
  - `status` (options: `PENDING`, `SENT`, `CANCELLED`, `FAILED`, `REVERSED`, `BLOCKED`)

### `io.kestra.plugin.mercury.accounts.BalanceThresholdTrigger`

Periodically polls Mercury bank account balances and triggers an execution when an account drops below a configured minimum threshold.

- **Required fields:**
  - `apiToken`
  - `minBalance` (USD amount)
- **Optional fields:**
  - `accountId` (UUID; when omitted, all active Mercury accounts are evaluated)
  - `balanceType` (default: `AVAILABLE`; options: `AVAILABLE`, `CURRENT`)
  - `baseUrl` (default: `https://api.mercury.com/api/v1`)
  - `interval` (default: `PT5M`)
  - `pageCap` (default: `100`, min: `1`)
