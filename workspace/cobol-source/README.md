# COBOL Source Files

Place your legacy COBOL source files in this directory for migration.

## Supported File Types

- `.cbl` - COBOL program files
- `.cpy` - COBOL copybook files
- `.jcl` - JCL (Job Control Language) files

## Directory Structure

You can organize files in subdirectories. The discovery agent will recursively scan this directory.

Example structure:
```
cobol-source/
├── programs/
│   ├── CUSTMGT.cbl
│   ├── INVOICE.cbl
│   └── PAYMENT.cbl
├── copybooks/
│   ├── CUSTOMER.cpy
│   └── ADDRESS.cpy
└── jcl/
    └── BATCH01.jcl
```

## Sample Files

See `samples/` subdirectory for example COBOL programs used in testing.
