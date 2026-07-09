# Meridian Savings Bank — HomeBank II web frontend (synthetic fixture)

Circa-2005 J2EE webapp (Servlet 2.4 / JSP 2.0, scriptlets, no framework) that
fronts the mainframe estate in `../cobol` and `../jcl`.
Screens: `accounts.jsp` (account list), `transfer.jsp` (transfer form),
`confirm.jsp` (large-transfer confirmation).
Servlets: `AccountServlet` (`/accounts`), `TransferServlet` (`/transfer`,
`/transfer/confirm`). Reads DB2 `ACCOUNT`; writes only `XFER_REQUEST` — actual
posting stays with the nightly `XFERDLY` batch. `login.jsp` not retained.
