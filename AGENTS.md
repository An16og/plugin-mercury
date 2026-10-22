# Kestra Mercury Plugin

## What

- Provides plugin components under `io.kestra.plugin.mercury`.
- Includes tasks under `io.kestra.plugin.mercury.accounts` (`List`, `Get`) and `io.kestra.plugin.mercury.transactions` (`List`, `Get`).

## Why

- What user problem does this solve? Organizations need automated visibility and orchestration over their Mercury business bank accounts, transactions, and cash balances directly from Kestra workflows.
- Why would a team adopt this plugin in a workflow? It allows scheduled balance checks, financial event handling, and automated reporting without custom glue code or manual reconciliation.
- What operational/business outcome does it enable? Reliable, automated access to banking data with robust rate-limit handling, pagination, and secure credential management.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `mercury`
- `mercury.accounts`
- `mercury.transactions`
- `mercury.model`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.mercury.AbstractMercuryConnection`
- `io.kestra.plugin.mercury.accounts.List`
- `io.kestra.plugin.mercury.accounts.Get`
- `io.kestra.plugin.mercury.transactions.List`
- `io.kestra.plugin.mercury.transactions.Get`

### Project Structure

```
plugin-mercury/
├── src/main/java/io/kestra/plugin/mercury/
│   ├── AbstractMercuryConnection.java
│   ├── accounts/
│   │   ├── List.java
│   │   └── Get.java
│   ├── transactions/
│   │   ├── List.java
│   │   └── Get.java
│   └── model/
│       ├── Account.java
│       ├── AccountPage.java
│       ├── Transaction.java
│       └── TransactionsResponse.java
├── src/test/java/io/kestra/plugin/mercury/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
