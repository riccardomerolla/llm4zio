      *----------------------------------------------------------------
      *  XFERREC - MERIDIAN SAVINGS BANK
      *  DAILY TRANSFER REQUEST RECORD (XFERIN) - LRECL 80 FB
      *  BUILT BY BRANCH / ATM / TELEBANK FRONT ENDS, MERGED AND
      *  SORTED BY JOB XFERDLY STEP010 BEFORE POSTING.
      *  MAINT: 1988-06 R.HALLORAN  ORIGINAL
      *----------------------------------------------------------------
       01  XFER-REC.
           05  XFER-SEQ-NO             PIC 9(07).
           05  XFER-SRC-ACCT           PIC X(10).
           05  XFER-TGT-ACCT           PIC X(10).
           05  XFER-AMOUNT             PIC S9(09)V99.
           05  XFER-CHANNEL            PIC X(03).
               88  XFER-CHAN-BRANCH                VALUE 'BRN'.
               88  XFER-CHAN-ATM                   VALUE 'ATM'.
               88  XFER-CHAN-TELE                  VALUE 'TEL'.
           05  XFER-REQ-DATE           PIC X(08).
           05  XFER-MEMO               PIC X(25).
           05  FILLER                  PIC X(06).
