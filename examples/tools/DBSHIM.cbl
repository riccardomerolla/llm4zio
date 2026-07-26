      *----------------------------------------------------------------
      *  DBSHIM - GNUCOBOL STAND-INS FOR THE DB2 CALLS IN THE MERIDIAN
      *  FIXTURE.  cobol-capture.py REWRITES EACH EXEC SQL BLOCK INTO A
      *  CALL TO ONE OF THESE SUBPROGRAMS SO THE 1988 BATCH RUNS AS-IS
      *  ON A LAPTOP:
      *    DBSEL - SELECT FROM ACCOUNT BY ACCT_ID (SQLCODE 0/+100)
      *    DBUPD - UPDATE ACCOUNT ('S' SOURCE / 'T' TARGET COLUMNS)
      *    DBLED - INSERT INTO LEDGER
      *    DBAUD - INSERT INTO XFER_AUDIT
      *  THE ACCOUNT TABLE LOADS FROM acctdb.dat (PIPE-SEPARATED) ON
      *  FIRST SELECT; EVERY MUTATION APPENDS ONE LINE TO dbout.dat -
      *  THE OBSERVATION LOG THE CAPTURE TOOL PARSES INTO VECTORS.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DBSEL.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT DB-FILE ASSIGN TO 'acctdb.dat'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-DB-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  DB-FILE.
       01  DB-LINE                 PIC X(160).
       WORKING-STORAGE SECTION.
       01  WS-DB-STATUS            PIC X(02) VALUE '00'.
       01  WS-EOF                  PIC X     VALUE 'N'.
       01  WS-I                    PIC 9(03) VALUE 0.
       01  WS-FIELDS.
           05  WS-F1               PIC X(10).
           05  WS-F2               PIC X(08).
           05  WS-F3               PIC X(01).
           05  WS-F4               PIC X(15).
           05  WS-F5               PIC X(01).
           05  WS-F6               PIC X(15).
           05  WS-F7               PIC X(04).
       01  EXT-DB-LOADED EXTERNAL  PIC X.
       01  EXT-ACCT-CNT  EXTERNAL  PIC 9(03).
       01  EXT-ACCT-TAB  EXTERNAL.
           05  EXT-ACCT OCCURS 50 TIMES.
               10  EA-ID           PIC X(10).
               10  EA-CUST         PIC X(08).
               10  EA-STATUS       PIC X(01).
               10  EA-BALANCE      PIC S9(11)V99.
               10  EA-OD           PIC X(01).
               10  EA-ACCUM        PIC S9(09)V99.
               10  EA-BRANCH       PIC X(04).
       LINKAGE SECTION.
       01  LK-SQLCA.
           05  LK-SQLCODE          PIC S9(09) COMP-5.
       01  LK-ACCT.
           05  LK-ID               PIC X(10).
           05  LK-CUST-ID          PIC X(08).
           05  LK-STATUS           PIC X(01).
           05  LK-BALANCE          PIC S9(11)V99 COMP-3.
           05  LK-OD-FLAG          PIC X(01).
           05  LK-ACCUM            PIC S9(09)V99 COMP-3.
           05  LK-BRANCH-CODE      PIC X(04).
           05  LK-OPEN-DATE        PIC X(10).
           05  LK-LAST-ACTIVITY    PIC X(10).
           05  FILLER              PIC X(12).
       PROCEDURE DIVISION USING LK-SQLCA LK-ACCT.
           IF EXT-DB-LOADED NOT = 'Y'
               PERFORM LOAD-DB
           END-IF
           MOVE +100 TO LK-SQLCODE
           PERFORM VARYING WS-I FROM 1 BY 1
                   UNTIL WS-I > EXT-ACCT-CNT
               IF EA-ID (WS-I) = LK-ID
                   MOVE EA-CUST (WS-I)    TO LK-CUST-ID
                   MOVE EA-STATUS (WS-I)  TO LK-STATUS
                   MOVE EA-BALANCE (WS-I) TO LK-BALANCE
                   MOVE EA-OD (WS-I)      TO LK-OD-FLAG
                   MOVE EA-ACCUM (WS-I)   TO LK-ACCUM
                   MOVE EA-BRANCH (WS-I)  TO LK-BRANCH-CODE
                   MOVE 0 TO LK-SQLCODE
                   EXIT PERFORM
               END-IF
           END-PERFORM
           GOBACK.
       LOAD-DB.
           OPEN INPUT DB-FILE
           MOVE 0 TO EXT-ACCT-CNT
           MOVE 'N' TO WS-EOF
           PERFORM UNTIL WS-EOF = 'Y'
               READ DB-FILE
                   AT END
                       MOVE 'Y' TO WS-EOF
                   NOT AT END
                       ADD 1 TO EXT-ACCT-CNT
                       MOVE SPACES TO WS-FIELDS
                       UNSTRING DB-LINE DELIMITED BY '|'
                           INTO WS-F1 WS-F2 WS-F3 WS-F4 WS-F5
                                WS-F6 WS-F7
                       END-UNSTRING
                       MOVE WS-F1 TO EA-ID (EXT-ACCT-CNT)
                       MOVE WS-F2 TO EA-CUST (EXT-ACCT-CNT)
                       MOVE WS-F3 TO EA-STATUS (EXT-ACCT-CNT)
                       COMPUTE EA-BALANCE (EXT-ACCT-CNT) =
                           FUNCTION NUMVAL(WS-F4)
                       MOVE WS-F5 TO EA-OD (EXT-ACCT-CNT)
                       COMPUTE EA-ACCUM (EXT-ACCT-CNT) =
                           FUNCTION NUMVAL(WS-F6)
                       MOVE WS-F7 TO EA-BRANCH (EXT-ACCT-CNT)
               END-READ
           END-PERFORM
           CLOSE DB-FILE
           MOVE 'Y' TO EXT-DB-LOADED.
       END PROGRAM DBSEL.

       IDENTIFICATION DIVISION.
       PROGRAM-ID. DBUPD.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT OPTIONAL OBS-FILE ASSIGN TO 'dbout.dat'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-OBS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  OBS-FILE.
       01  OBS-LINE                PIC X(240).
       WORKING-STORAGE SECTION.
       01  WS-OBS-STATUS           PIC X(02) VALUE '00'.
       01  WS-I                    PIC 9(03) VALUE 0.
       01  WS-BAL-ED               PIC -(11)9.99.
       01  WS-ACC-ED               PIC -(9)9.99.
       01  EXT-DB-LOADED EXTERNAL  PIC X.
       01  EXT-ACCT-CNT  EXTERNAL  PIC 9(03).
       01  EXT-ACCT-TAB  EXTERNAL.
           05  EXT-ACCT OCCURS 50 TIMES.
               10  EA-ID           PIC X(10).
               10  EA-CUST         PIC X(08).
               10  EA-STATUS       PIC X(01).
               10  EA-BALANCE      PIC S9(11)V99.
               10  EA-OD           PIC X(01).
               10  EA-ACCUM        PIC S9(09)V99.
               10  EA-BRANCH       PIC X(04).
       LINKAGE SECTION.
       01  LK-SQLCA.
           05  LK-SQLCODE          PIC S9(09) COMP-5.
       01  LK-ACCT.
           05  LK-ID               PIC X(10).
           05  LK-CUST-ID          PIC X(08).
           05  LK-STATUS           PIC X(01).
           05  LK-BALANCE          PIC S9(11)V99 COMP-3.
           05  LK-OD-FLAG          PIC X(01).
           05  LK-ACCUM            PIC S9(09)V99 COMP-3.
           05  LK-BRANCH-CODE      PIC X(04).
           05  LK-OPEN-DATE        PIC X(10).
           05  LK-LAST-ACTIVITY    PIC X(10).
           05  FILLER              PIC X(12).
       01  LK-DATE                 PIC X(10).
       01  LK-KIND                 PIC X(01).
       PROCEDURE DIVISION USING LK-SQLCA LK-ACCT LK-DATE LK-KIND.
           MOVE 0 TO LK-SQLCODE
           MOVE LK-BALANCE TO WS-BAL-ED
           MOVE LK-ACCUM   TO WS-ACC-ED
           OPEN EXTEND OBS-FILE
           MOVE SPACES TO OBS-LINE
           IF LK-KIND = 'S'
               STRING 'DB|ACCOUNT|update|ACCT_ID='
                      FUNCTION TRIM(LK-ID)
                      '|BALANCE=' FUNCTION TRIM(WS-BAL-ED)
                      ';DAILY_XFER_ACCUM=' FUNCTION TRIM(WS-ACC-ED)
                      ';LAST_ACTIVITY=' FUNCTION TRIM(LK-DATE)
                      DELIMITED BY SIZE INTO OBS-LINE
               END-STRING
           ELSE
               STRING 'DB|ACCOUNT|update|ACCT_ID='
                      FUNCTION TRIM(LK-ID)
                      '|BALANCE=' FUNCTION TRIM(WS-BAL-ED)
                      ';LAST_ACTIVITY=' FUNCTION TRIM(LK-DATE)
                      DELIMITED BY SIZE INTO OBS-LINE
               END-STRING
           END-IF
           WRITE OBS-LINE
           CLOSE OBS-FILE
           PERFORM VARYING WS-I FROM 1 BY 1
                   UNTIL WS-I > EXT-ACCT-CNT
               IF EA-ID (WS-I) = LK-ID
                   MOVE LK-BALANCE TO EA-BALANCE (WS-I)
                   IF LK-KIND = 'S'
                       MOVE LK-ACCUM TO EA-ACCUM (WS-I)
                   END-IF
                   EXIT PERFORM
               END-IF
           END-PERFORM
           GOBACK.
       END PROGRAM DBUPD.

       IDENTIFICATION DIVISION.
       PROGRAM-ID. DBLED.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT OPTIONAL OBS-FILE ASSIGN TO 'dbout.dat'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-OBS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  OBS-FILE.
       01  OBS-LINE                PIC X(240).
       WORKING-STORAGE SECTION.
       01  WS-OBS-STATUS           PIC X(02) VALUE '00'.
       01  WS-AMT-ED               PIC -(11)9.99.
       01  WS-SEQ-ED               PIC 9(07).
       LINKAGE SECTION.
       01  LK-SQLCA.
           05  LK-SQLCODE          PIC S9(09) COMP-5.
       01  LK-LEDG.
           05  LK-ACCT-ID          PIC X(10).
           05  LK-POST-DATE        PIC X(10).
           05  LK-TXN-TYPE         PIC X(02).
           05  LK-AMOUNT           PIC S9(11)V99 COMP-3.
           05  LK-XFER-SEQ         PIC 9(07).
           05  LK-DESC             PIC X(30).
       PROCEDURE DIVISION USING LK-SQLCA LK-LEDG.
           MOVE 0 TO LK-SQLCODE
           MOVE LK-AMOUNT   TO WS-AMT-ED
           MOVE LK-XFER-SEQ TO WS-SEQ-ED
           OPEN EXTEND OBS-FILE
           MOVE SPACES TO OBS-LINE
           STRING 'DB|LEDGER|insert|ACCT_ID='
                  FUNCTION TRIM(LK-ACCT-ID)
                  ',TXN_TYPE=' FUNCTION TRIM(LK-TXN-TYPE)
                  ',XFER_SEQ=' WS-SEQ-ED
                  '|POST_DATE=' FUNCTION TRIM(LK-POST-DATE)
                  ';AMOUNT=' FUNCTION TRIM(WS-AMT-ED)
                  ';DESCR=' FUNCTION TRIM(LK-DESC)
                  DELIMITED BY SIZE INTO OBS-LINE
           END-STRING
           WRITE OBS-LINE
           CLOSE OBS-FILE
           GOBACK.
       END PROGRAM DBLED.

       IDENTIFICATION DIVISION.
       PROGRAM-ID. DBAUD.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT OPTIONAL OBS-FILE ASSIGN TO 'dbout.dat'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-OBS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  OBS-FILE.
       01  OBS-LINE                PIC X(240).
       WORKING-STORAGE SECTION.
       01  WS-OBS-STATUS           PIC X(02) VALUE '00'.
       01  WS-AMT-ED               PIC -(9)9.99.
       01  WS-FEE-ED               PIC -(5)9.99.
       01  WS-SEQ-ED               PIC 9(07).
       LINKAGE SECTION.
       01  LK-SQLCA.
           05  LK-SQLCODE          PIC S9(09) COMP-5.
       01  LK-SEQ                  PIC 9(07).
       01  LK-SRC                  PIC X(10).
       01  LK-TGT                  PIC X(10).
       01  LK-AMOUNT               PIC S9(09)V99.
       01  LK-FEE                  PIC S9(05)V99 COMP-3.
       01  LK-OD-FLAG              PIC X(01).
       01  LK-CHANNEL              PIC X(03).
       01  LK-DATE                 PIC X(10).
       PROCEDURE DIVISION USING LK-SQLCA LK-SEQ LK-SRC LK-TGT
                                LK-AMOUNT LK-FEE LK-OD-FLAG
                                LK-CHANNEL LK-DATE.
           MOVE 0 TO LK-SQLCODE
           MOVE LK-AMOUNT TO WS-AMT-ED
           MOVE LK-FEE    TO WS-FEE-ED
           MOVE LK-SEQ    TO WS-SEQ-ED
           OPEN EXTEND OBS-FILE
           MOVE SPACES TO OBS-LINE
           STRING 'DB|XFER_AUDIT|insert|XFER_SEQ=' WS-SEQ-ED
                  '|SRC_ACCT=' FUNCTION TRIM(LK-SRC)
                  ';TGT_ACCT=' FUNCTION TRIM(LK-TGT)
                  ';AMOUNT=' FUNCTION TRIM(WS-AMT-ED)
                  ';FEE=' FUNCTION TRIM(WS-FEE-ED)
                  ';OD_FEE_FLAG=' LK-OD-FLAG
                  ';CHANNEL=' FUNCTION TRIM(LK-CHANNEL)
                  ';POST_DATE=' FUNCTION TRIM(LK-DATE)
                  DELIMITED BY SIZE INTO OBS-LINE
           END-STRING
           WRITE OBS-LINE
           CLOSE OBS-FILE
           GOBACK.
       END PROGRAM DBAUD.
