--       Copyright 2025 Kyndryl, All Rights Reserved
--
--   Licensed under the Apache License, Version 2.0 (the "License");
--   you may not use this file except in compliance with the License.
--   You may obtain a copy of the License at
--
--       http://www.apache.org/licenses/LICENSE-2.0
--
--   Unless required by applicable law or agreed to in writing, software
--   distributed under the License is distributed on an "AS IS" BASIS,
--   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
--   See the License for the specific language governing permissions and
--   limitations under the License.

-- Relational schema of the modernized cash ledger: accounts, reservations, the append-only ledger and the migration staging and reporting tables.

-- Applied by Spring Boot SQL initialization instead of Flyway or Liquibase because neither is present in
-- any pom.xml of this checkout, and backend/portfolio already hand-applies its DDL
-- (backend/portfolio/createTables.ddl:L16); the mechanism therefore stays inside the mandated framework
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
    CONSTRAINT ck_cash_account_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
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
    CONSTRAINT ck_cash_reservation_state CHECK (state IN ('HELD', 'SETTLED', 'RELEASED', 'EXPIRED'))
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
    CONSTRAINT ck_ledger_entry_amount_nonneg CHECK (amount >= 0)
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
END
$trg$;;

SELECT pg_advisory_unlock(724300101);;
