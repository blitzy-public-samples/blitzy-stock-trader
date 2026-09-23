-- Relational schema of the modernized cash ledger: accounts, reservations, the append-only ledger and the migration staging and reporting tables.

-- Applied by Spring Boot SQL initialization instead of Flyway or Liquibase because neither is present in
-- any pom.xml of this checkout, and backend/portfolio already hand-applies its DDL
-- (backend/portfolio/createTables.ddl:L15); the mechanism therefore stays inside the mandated framework
-- while the same file remains applicable by hand under a DDL-owning role. Accepted limitation: this is not
-- a versioned migration history, so a later schema change needs a versioned tool or a hand-written ALTER
-- step appended to this file.

-- Every statement here is terminated with a repeated semicolon because Spring's script runner splits on
-- the single default terminator, which would sever the plpgsql function body and the DO block at the end
-- of this file. The other half of that contract is spring.sql.init.separator in the sibling application.yml.

-- One session-level lock serializes concurrent pod start-ups, as the runner applies the whole script over a single connection.
SELECT pg_advisory_lock(724300101);;

CREATE TABLE IF NOT EXISTS cash_account (
    -- NUMERIC(9,2) on every money column is the legacy precision at backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L48.
    owner             VARCHAR(32)  NOT NULL,
    incarnation_id    UUID         NOT NULL,
    currency          VARCHAR(8)   NOT NULL,
    available_balance NUMERIC(9,2) NOT NULL DEFAULT 0,
    reserved_balance  NUMERIC(9,2) NOT NULL DEFAULT 0,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_cash_account PRIMARY KEY (owner),
    CONSTRAINT uq_cash_account_incarnation UNIQUE (incarnation_id),
    CONSTRAINT ck_cash_account_available_nonneg CHECK (available_balance >= 0),
    CONSTRAINT ck_cash_account_reserved_nonneg CHECK (reserved_balance >= 0),
    CONSTRAINT ck_cash_account_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
    -- The owner rule of domain/OwnerNormalizer.PERMITTED_OWNER_REGEX, in the one place no application path can
    -- bypass. It closed QA finding F03: an unconstrained identifier column accepted markup, shell and template
    -- metacharacters and arbitrary unicode as account owners. The expression is the normalizer's own text with
    -- anchors added, and OwnerNormalizerTest reads this file to prove the two have not drifted.
    CONSTRAINT ck_cash_account_owner_identifier CHECK (owner ~ '^[A-Z0-9._-]{1,32}$')
);;

-- Neither this table nor ledger_entry carries a foreign key to cash_account: both retain their rows as the
-- audit record after a retail account deletion, and a foreign key would block that deletion while terminal
-- reservations still exist.
CREATE TABLE IF NOT EXISTS cash_reservation (
    reservation_id  UUID         NOT NULL,
    owner           VARCHAR(32)  NOT NULL,
    incarnation_id  UUID         NOT NULL,
    order_reference VARCHAR(64)  NOT NULL,
    amount          NUMERIC(9,2) NOT NULL,
    settled_amount  NUMERIC(9,2) NULL,
    currency        VARCHAR(8)   NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_cash_reservation PRIMARY KEY (reservation_id),
    -- Load-bearing rather than hygiene: this is the atomic first-writer-wins guard for hold idempotency,
    -- and ReservationService distinguishes a replay from key reuse by the violation it raises.
    CONSTRAINT uq_cash_reservation_incarnation_key UNIQUE (incarnation_id, idempotency_key),
    CONSTRAINT ck_cash_reservation_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_cash_reservation_settled_le_amount CHECK (settled_amount IS NULL OR settled_amount <= amount),
    CONSTRAINT ck_cash_reservation_state CHECK (state IN ('HELD', 'SETTLED', 'RELEASED', 'EXPIRED')),
    -- Constrained here too, and not left to the account's constraint by way of a foreign key: there is
    -- deliberately no such key, because these rows outlive the account they reserved against.
    CONSTRAINT ck_cash_reservation_owner_identifier CHECK (owner ~ '^[A-Z0-9._-]{1,32}$')
);;

