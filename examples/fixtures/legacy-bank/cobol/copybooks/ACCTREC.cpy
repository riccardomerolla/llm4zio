      *----------------------------------------------------------------
      *  ACCTREC - MERIDIAN SAVINGS BANK
      *  ACCOUNT MASTER LAYOUT / DB2 ACCOUNT TABLE HOST STRUCTURE
      *  COPY WITH REPLACING ==ACCT-== BY ==<PFX>-== WHEN TWO
      *  ACCOUNT IMAGES ARE NEEDED IN THE SAME PROGRAM.
      *  MAINT: 1987-03 R.HALLORAN  ORIGINAL
      *         1994-11 P.OKONKWO   ADD OD FLAG + DAILY ACCUM
      *----------------------------------------------------------------
       01  ACCT-REC.
           05  ACCT-ID                 PIC X(10).
           05  ACCT-CUST-ID            PIC X(08).
           05  ACCT-STATUS             PIC X(01).
               88  ACCT-ACTIVE                     VALUE 'A'.
               88  ACCT-FROZEN                     VALUE 'F'.
               88  ACCT-CLOSED                     VALUE 'C'.
               88  ACCT-DORMANT                    VALUE 'D'.
           05  ACCT-BALANCE            PIC S9(11)V99 COMP-3.
           05  ACCT-OD-FLAG            PIC X(01).
               88  ACCT-OD-PROTECTED               VALUE 'Y'.
           05  ACCT-DAILY-XFER-ACCUM   PIC S9(09)V99 COMP-3.
           05  ACCT-BRANCH-CODE        PIC X(04).
           05  ACCT-OPEN-DATE          PIC X(10).
           05  ACCT-LAST-ACTIVITY      PIC X(10).
           05  FILLER                  PIC X(12).
