       IDENTIFICATION DIVISION.
       PROGRAM-ID.    INTCALC.
       AUTHOR.        P OKONKWO.
       DATE-WRITTEN.  NOVEMBER 1994.
      *----------------------------------------------------------------
      *  MERIDIAN SAVINGS BANK - MONTHLY INTEREST POSTING
      *
      *  CURSORS THROUGH ACTIVE ACCOUNTS ON THE DB2 ACCOUNT TABLE,
      *  ACCRUES ONE MONTH OF INTEREST AT THE TIERED ANNUAL RATE,
      *  UPDATES THE BALANCE AND WRITES ONE LEDGER ROW PER ACCOUNT.
      *
      *  RUN ON THE LAST BUSINESS DAY OF THE MONTH BY JOB INTMNTH.
      *  COMMITS EVERY 250 ACCOUNTS.
      *
      *  MAINT LOG
      *    1994-11  P.OKONKWO    ORIGINAL
      *    2001-04  S.VANDERBERG RATE TIER REVISION PER MEMO FIN-274
      *----------------------------------------------------------------
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER.  IBM-3090.
       OBJECT-COMPUTER.  IBM-3090.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-SWITCHES.
           05  WS-EOF-SW               PIC X(01)  VALUE 'N'.
               88  WS-EOF                          VALUE 'Y'.

       01  WS-COUNTERS.
           05  WS-ACCT-COUNT           PIC S9(07) COMP-3 VALUE ZERO.
           05  WS-POSTED-COUNT         PIC S9(07) COMP-3 VALUE ZERO.
           05  WS-SKIPPED-COUNT        PIC S9(07) COMP-3 VALUE ZERO.
           05  WS-COMMIT-COUNT         PIC S9(04) COMP   VALUE ZERO.
           05  WS-INT-TOTAL            PIC S9(11)V99 COMP-3
                                                  VALUE ZERO.

      *  ANNUAL RATE TIERS PER MEMO FIN-274 (2001)
       01  WS-CONSTANTS.
           05  K-TIER1-CEILING         PIC S9(07)V99 COMP-3
                                                  VALUE +1000.00.
           05  K-TIER2-CEILING         PIC S9(07)V99 COMP-3
                                                  VALUE +10000.00.
           05  K-TIER1-RATE            PIC SV9(04)   COMP-3
                                                  VALUE +.0050.
           05  K-TIER2-RATE            PIC SV9(04)   COMP-3
                                                  VALUE +.0125.
           05  K-TIER3-RATE            PIC SV9(04)   COMP-3
                                                  VALUE +.0200.
           05  K-MONTHS-PER-YEAR       PIC S9(02) COMP VALUE +12.
           05  K-COMMIT-FREQ           PIC S9(04) COMP VALUE +250.

       01  WS-WORK-AREAS.
           05  WS-ANNUAL-RATE          PIC SV9(04)   COMP-3
                                                  VALUE ZERO.
           05  WS-INT-AMOUNT           PIC S9(09)V99 COMP-3
                                                  VALUE ZERO.
           05  WS-SQLCODE-DISP         PIC -(09)9.
           05  WS-ABEND-CODE           PIC S9(04) COMP VALUE +3100.

       01  WS-RUN-DATE.
           05  WS-RUN-YYYYMMDD        PIC X(08).
           05  WS-RUN-YYYYMMDD-R REDEFINES WS-RUN-YYYYMMDD.
               10  WS-RUN-YYYY         PIC X(04).
               10  WS-RUN-MM           PIC X(02).
               10  WS-RUN-DD           PIC X(02).
       01  WS-POST-DATE                PIC X(10).

      *  ACCOUNT IMAGE (DB2 HOST STRUCTURE)
       COPY ACCTREC.

      *  LEDGER POSTING ROW
       COPY LEDGREC.

           EXEC SQL
               INCLUDE SQLCA
           END-EXEC.

           EXEC SQL
               DECLARE ACT-CSR CURSOR FOR
               SELECT ACCT_ID, CUST_ID, BALANCE
                 FROM ACCOUNT
                WHERE STATUS = 'A'
                ORDER BY ACCT_ID
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-INITIALIZE
           PERFORM 2000-PROCESS-ACCT
               UNTIL WS-EOF
           PERFORM 3000-FINALIZE
           GOBACK.

       1000-INITIALIZE.
           ACCEPT WS-RUN-YYYYMMDD FROM DATE YYYYMMDD
           STRING WS-RUN-YYYY '-' WS-RUN-MM '-' WS-RUN-DD
               DELIMITED BY SIZE
               INTO WS-POST-DATE
           END-STRING
           EXEC SQL
               OPEN ACT-CSR
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF
           PERFORM 2010-FETCH-ACCT.

       2000-PROCESS-ACCT.
      *    NO INTEREST ACCRUES ON ZERO OR OVERDRAWN BALANCES
           IF ACCT-BALANCE > ZERO
               PERFORM 2100-CALC-INTEREST
               PERFORM 2200-POST-INTEREST
           ELSE
               ADD 1 TO WS-SKIPPED-COUNT
           END-IF
           PERFORM 2010-FETCH-ACCT.

       2010-FETCH-ACCT.
           EXEC SQL
               FETCH ACT-CSR
                INTO :ACCT-ID, :ACCT-CUST-ID, :ACCT-BALANCE
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE = 0
                   ADD 1 TO WS-ACCT-COUNT
               WHEN SQLCODE = +100
                   SET WS-EOF TO TRUE
               WHEN OTHER
                   PERFORM 9000-DB-ERROR
           END-EVALUATE.

      *----------------------------------------------------------------
      *  TIERED ANNUAL RATES, APPLIED MONTHLY (ANNUAL / 12).
      *  ROUNDED IS HALF-UP TO THE CENT.
      *----------------------------------------------------------------
       2100-CALC-INTEREST.
           EVALUATE TRUE
               WHEN ACCT-BALANCE < K-TIER1-CEILING
                   MOVE K-TIER1-RATE TO WS-ANNUAL-RATE
               WHEN ACCT-BALANCE < K-TIER2-CEILING
                   MOVE K-TIER2-RATE TO WS-ANNUAL-RATE
               WHEN OTHER
                   MOVE K-TIER3-RATE TO WS-ANNUAL-RATE
           END-EVALUATE
           COMPUTE WS-INT-AMOUNT ROUNDED =
               ACCT-BALANCE * WS-ANNUAL-RATE / K-MONTHS-PER-YEAR.

       2200-POST-INTEREST.
           ADD WS-INT-AMOUNT TO ACCT-BALANCE
           EXEC SQL
               UPDATE ACCOUNT
                  SET BALANCE       = :ACCT-BALANCE,
                      LAST_ACTIVITY = :WS-POST-DATE
                WHERE ACCT_ID = :ACCT-ID
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF
           MOVE ACCT-ID           TO LEDG-ACCT-ID
           MOVE WS-POST-DATE      TO LEDG-POST-DATE
           MOVE 'IN'              TO LEDG-TXN-TYPE
           MOVE WS-INT-AMOUNT     TO LEDG-AMOUNT
           MOVE ZERO              TO LEDG-XFER-SEQ
           MOVE 'MONTHLY INTEREST' TO LEDG-DESC
           EXEC SQL
               INSERT INTO LEDGER
                      (ACCT_ID, POST_DATE, TXN_TYPE, AMOUNT,
                       XFER_SEQ, DESCR)
               VALUES (:LEDG-ACCT-ID, :LEDG-POST-DATE,
                       :LEDG-TXN-TYPE, :LEDG-AMOUNT,
                       :LEDG-XFER-SEQ, :LEDG-DESC)
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF
           ADD WS-INT-AMOUNT TO WS-INT-TOTAL
           ADD 1 TO WS-POSTED-COUNT
           ADD 1 TO WS-COMMIT-COUNT
           IF WS-COMMIT-COUNT NOT < K-COMMIT-FREQ
               EXEC SQL
                   COMMIT
               END-EXEC
               IF SQLCODE NOT = 0
                   PERFORM 9000-DB-ERROR
               END-IF
               MOVE ZERO TO WS-COMMIT-COUNT
           END-IF.

       3000-FINALIZE.
           EXEC SQL
               CLOSE ACT-CSR
           END-EXEC
           EXEC SQL
               COMMIT
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF
           DISPLAY 'INTCALC RUN DATE ....... ' WS-POST-DATE
           DISPLAY 'INTCALC ACCOUNTS READ .. ' WS-ACCT-COUNT
           DISPLAY 'INTCALC POSTED ......... ' WS-POSTED-COUNT
           DISPLAY 'INTCALC SKIPPED ........ ' WS-SKIPPED-COUNT
           DISPLAY 'INTCALC INTEREST PAID .. ' WS-INT-TOTAL.

       9000-DB-ERROR.
           MOVE SQLCODE TO WS-SQLCODE-DISP
           DISPLAY 'INTCALC: UNEXPECTED SQLCODE ' WS-SQLCODE-DISP
           DISPLAY 'INTCALC: LAST ACCOUNT ' ACCT-ID
           EXEC SQL
               ROLLBACK
           END-EXEC
           DISPLAY 'INTCALC: ABENDING U3100'
           CALL 'ILBOABN0' USING WS-ABEND-CODE
           MOVE 16 TO RETURN-CODE
           STOP RUN.