CREATE TABLE IF NOT EXISTS ledger_entry (
    entry_id        BIGINT GENERATED ALWAYS AS IDENTITY,
    owner           VARCHAR(32)  NOT NULL,
    incarnation_id  UUID         NOT NULL,
    event_type      VARCHAR(24)  NOT NULL,
    amount          NUMERIC(9,2) NOT NULL,
    currency        VARCHAR(8)   NOT NULL,
    available_after NUMERIC(9,2) NOT NULL,
    reserved_after  NUMERIC(9,2) NOT NULL,
    reservation_id  UUID         NULL,
    order_reference VARCHAR(64)  NULL,
    source          VARCHAR(16)  NOT NULL,
    run_id          UUID         NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ledger_entry PRIMARY KEY (entry_id),
    CONSTRAINT ck_ledger_entry_amount_nonneg CHECK (amount >= 0),
    -- The audit trail carries the same identifier rule as the account, because a row here is append-only: an
    -- owner that reached this column could never be corrected, only annotated by a later row.
    CONSTRAINT ck_ledger_entry_owner_identifier CHECK (owner ~ '^[A-Z0-9._-]{1,32}$')
);;

CREATE TABLE IF NOT EXISTS migration_run (
    run_id                  UUID         NOT NULL,
    batch_id                UUID         NOT NULL,
    mode                    VARCHAR(16)  NOT NULL,
    source_path             VARCHAR(512) NOT NULL,
    legacy_record_count     INT          NOT NULL DEFAULT 0,
    migrated_record_count   INT          NOT NULL DEFAULT 0,
    variance_count          INT          NOT NULL DEFAULT 0,
    status                  VARCHAR(16)  NOT NULL,
    characterization_status VARCHAR(8)   NOT NULL,
    started_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at             TIMESTAMPTZ  NULL,
    CONSTRAINT pk_migration_run PRIMARY KEY (run_id)
);;

CREATE TABLE IF NOT EXISTS migration_reconciliation (
    reconciliation_id BIGINT GENERATED ALWAYS AS IDENTITY,
    run_id            UUID          NOT NULL,
    -- Deliberately carries no owner CHECK, unlike the three tables above: a finding has to be able to NAME the
    -- legacy owner it refused, character for character, and this column also holds a rate key on a RATE_SOURCE row.
    owner             VARCHAR(32)   NOT NULL,
    variance_kind     VARCHAR(24)   NOT NULL,
    legacy_value      VARCHAR(64)   NULL,
    migrated_value    VARCHAR(64)   NULL,
    legacy_balance    NUMERIC(9,2)  NULL,
    migrated_balance  NUMERIC(9,2)  NULL,
    -- Ten digits: a signed difference of two NUMERIC(9,2) values needs one more than either operand.
    variance          NUMERIC(10,2) NULL,
    status            VARCHAR(20)   NOT NULL,
    recorded_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_migration_reconciliation PRIMARY KEY (reconciliation_id),
    CONSTRAINT fk_migration_reconciliation_run FOREIGN KEY (run_id) REFERENCES migration_run (run_id)
);;

-- The primary key carries the raw cased name because WS-VR-NAME is the caller's name in the caller's
-- casing (backend/cash-account-cobol/COBOL/CASH00.cbl:L111, from WS-NAME PIC X(15) at L55), so 'John' and
-- 'JOHN' with one stamp are two distinct, valid 29-byte KSDS keys (CASH00.cbl:L47-L50 and
-- backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L14 KEYS(29 0)). owner_key is the uppercased join key.
CREATE TABLE IF NOT EXISTS legacy_history (
    run_id       UUID         NOT NULL,
    name         VARCHAR(15)  NOT NULL,
    -- Unconstrained for the same reason as the raw name beside it: staging a legacy export is lossless, and a
    -- history name outside the target's identifier rule simply joins no account rather than being refused.
    owner_key    VARCHAR(32)  NOT NULL,
    event_date   CHAR(8)      NOT NULL,
    event_time   CHAR(6)      NOT NULL,
    request_code CHAR(1)      NOT NULL,
    balance      NUMERIC(9,2) NULL,
    currency     VARCHAR(8)   NULL,
    retcode      VARCHAR(10)  NULL,
    event_at     TIMESTAMPTZ  NULL,
    CONSTRAINT pk_legacy_history PRIMARY KEY (run_id, name, event_date, event_time)
);;

