      *----------------------------------------------------------------
      *  LEDGREC - MERIDIAN SAVINGS BANK
      *  LEDGER POSTING ROW / DB2 LEDGER TABLE HOST STRUCTURE
      *  ONE ROW PER POSTED MOVEMENT.  SEE ACCTXFR AND INTCALC FOR
      *  TXN TYPE USAGE.
      *  MAINT: 1988-06 R.HALLORAN  ORIGINAL
      *         1994-11 P.OKONKWO   ADD OD FEE TYPE
      *----------------------------------------------------------------
       01  LEDG-ROW.
           05  LEDG-ACCT-ID            PIC X(10).
           05  LEDG-POST-DATE          PIC X(10).
           05  LEDG-TXN-TYPE           PIC X(02).
               88  LEDG-TXN-DEBIT                  VALUE 'DR'.
               88  LEDG-TXN-CREDIT                 VALUE 'CR'.
               88  LEDG-TXN-FEE                    VALUE 'FE'.
               88  LEDG-TXN-OD-FEE                 VALUE 'OD'.
               88  LEDG-TXN-INTEREST               VALUE 'IN'.
           05  LEDG-AMOUNT             PIC S9(11)V99 COMP-3.
           05  LEDG-XFER-SEQ           PIC 9(07).
           05  LEDG-DESC               PIC X(30).
