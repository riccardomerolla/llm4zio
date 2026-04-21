workspace "bankmod-sample" "Bank modernization sample graph" {
    model {
        accountsApi = softwareSystem "accounts-api" {
            tags "Tier1"
        }
        auditLog = softwareSystem "audit-log" {
            tags "Tier3"
        }
        customerProfile = softwareSystem "customer-profile" {
            tags "Tier2"
        }
        fraudScorer = softwareSystem "fraud-scorer" {
            tags "Tier2"
        }
        ledgerCore = softwareSystem "ledger-core" {
            tags "Tier1"
        }
        notificationBus = softwareSystem "notification-bus" {
            tags "Tier3"
        }
        paymentsEngine = softwareSystem "payments-engine" {
            tags "Tier1"
        }
        statementService = softwareSystem "statement-service" {
            tags "Tier2"
        }

        accountsApi -> customerProfile "gRPC"
        accountsApi -> ledgerCore "REST"
        customerProfile -> auditLog "Event:profile.updated"
        fraudScorer -> auditLog "Event:fraud.scored"
        ledgerCore -> statementService "Event:ledger.posted"
        paymentsEngine -> fraudScorer "gRPC"
        paymentsEngine -> ledgerCore "REST"
        statementService -> notificationBus "Event:statements.ready"
    }

    views {
        systemLandscape "landscape" {
            include *
            autolayout lr
        }
    }
}