CREATE TABLE IF NOT EXISTS legacy_rate_table (
    run_id    UUID         NOT NULL,
    currnkey  VARCHAR(5)   NOT NULL,
    currnbase VARCHAR(5)   NULL,
    amount    NUMERIC(9,2) NULL,
    rates     NUMERIC(3,2) NULL,
    loaddt    DATE         NULL,
    CONSTRAINT pk_legacy_rate_table PRIMARY KEY (run_id, currnkey)
);;

CREATE INDEX IF NOT EXISTS idx_cash_reservation_owner
    ON cash_reservation (owner);;

CREATE INDEX IF NOT EXISTS idx_cash_reservation_state_expires_at
    ON cash_reservation (state, expires_at);;

CREATE INDEX IF NOT EXISTS idx_ledger_entry_owner_recorded_at
    ON ledger_entry (owner, recorded_at DESC, entry_id DESC);;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_entry_migration_load
    ON ledger_entry (run_id, owner) WHERE event_type = 'MIGRATION_LOAD';;

CREATE INDEX IF NOT EXISTS idx_migration_run_batch_id
    ON migration_run (batch_id);;

CREATE INDEX IF NOT EXISTS idx_migration_reconciliation_run_status
    ON migration_reconciliation (run_id, status);;

CREATE INDEX IF NOT EXISTS idx_legacy_history_run_owner_key
    ON legacy_history (run_id, owner_key);;

-- The owner rule reaches a database that already holds these tables, which CREATE TABLE IF NOT EXISTS above
-- cannot do: an existing table is left exactly as it is, constraint and all. Guarded on pg_constraint so a
-- repeated application and a concurrent pod start-up are both no-ops, and NOT VALID by deliberate choice - it
-- enforces every INSERT and UPDATE from here on while leaving rows that predate it to be found and corrected
-- rather than turning an owner some earlier build stored into a pod that cannot start. Validating afterwards is
-- one statement per table, and the runbook's schema-validation step carries it with the query that lists what
-- would fail it: ALTER TABLE <table> VALIDATE CONSTRAINT ck_<table>_owner_identifier.
DO $own$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_cash_account_owner_identifier'
                      AND conrelid = 'cash_account'::regclass) THEN
        ALTER TABLE cash_account
            ADD CONSTRAINT ck_cash_account_owner_identifier
            CHECK (owner ~ '^[A-Z0-9._-]{1,32}$') NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_cash_reservation_owner_identifier'
                      AND conrelid = 'cash_reservation'::regclass) THEN
        ALTER TABLE cash_reservation
            ADD CONSTRAINT ck_cash_reservation_owner_identifier
            CHECK (owner ~ '^[A-Z0-9._-]{1,32}$') NOT VALID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_ledger_entry_owner_identifier'
                      AND conrelid = 'ledger_entry'::regclass) THEN
        ALTER TABLE ledger_entry
            ADD CONSTRAINT ck_ledger_entry_owner_identifier
            CHECK (owner ~ '^[A-Z0-9._-]{1,32}$') NOT VALID;
    END IF;
END
$own$;;

