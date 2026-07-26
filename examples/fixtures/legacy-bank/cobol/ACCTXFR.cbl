       IDENTIFICATION DIVISION.
       PROGRAM-ID.    ACCTXFR.
       AUTHOR.        R HALLORAN.
       DATE-WRITTEN.  MARCH 1988.
      *----------------------------------------------------------------
      *  MERIDIAN SAVINGS BANK - DAILY ACCOUNT TRANSFER POSTING
      *
      *  READS THE SORTED DAILY TRANSFER REQUEST FILE (XFERIN),
      *  VALIDATES EACH REQUEST AGAINST THE DB2 ACCOUNT TABLE,
      *  POSTS GOOD TRANSFERS TO LEDGER AND XFER_AUDIT, AND WRITES
      *  FAILED REQUESTS TO THE REJECT FILE WITH A REASON CODE.
      *
      *  RUN NIGHTLY BY JOB XFERDLY STEP020 UNDER IKJEFT01.
      *  COMMITS EVERY 100 POSTED TRANSFERS.  ON ANY UNEXPECTED
      *  SQLCODE THE RUN ROLLS BACK TO LAST COMMIT AND ABENDS U3000.
      *
      *  MAINT LOG
      *    1988-03  R.HALLORAN   ORIGINAL
      *    1994-11  P.OKONKWO    OVERDRAFT PROTECTION + DAILY LIMIT
      *    1999-02  S.VANDERBERG FEE SCHEDULE REWORK PER MEMO FIN-221
      *    2003-07  D.MBEKI      SAME-CUSTOMER FEE WAIVER
      *----------------------------------------------------------------
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER.  IBM-3090.
       OBJECT-COMPUTER.  IBM-3090.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT XFER-FILE      ASSIGN TO XFERIN
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS  IS WS-XFER-STATUS.
           SELECT REJECT-FILE    ASSIGN TO REJECTS
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS  IS WS-REJ-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  XFER-FILE
           RECORDING MODE IS F
           RECORD CONTAINS 80 CHARACTERS
           BLOCK CONTAINS 0 RECORDS.
       COPY XFERREC.

       FD  REJECT-FILE
           RECORDING MODE IS F
           RECORD CONTAINS 120 CHARACTERS
           BLOCK CONTAINS 0 RECORDS.
       01  REJECT-REC.
           05  REJ-ORIG-REC            PIC X(80).
           05  REJ-CODE                PIC X(02).
           05  REJ-REASON              PIC X(30).
           05  FILLER                  PIC X(08).

       WORKING-STORAGE SECTION.
       01  WS-SWITCHES.
           05  WS-EOF-SW               PIC X(01)  VALUE 'N'.
               88  WS-EOF                          VALUE 'Y'.
           05  WS-OD-TRIGGERED         PIC X(01)  VALUE 'N'.
               88  WS-OD-FEE-DUE                   VALUE 'Y'.
           05  WS-XFER-STATUS          PIC X(02)  VALUE '00'.
           05  WS-REJ-STATUS           PIC X(02)  VALUE '00'.

       01  WS-COUNTERS.
           05  WS-READ-COUNT           PIC S9(07) COMP-3 VALUE ZERO.
           05  WS-POSTED-COUNT         PIC S9(07) COMP-3 VALUE ZERO.
           05  WS-REJECT-COUNT         PIC S9(07) COMP-3 VALUE ZERO.
           05  WS-COMMIT-COUNT         PIC S9(04) COMP   VALUE ZERO.
           05  WS-FEE-TOTAL            PIC S9(09)V99 COMP-3
                                                  VALUE ZERO.

       01  WS-CONSTANTS.
           05  K-DAILY-XFER-LIMIT      PIC S9(07)V99 COMP-3
                                                  VALUE +5000.00.
           05  K-FEE-THRESHOLD         PIC S9(07)V99 COMP-3
                                                  VALUE +1000.00.
           05  K-FLAT-FEE              PIC S9(03)V99 COMP-3
                                                  VALUE +2.50.
           05  K-FEE-RATE              PIC SV9(04)   COMP-3
                                                  VALUE +.0025.
           05  K-FEE-CAP               PIC S9(03)V99 COMP-3
                                                  VALUE +20.00.
           05  K-OD-FLOOR              PIC S9(05)V99 COMP-3
                                                  VALUE -500.00.
           05  K-OD-FEE                PIC S9(03)V99 COMP-3
                                                  VALUE +15.00.
           05  K-COMMIT-FREQ           PIC S9(04) COMP VALUE +100.

       01  WS-WORK-AREAS.
           05  WS-XFER-FEE             PIC S9(05)V99 COMP-3
                                                  VALUE ZERO.
           05  WS-NEW-BALANCE          PIC S9(11)V99 COMP-3
                                                  VALUE ZERO.
           05  WS-NEW-ACCUM            PIC S9(09)V99 COMP-3
                                                  VALUE ZERO.
           05  WS-SQLCODE-DISP         PIC -(09)9.
           05  WS-ABEND-CODE           PIC S9(04) COMP VALUE +3000.

       01  WS-RUN-DATE.
           05  WS-RUN-YYYYMMDD        PIC X(08).
           05  WS-RUN-YYYYMMDD-R REDEFINES WS-RUN-YYYYMMDD.
               10  WS-RUN-YYYY         PIC X(04).
               10  WS-RUN-MM           PIC X(02).
               10  WS-RUN-DD           PIC X(02).
       01  WS-POST-DATE                PIC X(10).

       01  WS-REJECT-CODE              PIC X(02)  VALUE SPACES.
           88  RJ-NONE                             VALUE SPACES.
           88  RJ-SRC-NOT-FOUND                    VALUE '10'.
           88  RJ-SRC-FROZEN                       VALUE '11'.
           88  RJ-SRC-CLOSED                       VALUE '12'.
           88  RJ-SRC-NOT-ACTIVE                   VALUE '13'.
           88  RJ-TGT-NOT-FOUND                    VALUE '14'.
           88  RJ-TGT-NOT-ACTIVE                   VALUE '15'.
           88  RJ-BAD-AMOUNT                       VALUE '20'.
           88  RJ-SAME-ACCT                        VALUE '21'.
           88  RJ-DAILY-LIMIT                      VALUE '30'.
           88  RJ-INSUF-FUNDS                      VALUE '40'.
           88  RJ-OD-LIMIT                         VALUE '41'.

      *  SOURCE AND TARGET ACCOUNT IMAGES (DB2 HOST STRUCTURES)
       COPY ACCTREC REPLACING ==ACCT-== BY ==SRC-==.
       COPY ACCTREC REPLACING ==ACCT-== BY ==TGT-==.

      *  LEDGER POSTING ROW
       COPY LEDGREC.

           EXEC SQL
               INCLUDE SQLCA
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-INITIALIZE
           PERFORM 2000-PROCESS-XFER
               UNTIL WS-EOF
           PERFORM 3000-FINALIZE
           GOBACK.

       1000-INITIALIZE.
           ACCEPT WS-RUN-YYYYMMDD FROM DATE YYYYMMDD
           STRING WS-RUN-YYYY '-' WS-RUN-MM '-' WS-RUN-DD
               DELIMITED BY SIZE
               INTO WS-POST-DATE
           END-STRING
           OPEN INPUT  XFER-FILE
           IF WS-XFER-STATUS NOT = '00'
               DISPLAY 'ACCTXFR: OPEN XFERIN FAILED FS=' WS-XFER-STATUS
               PERFORM 9100-ABEND
           END-IF
           OPEN OUTPUT REJECT-FILE
           IF WS-REJ-STATUS NOT = '00'
               DISPLAY 'ACCTXFR: OPEN REJECTS FAILED FS=' WS-REJ-STATUS
               PERFORM 9100-ABEND
           END-IF
           PERFORM 2010-READ-XFER.

       2000-PROCESS-XFER.
           MOVE SPACES TO WS-REJECT-CODE
           MOVE 'N'    TO WS-OD-TRIGGERED
           MOVE ZERO   TO WS-XFER-FEE
           PERFORM 2100-VALIDATE-XFER THRU 2100-EXIT
           IF RJ-NONE
               PERFORM 2400-POST-XFER
           ELSE
               PERFORM 2600-WRITE-REJECT
           END-IF
           PERFORM 2010-READ-XFER.

       2010-READ-XFER.
           READ XFER-FILE
               AT END
                   SET WS-EOF TO TRUE
               NOT AT END
                   ADD 1 TO WS-READ-COUNT
           END-READ.

      *----------------------------------------------------------------
      *  VALIDATION.  FIRST FAILURE WINS - CHECK ORDER IS PART OF
      *  THE CONTRACT WITH THE BRANCH RECONCILEMENT DESK.
      *----------------------------------------------------------------
       2100-VALIDATE-XFER.
           IF XFER-AMOUNT NOT > ZERO
               SET RJ-BAD-AMOUNT TO TRUE
               GO TO 2100-EXIT
           END-IF
           IF XFER-SRC-ACCT = XFER-TGT-ACCT
               SET RJ-SAME-ACCT TO TRUE
               GO TO 2100-EXIT
           END-IF
           PERFORM 2110-FETCH-SOURCE-ACCT
           IF NOT RJ-NONE
               GO TO 2100-EXIT
           END-IF
           PERFORM 2120-FETCH-TARGET-ACCT
           IF NOT RJ-NONE
               GO TO 2100-EXIT
           END-IF
           PERFORM 2200-CHECK-DAILY-LIMIT
           IF NOT RJ-NONE
               GO TO 2100-EXIT
           END-IF
           PERFORM 2250-CALC-FEE
           PERFORM 2300-CHECK-FUNDS.
       2100-EXIT.
           EXIT.

       2110-FETCH-SOURCE-ACCT.
           MOVE XFER-SRC-ACCT TO SRC-ID
           EXEC SQL
               SELECT CUST_ID, STATUS, BALANCE, OD_FLAG,
                      DAILY_XFER_ACCUM, BRANCH_CODE
                 INTO :SRC-CUST-ID, :SRC-STATUS, :SRC-BALANCE,
                      :SRC-OD-FLAG, :SRC-DAILY-XFER-ACCUM,
                      :SRC-BRANCH-CODE
                 FROM ACCOUNT
                WHERE ACCT_ID = :SRC-ID
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE = 0
                   CONTINUE
               WHEN SQLCODE = +100
                   SET RJ-SRC-NOT-FOUND TO TRUE
               WHEN OTHER
                   PERFORM 9000-DB-ERROR
           END-EVALUATE
           IF RJ-NONE
               EVALUATE TRUE
                   WHEN SRC-ACTIVE
                       CONTINUE
                   WHEN SRC-FROZEN
                       SET RJ-SRC-FROZEN TO TRUE
                   WHEN SRC-CLOSED
                       SET RJ-SRC-CLOSED TO TRUE
                   WHEN OTHER
                       SET RJ-SRC-NOT-ACTIVE TO TRUE
               END-EVALUATE
           END-IF.

       2120-FETCH-TARGET-ACCT.
           MOVE XFER-TGT-ACCT TO TGT-ID
           EXEC SQL
               SELECT CUST_ID, STATUS, BALANCE
                 INTO :TGT-CUST-ID, :TGT-STATUS, :TGT-BALANCE
                 FROM ACCOUNT
                WHERE ACCT_ID = :TGT-ID
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE = 0
                   IF NOT TGT-ACTIVE
                       SET RJ-TGT-NOT-ACTIVE TO TRUE
                   END-IF
               WHEN SQLCODE = +100
                   SET RJ-TGT-NOT-FOUND TO TRUE
               WHEN OTHER
                   PERFORM 9000-DB-ERROR
           END-EVALUATE.

      *----------------------------------------------------------------
      *  DAILY LIMIT IS 5000.00 OF PRINCIPAL PER SOURCE ACCOUNT PER
      *  CALENDAR DAY, CUMULATIVE ACROSS THE RUN.  FEES DO NOT COUNT
      *  AGAINST THE LIMIT.  ACCUM IS RESET BY JOB ACCTRST AT EOD.
      *----------------------------------------------------------------
       2200-CHECK-DAILY-LIMIT.
           COMPUTE WS-NEW-ACCUM =
               SRC-DAILY-XFER-ACCUM + XFER-AMOUNT
           IF WS-NEW-ACCUM > K-DAILY-XFER-LIMIT
               SET RJ-DAILY-LIMIT TO TRUE
           END-IF.

      *----------------------------------------------------------------
      *  FEE SCHEDULE PER MEMO FIN-221 (1999):
      *    UNDER 1000.00           FLAT 2.50
      *    1000.00 AND OVER        0.25 PCT, CAPPED AT 20.00
      *  WAIVED WHEN BOTH ACCOUNTS BELONG TO THE SAME CUSTOMER
      *  (MEMO FIN-305, 2003).
      *----------------------------------------------------------------
       2250-CALC-FEE.
           IF SRC-CUST-ID = TGT-CUST-ID
               MOVE ZERO TO WS-XFER-FEE
           ELSE
               IF XFER-AMOUNT < K-FEE-THRESHOLD
                   MOVE K-FLAT-FEE TO WS-XFER-FEE
               ELSE
                   COMPUTE WS-XFER-FEE ROUNDED =
                       XFER-AMOUNT * K-FEE-RATE
                   IF WS-XFER-FEE > K-FEE-CAP
                       MOVE K-FEE-CAP TO WS-XFER-FEE
                   END-IF
               END-IF
           END-IF.

      *----------------------------------------------------------------
      *  FUNDS CHECK.  BALANCE MAY NOT GO NEGATIVE UNLESS THE ACCOUNT
      *  CARRIES OVERDRAFT PROTECTION (OD_FLAG = Y), IN WHICH CASE THE
      *  FLOOR IS -500.00 INCLUDING THE 15.00 OVERDRAFT FEE, WHICH
      *  POSTS AS A SEPARATE LEDGER TRANSACTION.
      *----------------------------------------------------------------
       2300-CHECK-FUNDS.
           COMPUTE WS-NEW-BALANCE =
               SRC-BALANCE - XFER-AMOUNT - WS-XFER-FEE
           IF WS-NEW-BALANCE < ZERO
               IF NOT SRC-OD-PROTECTED
                   SET RJ-INSUF-FUNDS TO TRUE
               ELSE
                   IF WS-NEW-BALANCE - K-OD-FEE < K-OD-FLOOR
                       SET RJ-OD-LIMIT TO TRUE
                   ELSE
                       SET WS-OD-FEE-DUE TO TRUE
                   END-IF
               END-IF
           END-IF.

      *----------------------------------------------------------------
      *  POSTING.  DEBIT SOURCE, CREDIT TARGET, FEE AND OD FEE AS
      *  SEPARATE LEDGER ROWS, ONE AUDIT ROW PER POSTED TRANSFER.
      *----------------------------------------------------------------
       2400-POST-XFER.
           MOVE WS-NEW-BALANCE TO SRC-BALANCE
           IF WS-OD-FEE-DUE
               SUBTRACT K-OD-FEE FROM SRC-BALANCE
           END-IF
           MOVE WS-NEW-ACCUM TO SRC-DAILY-XFER-ACCUM
           ADD XFER-AMOUNT TO TGT-BALANCE
           PERFORM 2410-UPDATE-SOURCE
           PERFORM 2420-UPDATE-TARGET
           PERFORM 2430-POST-LEDGER-ROWS
           PERFORM 2500-WRITE-AUDIT
           ADD WS-XFER-FEE TO WS-FEE-TOTAL
           IF WS-OD-FEE-DUE
               ADD K-OD-FEE TO WS-FEE-TOTAL
           END-IF
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

       2410-UPDATE-SOURCE.
           EXEC SQL
               UPDATE ACCOUNT
                  SET BALANCE          = :SRC-BALANCE,
                      DAILY_XFER_ACCUM = :SRC-DAILY-XFER-ACCUM,
                      LAST_ACTIVITY    = :WS-POST-DATE
                WHERE ACCT_ID = :SRC-ID
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF.

       2420-UPDATE-TARGET.
           EXEC SQL
               UPDATE ACCOUNT
                  SET BALANCE       = :TGT-BALANCE,
                      LAST_ACTIVITY = :WS-POST-DATE
                WHERE ACCT_ID = :TGT-ID
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF.

       2430-POST-LEDGER-ROWS.
      *    DEBIT LEG
           MOVE SRC-ID          TO LEDG-ACCT-ID
           MOVE WS-POST-DATE    TO LEDG-POST-DATE
           MOVE 'DR'            TO LEDG-TXN-TYPE
           MOVE XFER-AMOUNT     TO LEDG-AMOUNT
           MOVE XFER-SEQ-NO     TO LEDG-XFER-SEQ
           MOVE XFER-MEMO       TO LEDG-DESC
           PERFORM 2440-INSERT-LEDGER
      *    CREDIT LEG
           MOVE TGT-ID          TO LEDG-ACCT-ID
           MOVE 'CR'            TO LEDG-TXN-TYPE
           PERFORM 2440-INSERT-LEDGER
      *    TRANSFER FEE, IF ANY
           IF WS-XFER-FEE > ZERO
               MOVE SRC-ID              TO LEDG-ACCT-ID
               MOVE 'FE'                TO LEDG-TXN-TYPE
               MOVE WS-XFER-FEE         TO LEDG-AMOUNT
               MOVE 'TRANSFER FEE'      TO LEDG-DESC
               PERFORM 2440-INSERT-LEDGER
           END-IF
      *    OVERDRAFT FEE, SEPARATE TRANSACTION
           IF WS-OD-FEE-DUE
               MOVE SRC-ID              TO LEDG-ACCT-ID
               MOVE 'OD'                TO LEDG-TXN-TYPE
               MOVE K-OD-FEE            TO LEDG-AMOUNT
               MOVE 'OVERDRAFT FEE'     TO LEDG-DESC
               PERFORM 2440-INSERT-LEDGER
           END-IF.

       2440-INSERT-LEDGER.
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
           END-IF.

       2500-WRITE-AUDIT.
           EXEC SQL
               INSERT INTO XFER_AUDIT
                      (XFER_SEQ, SRC_ACCT, TGT_ACCT, AMOUNT,
                       FEE, OD_FEE_FLAG, CHANNEL, POST_DATE)
               VALUES (:XFER-SEQ-NO, :SRC-ID, :TGT-ID,
                       :XFER-AMOUNT, :WS-XFER-FEE,
                       :WS-OD-TRIGGERED, :XFER-CHANNEL,
                       :WS-POST-DATE)
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF.

       2600-WRITE-REJECT.
           MOVE XFER-REC       TO REJ-ORIG-REC
           MOVE WS-REJECT-CODE TO REJ-CODE
           EVALUATE TRUE
               WHEN RJ-SRC-NOT-FOUND
                   MOVE 'SOURCE ACCOUNT NOT ON FILE'
                     TO REJ-REASON
               WHEN RJ-SRC-FROZEN
                   MOVE 'SOURCE ACCOUNT FROZEN'
                     TO REJ-REASON
               WHEN RJ-SRC-CLOSED
                   MOVE 'SOURCE ACCOUNT CLOSED'
                     TO REJ-REASON
               WHEN RJ-SRC-NOT-ACTIVE
                   MOVE 'SOURCE ACCOUNT NOT ACTIVE'
                     TO REJ-REASON
               WHEN RJ-TGT-NOT-FOUND
                   MOVE 'TARGET ACCOUNT NOT ON FILE'
                     TO REJ-REASON
               WHEN RJ-TGT-NOT-ACTIVE
                   MOVE 'TARGET ACCOUNT NOT ACTIVE'
                     TO REJ-REASON
               WHEN RJ-BAD-AMOUNT
                   MOVE 'AMOUNT NOT POSITIVE'
                     TO REJ-REASON
               WHEN RJ-SAME-ACCT
                   MOVE 'SOURCE EQUALS TARGET'
                     TO REJ-REASON
               WHEN RJ-DAILY-LIMIT
                   MOVE 'DAILY TRANSFER LIMIT EXCEEDED'
                     TO REJ-REASON
               WHEN RJ-INSUF-FUNDS
                   MOVE 'INSUFFICIENT FUNDS'
                     TO REJ-REASON
               WHEN RJ-OD-LIMIT
                   MOVE 'OVERDRAFT LIMIT EXCEEDED'
                     TO REJ-REASON
               WHEN OTHER
                   MOVE 'UNKNOWN REJECT'
                     TO REJ-REASON
           END-EVALUATE
           WRITE REJECT-REC
           ADD 1 TO WS-REJECT-COUNT.

       3000-FINALIZE.
           EXEC SQL
               COMMIT
           END-EXEC
           IF SQLCODE NOT = 0
               PERFORM 9000-DB-ERROR
           END-IF
           CLOSE XFER-FILE
                 REJECT-FILE
           DISPLAY 'ACCTXFR RUN DATE ....... ' WS-POST-DATE
           DISPLAY 'ACCTXFR REQUESTS READ .. ' WS-READ-COUNT
           DISPLAY 'ACCTXFR POSTED ......... ' WS-POSTED-COUNT
           DISPLAY 'ACCTXFR REJECTED ....... ' WS-REJECT-COUNT
           DISPLAY 'ACCTXFR FEES COLLECTED . ' WS-FEE-TOTAL.

       9000-DB-ERROR.
           MOVE SQLCODE TO WS-SQLCODE-DISP
           DISPLAY 'ACCTXFR: UNEXPECTED SQLCODE ' WS-SQLCODE-DISP
           DISPLAY 'ACCTXFR: LAST SEQ PROCESSED ' XFER-SEQ-NO
           EXEC SQL
               ROLLBACK
           END-EXEC
           PERFORM 9100-ABEND.

       9100-ABEND.
           DISPLAY 'ACCTXFR: ABENDING U3000'
           CALL 'ILBOABN0' USING WS-ABEND-CODE
           MOVE 16 TO RETURN-CODE
           STOP RUN.
