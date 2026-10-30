# Kestra Mercury Plugin

## What

- Provides plugin components under `io.kestra.plugin.mercury`.
- Includes tasks under `io.kestra.plugin.mercury.accounts` (`List`, `Get`), `io.kestra.plugin.mercury.transactions` (`List`, `Get`), `io.kestra.plugin.mercury.recipients` (`List`), `io.kestra.plugin.mercury.cards` (`List`), and `io.kestra.plugin.mercury.statements` (`List`, `Download`).
- Includes triggers under `io.kestra.plugin.mercury.accounts` (`BalanceThresholdTrigger`) and `io.kestra.plugin.mercury.transactions` (`TransactionTrigger`).

## Why

- What user problem does this solve? Organizations need automated visibility and orchestration over their Mercury business bank accounts, transactions, payment recipients, corporate cards, and cash balances directly from Kestra workflows.
- Why would a team adopt this plugin in a workflow? It allows scheduled balance checks, financial event handling, and automated reporting without custom glue code or manual reconciliation.
- What operational/business outcome does it enable? Reliable, automated access to banking data with robust rate-limit handling, pagination, and secure credential management.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `mercury`
- `mercury.accounts`
- `mercury.cards`
- `mercury.recipients`
- `mercury.statements`
- `mercury.transactions`
- `mercury.model`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.mercury.AbstractMercuryConnection`
- `io.kestra.plugin.mercury.AbstractMercuryList`
- `io.kestra.plugin.mercury.AbstractMercuryTrigger`
- `io.kestra.plugin.mercury.accounts.List`
- `io.kestra.plugin.mercury.accounts.Get`
- `io.kestra.plugin.mercury.accounts.BalanceThresholdTrigger`
- `io.kestra.plugin.mercury.cards.List`
- `io.kestra.plugin.mercury.recipients.List`
- `io.kestra.plugin.mercury.statements.List`
- `io.kestra.plugin.mercury.statements.Download`
- `io.kestra.plugin.mercury.transactions.List`
- `io.kestra.plugin.mercury.transactions.Get`
- `io.kestra.plugin.mercury.transactions.TransactionTrigger`

### Project Structure

```
plugin-mercury/
├── src/main/java/io/kestra/plugin/mercury/
│   ├── AbstractMercuryConnection.java
│   ├── AbstractMercuryList.java
│   ├── AbstractMercuryTrigger.java
│   ├── accounts/
│   │   ├── List.java
│   │   ├── Get.java
│   │   └── BalanceThresholdTrigger.java
│   ├── cards/
│   │   └── List.java
│   ├── recipients/
│   │   └── List.java
│   ├── statements/
│   │   ├── List.java
│   │   └── Download.java
│   ├── transactions/
│   │   ├── List.java
│   │   ├── Get.java
│   │   └── TransactionTrigger.java
│   └── model/            # API response and output models
├── src/test/java/io/kestra/plugin/mercury/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