-- The ledger replaces a write-only VSAM HISTORY KSDS whose EXEC CICS IGNORE CONDITION DUPREC
-- (backend/cash-account-cobol/COBOL/CASH00.cbl:L124, the only access being the WRITE at L126-L131)
-- silently discarded a second record for the same owner within one second. LedgerEntryRepository exposes
-- no update or delete method; this guard is what makes that shape enforceable rather than conventional.
CREATE OR REPLACE FUNCTION ledger_entry_reject() RETURNS trigger AS $fn$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only: % is rejected', TG_OP;
END;
$fn$ LANGUAGE plpgsql;;

DO $trg$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger t
          JOIN pg_class c ON c.oid = t.tgrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE t.tgname = 'ledger_entry_immutable'
           AND c.relname = 'ledger_entry'
           AND n.nspname = current_schema()
    ) THEN
        CREATE TRIGGER ledger_entry_immutable
            BEFORE UPDATE OR DELETE ON ledger_entry
            FOR EACH ROW EXECUTE FUNCTION ledger_entry_reject();
    END IF;
    -- PostgreSQL never fires a row-level trigger for TRUNCATE, so the guard above would let the one identity the
    -- chart supplies for DDL and DML alike (AAP 0.6.3) erase the whole ledger in a single statement. Keeping the
    -- append-only promise of AAP 0.7.4 true under that identity therefore takes a statement-level trigger too.
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger t
          JOIN pg_class c ON c.oid = t.tgrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE t.tgname = 'ledger_entry_immutable_truncate'
           AND c.relname = 'ledger_entry'
           AND n.nspname = current_schema()
    ) THEN
        CREATE TRIGGER ledger_entry_immutable_truncate
            BEFORE TRUNCATE ON ledger_entry
            FOR EACH STATEMENT EXECUTE FUNCTION ledger_entry_reject();
    END IF;
END
$trg$;;

SELECT pg_advisory_unlock(724300101);;

-- WHO SHOULD OWN THESE OBJECTS, AND WHO SHOULD CONNECT WITH THEM. Every guard above binds the
-- request path; none of it binds the object OWNER, because PostgreSQL grants DROP, ALTER and TRIGGER
-- by ownership rather than by privilege. Applying this script from the pod therefore makes the
-- identity that handles requests the owner of ledger_entry, and an owner can drop the trigger and
-- then rewrite audit history - verified as a successful DROP TRIGGER plus UPDATE under that identity.
-- A restart re-creates both guards, so the exposure is a window and not a permanent loss.
--
-- The hardened arrangement splits the two identities and needs nothing from this file but that it be
-- applied by the first of them. Substitute the release's own names; run as the DDL-owning role, in
-- the database and schema the service connects to:
--
--   CREATE ROLE <runtime-role> LOGIN PASSWORD '<from the secret manager, never a literal here>';;
--   GRANT CONNECT ON DATABASE <database> TO <runtime-role>;;
--   GRANT USAGE ON SCHEMA <schema> TO <runtime-role>;;
--   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA <schema> TO <runtime-role>;;
--   ALTER DEFAULT PRIVILEGES IN SCHEMA <schema>
--     GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO <runtime-role>;;
--
-- Then start the service as <runtime-role> with SPRING_SQL_INIT_MODE=never (application.yml's
-- spring.sql.init.mode). No REVOKE is needed and none is listed: the role is granted no TRUNCATE and
-- owns nothing, which is what PostgreSQL 12.22 then refuses it - DROP TRIGGER "must be owner of
-- relation ledger_entry", ALTER TABLE ... DISABLE TRIGGER "must be owner of table ledger_entry",
-- DROP FUNCTION "must be owner of function ledger_entry_reject" and TRUNCATE "permission denied for
-- table ledger_entry", all 42501, while UPDATE and DELETE still raise this file's own P0001. Identity
-- columns need no sequence grant: entry_id's sequence is internally owned by its table, so INSERT
-- alone suffices. audit/LedgerImmutabilityIT exercises that role and those refusals; README
-- "Hardened production posture" and runbook Step 0 carry the operator's copy of this, and the second
-- secret key that delivers a second identity to the pod is the chart's to add (AAP 0.11.2).
