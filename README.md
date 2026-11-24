# Kudi9ja — backend

The server behind the Kudi9ja mobile app.

**Quadrilateral Technologies Limited** (RC 1657731), Lagos, Nigeria. Kudi9ja is
its product — every contract, receipt and legal document names the company, not
the product.

Spring Boot 3.3 · Java 21 · PostgreSQL · Flyway

---

## The rules this codebase is built around

These are invariants. If an implementation choice breaks one of them, the choice
is wrong.

1. **Kudi9ja issues no account numbers.** What a customer has is a *customer
   reference* (`K9-A1B2C3`) for matching payments. It is not payable into. Money
   leaves the wallet to a bank account the customer already holds, in their own
   name.
2. **No money enters a wallet without an admin confirming it** against the bank
   statement. No card, no USSD, no instant credit — bank transfer only, claimed
   in the app with a receipt.
3. **Every pay-in carries its own unique reference.** Not one per customer: two
   transfers of the same amount on the same day are otherwise indistinguishable
   on a statement.
4. **Withdrawals debit at request, not at approval**, so the same money cannot be
   spent twice while it is under review. Declining refunds in full.
5. **A running plan or loan keeps the terms it was opened on.** Rate changes
   never rewrite history.
6. **Fixed Savings cannot be broken.** The return is paid upfront precisely
   because the principal stays put.
7. **Interest never compounds**, anywhere. Savings and loan interest are both
   flat, computed once.
8. **Every admin action is written to an append-only audit log**, with actor,
   category, timestamp and a human-readable before → after.
9. **An admin cannot revoke their own access.** Self-lockout is impossible by
   construction.
10. **Passcodes, PINs and passwords are never stored or transmitted in the
    clear**, and never returned by any endpoint.
11. **Nothing is given away.** No sign-up bonus, no free credit. Every naira in a
    wallet was paid in, earned on a plan, or borrowed.
12. **The app claims no licence it does not hold.** No "licensed lender", no "CBN
    compliant", no "NDIC insured" — and a test fails the build if one reappears.

## Running it locally

Java 21 and Maven. No database needed for the `dev` profile — it runs on
in-memory H2 with the schema generated, a mailer that logs instead of sending,
and a sandbox identity verifier.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Then:

- API docs — <http://localhost:8080/swagger-ui.html>
- Health — <http://localhost:8080/actuator/health>

```bash
mvn test          # 99 tests
```

### The sandbox verifier reads the final digit as an instruction

Useful to know before test data looks arbitrary. For a BVN, an NIN or an account
number:

| Ends in | Result |
|---|---|
| `0` | not found |
| `9` | resolves to a *different* person — a name mismatch |
| anything else | passes |

One-time codes are stored hashed and never logged. Tests read them through
`CapturingMailer`, which is the only seam.

## Configuration

Everything below is empty by default, and the application is deliberate about
what that means.

| Variable | Effect when unset |
|---|---|
| `KUDI9JA_PEPPER` | **Required.** The application will not start. |
| `KUDI9JA_JWT_SECRET` | **Required.** The application will not start. |
| `KYC_PROVIDER` | **The application will not start.** A simulated BVN check that always passes is worse than no check, because it looks like one. |
| `DOJAH_APP_ID`, `DOJAH_SECRET_KEY`, `DOJAH_BASE_URL` | Needed only with `KYC_PROVIDER=dojah` (BVN checked with Dojah); without the keys the application will not start. |
| `KUDI9JA_OWNER_EMAIL` | No owner is ever created; nobody can reach the admin panel. |
| `PUSH_PROVIDER` | No push. Notifications still appear when the app is opened. |
| `MAIL_ENABLED` | Mail is logged, not sent. |
| `DATABASE_URL` | Falls back to a local PostgreSQL. |

Product economics — rates, limits, fees, switches — are **not** configuration.
They live in a versioned platform-settings document, editable from the admin
panel, diffed and written to the audit log on every change.

## Layout

```
common/        errors, idempotency, money and date helpers
config/        security, scheduling, OpenAPI, properties
domain/        the product, one package per area
  admin/         panel access, team, customers, overview
  compliance/    NDPA data export and account closure
  kyc/           one-time codes and identity checks
  legal/         versioned documents and acceptances
  loan/          pricing, disbursement, repayment, credit score
  notification/  the feed, devices, push preferences
  payin/         claims and unmatched credits
  payout/        withdrawals
  savings/       fixed and target plans
  settings/      the platform settings document
  thrift/        ajo / esusu / adashe circles
  user/          sign-up, auth, profile
  wallet/        the ledger
finance/       every money formula, in one place
integration/   bank, email, kyc, push, storage — all behind interfaces
jobs/          the scheduled sweeps
security/      tokens, hashing, the auth filter
web/           controllers and DTOs
```

`Finance` holds every formula. If a number is computed anywhere else, that is a
bug.

## Tests

```
FinanceTest                  the published rate tables, and the two invariants
                             the loan card must satisfy
CustomerJourneyTest          sign-up to borrowing, over real HTTP
DataRightsTest               export, closure, and erasure after retention
PushNotificationTest         what a locked phone may say, and what it may not
NoUnearnedRegulatoryClaims   fails the build if a licence claim reappears
```

## What is not built yet

- The real identity provider, the Resend mailer and its email templates, and
  object storage for receipts — all three have interfaces waiting for them
- Human review of a declined loan, and complaints tracking
- The migration has not been run against a real PostgreSQL

---

© Quadrilateral Technologies Limited. All rights reserved.
