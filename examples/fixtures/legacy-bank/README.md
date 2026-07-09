# Meridian Savings Bank — legacy batch estate (synthetic fixture)

Synthetic-but-authentic slice of a fictional mainframe banking estate, used as
the *input* to llm4zio's legacy-modernization example flows. Everything here is
invented: no real bank, customer, or account data. The COBOL is structurally
correct COBOL-85 + EXEC SQL but is not expected to compile on a real mainframe
as-is (no DCLGENs, no precompiler setup are shipped).

The business rules of the estate live **only in the code** — by design. The
demo flows reverse-engineer them; this README stays at inventory level.

## What runs when

| Job | Schedule | Program(s) | Purpose |
|-----|----------|-----------|---------|
| `XFERDLY` | nightly, after channel cutover | DFSORT, `ACCTXFR` | sort + post the day's account-to-account transfer requests |
| `INTMNTH` | last business day of month | `INTCALC` | accrue and post monthly interest (JCL not retained in this extract) |
| `ACCTRST` | end of day | — | resets per-account daily accumulators (not retained in this extract) |

## Source inventory

```
cobol/
  ACCTXFR.cbl            transfer posting batch program (the big one)
  INTCALC.cbl            monthly interest posting
  copybooks/
    ACCTREC.cpy          ACCOUNT master / DB2 host structure (COPY REPLACING-able prefix)
    XFERREC.cpy          transfer request input record (XFERIN, LRECL 80)
    LEDGREC.cpy          LEDGER posting row host structure
jcl/
  XFERDLY.jcl            2-step nightly job: DFSORT validate/sort, then ACCTXFR under IKJEFT01/DB2
```

SQLCA is not shipped — it comes from the DB2 precompiler (`EXEC SQL INCLUDE SQLCA`).

## Data inventory

Files:

- `MERID.XFER.DAILY.MERGED` — merged branch/ATM/telebank transfer requests (input, GDG)
- `MERID.XFER.DAILY.SORTED` — sorted work file between steps
- `MERID.XFER.REJECTS` — rejected requests with reason code (output, GDG, LRECL 120)

DB2 tables (subsystem `DB2P`):

- `ACCOUNT` — account master: id, customer id, status, balance, overdraft flag, daily transfer accumulator, branch, dates
- `LEDGER` — one row per posted movement (typed: debit/credit/fee/overdraft fee/interest)
- `XFER_AUDIT` — one row per posted transfer

Program run summaries go to SYSOUT via `DISPLAY`; abends are U3000 (`ACCTXFR`)
and U3100 (`INTCALC`) with the offending SQLCODE printed first.
