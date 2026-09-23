package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.sql.SQLException;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.CashAccountApplication;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail.RetailCashAccountService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/** Proves the append-only guard on {@code ledger_entry} survives repeated schema application and is beyond the reach of a DML-only runtime role. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LedgerImmutabilityIT extends PostgresTestSupport {

    private static final String OWNER = "LEDGERIMMUT";

    private static final String UPDATE_ATTEMPT = "UPDATE ledger_entry SET amount = 0";

    private static final String DELETE_ATTEMPT = "DELETE FROM ledger_entry";

    // The statement no row-level trigger can see: PostgreSQL fires only statement-level triggers for TRUNCATE, and
    // the chart's single database identity owns the table, so this is reachable by the service's own credential.
    private static final String TRUNCATE_ATTEMPT = "TRUNCATE ledger_entry";

    // The three statements that do not attack a row but the guard itself. They are attempted ONLY as the DML-only
    // role: as the owning identity they succeed, which is the exposure the hardened posture below closes, and
    // running them here would strip the guard from the container every other *IT shares in this JVM.
    private static final String DROP_TRIGGER_ATTEMPT = "DROP TRIGGER ledger_entry_immutable ON ledger_entry";

    private static final String DISABLE_TRIGGER_ATTEMPT =
            "ALTER TABLE ledger_entry DISABLE TRIGGER ledger_entry_immutable";

    private static final String DROP_FUNCTION_ATTEMPT = "DROP FUNCTION ledger_entry_reject() CASCADE";

    // ledger_entry_reject() in schema/cash-account-schema.sql raises without an ERRCODE, so plpgsql reports its
    // default raise_exception rather than a class this test would otherwise have to guess at.
    private static final String RAISE_EXCEPTION_SQLSTATE = "P0001";

    // PostgreSQL's insufficient_privilege. It is what separates the two postures: the guard-removal statements are
    // refused by this class of error rather than by the trigger, because DROP, ALTER and TRIGGER come with
    // OWNERSHIP and cannot be granted or revoked individually.
    private static final String INSUFFICIENT_PRIVILEGE_SQLSTATE = "42501";

    // The runtime identity of the hardened posture (AAP 0.6.3, README "Hardened production posture", runbook
    // Step 0): SELECT/INSERT/UPDATE/DELETE on the tables, ownership of nothing, and the service started with
    // SPRING_SQL_INIT_MODE=never so the schema is applied by a separate DDL-owning role. The chart supplies one
    // identity today (AAP 0.11.2 open item), so this role is provisioned here to prove what the arrangement buys.
    private static final String DML_ONLY_ROLE = "cash_account_dml_only";

    // Test-only credential for a role that exists inside a disposable container; no deployment reads it.
    private static final String DML_ONLY_PASSWORD = "dml-only-test";

    private static ConfigurableApplicationContext secondaryContext;

    private static JdbcTemplate dmlOnlyJdbc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetailCashAccountService retailCashAccountService;

    @DynamicPropertySource
    static void jwtSignerKey(DynamicPropertyRegistry registry) {
        registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation);
    }

    // Deliberately no @Transactional anywhere in this class: a rolled-back test transaction would leave the seeded
    // row invisible to the second application, and a statement the trigger rejects aborts the transaction it runs
    // in, so sharing one would poison every statement attempted after the first rejection.
    @BeforeEach
    void seedOneLedgerRow() {
        if (ledgerRowsFor(jdbc) == 0) {
            retailCashAccountService.create(OWNER, new BigDecimal("1000.00"), "USD");
        }
        // The UPDATE and DELETE guard is a FOR EACH ROW trigger, so an empty table would let those two attacks pass
        // for the wrong reason, and no attack could then show the rows it failed to destroy are still there.
        assertThat(ledgerRowsFor(jdbc)).isPositive();
    }

    @AfterAll
    static void closeSecondaryContext() {
        if (secondaryContext != null) {
            secondaryContext.close();
            secondaryContext = null;
        }
    }

    // Every identity, because the guard has to hold for both deployment postures: the owning identity the chart
    // supplies today, the same identity after a restart re-applies the script, and the DML-only runtime role the
    // hardened posture connects with.
    @Test
    void updateOnLedgerEntryIsRejectedBeforeAndAfterSchemaReapplication() {
        assertUpdateRejected(jdbc);
        assertUpdateRejected(secondaryJdbc());
        assertUpdateRejected(dmlOnlyJdbc());
    }

    @Test
    void deleteOnLedgerEntryIsRejectedBeforeAndAfterSchemaReapplication() {
        assertDeleteRejected(jdbc);
        assertDeleteRejected(secondaryJdbc());
        assertDeleteRejected(dmlOnlyJdbc());
    }

    // The gap the row-level guard leaves, and the privilege split that closes it. UPDATE and DELETE are refused
    // row by row while TRUNCATE removes every row in one statement the table's owner - the single identity the pod
    // connects with - is entitled to issue; and an owner can equally drop the trigger or the function outright,
    // which no in-database guard can prevent. Under the DML-only role all four are refused before the trigger is
    // even consulted, which is the whole reason the hardened posture exists.
    @Test
    void truncateIsRejectedAndTheDmlOnlyRoleCannotRemoveTheGuard() {
        assertTruncateRejected(jdbc);
        assertTruncateRejected(secondaryJdbc());

        JdbcTemplate runtimeRole = dmlOnlyJdbc();
        assertDeniedByPrivilege(runtimeRole, "must be owner of relation ledger_entry",
                () -> runtimeRole.execute(DROP_TRIGGER_ATTEMPT));
        assertDeniedByPrivilege(runtimeRole, "must be owner of table ledger_entry",
                () -> runtimeRole.execute(DISABLE_TRIGGER_ATTEMPT));
        assertDeniedByPrivilege(runtimeRole, "must be owner of function ledger_entry_reject",
                () -> runtimeRole.execute(DROP_FUNCTION_ATTEMPT));
        // No TRUNCATE privilege was granted, so this one never reaches the statement-level trigger at all.
        assertDeniedByPrivilege(runtimeRole, "permission denied for table ledger_entry",
                () -> runtimeRole.execute(TRUNCATE_ATTEMPT));
    }

    private void assertUpdateRejected(JdbcTemplate target) {
        assertRejected(target, "UPDATE", () -> target.update(UPDATE_ATTEMPT));
    }

    private void assertDeleteRejected(JdbcTemplate target) {
        assertRejected(target, "DELETE", () -> target.update(DELETE_ATTEMPT));
    }

    // TRUNCATE reports no update count, so it is issued through execute rather than update; the assertion that
    // follows is the same one the other two attacks make.
    private void assertTruncateRejected(JdbcTemplate target) {
        assertRejected(target, "TRUNCATE", () -> target.execute(TRUNCATE_ATTEMPT));
    }

    // Raw SQL rather than the repository, because persistence/LedgerEntryRepository exposes only save and two
    // finders and nothing above the database can express this attack. The ledger it protects replaces a VSAM
    // history that nothing read back and that silently discarded a second record for one owner within the same
    // second [backend/cash-account-cobol/COBOL/CASH00.cbl:L123-L131]. The attempt arrives as a callable because
    // the three statements need two different JdbcTemplate entry points for one shared expectation.
    private void assertRejected(JdbcTemplate target, String operation, ThrowingCallable attempt) {
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(attempt)
                .satisfies(failure -> {
                    SQLException raised = rootSqlException(failure);
                    assertThat(raised.getSQLState()).isEqualTo(RAISE_EXCEPTION_SQLSTATE);
                    assertThat(raised.getMessage())
                            .contains("ledger_entry is append-only: " + operation + " is rejected");
                });
        assertThat(ledgerRowsFor(target)).isPositive();
        assertThat(zeroedLedgerRowsFor(target)).isZero();
    }

    // Refused by PostgreSQL rather than by this module's trigger, so the assertion is on the privilege class and
    // on the server's own wording: a message change would mean the refusal came from somewhere else.
    private void assertDeniedByPrivilege(JdbcTemplate target, String expectedMessage, ThrowingCallable attempt) {
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(attempt)
                .satisfies(failure -> {
                    SQLException raised = rootSqlException(failure);
                    assertThat(raised.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE_SQLSTATE);
                    assertThat(raised.getMessage()).contains(expectedMessage);
                });
        assertThat(ledgerRowsFor(target)).isPositive();
        assertThat(zeroedLedgerRowsFor(target)).isZero();
    }

    // A second application start re-applies schema/cash-account-schema.sql to a database that already holds every
    // object, which is the only way to show start-up never removes the guard: PostgreSQL 12 has no
    // CREATE OR REPLACE TRIGGER, so the script's NOT-EXISTS block has to hold rather than a drop and recreate.
    private static synchronized JdbcTemplate secondaryJdbc() {
        if (secondaryContext == null) {
            secondaryContext = new SpringApplicationBuilder(CashAccountApplication.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .profiles("test")
                    // Outside the test context nothing contributes Testcontainers' JdbcConnectionDetails, and an
                    // explicit spring.datasource.url is the single input config/DataSourceGuardConfig returns
                    // verbatim. Passed as command-line arguments, not through properties(), where
                    // application.yml's own empty ${JDBC_ID:}/${JDBC_PASSWORD:} would outrank them.
                    .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                            "--spring.datasource.username=" + POSTGRES.getUsername(),
                            "--spring.datasource.password=" + POSTGRES.getPassword(),
                            "--cashaccount.security.jwt.public-key-location=" + JwtTestTokens.publicKeyLocation());
        }
        return secondaryContext.getBean(JdbcTemplate.class);
    }

    // The hardened posture's runtime identity, built once against the same container: the grants are exactly the
    // four the README and schema/cash-account-schema.sql prescribe, and nothing is revoked because nothing beyond
    // them is held - ownership, which is what carries DROP, ALTER and TRIGGER, stays with the role that applied
    // the script. No sequence grant appears on purpose: ledger_entry.entry_id is GENERATED ALWAYS AS IDENTITY, so
    // its sequence is internally owned by the table and INSERT alone reaches it.
    private static synchronized JdbcTemplate dmlOnlyJdbc() {
        if (dmlOnlyJdbc == null) {
            jdbcForProvisioning().execute("DO $prov$ BEGIN IF NOT EXISTS ("
                    + "SELECT 1 FROM pg_roles WHERE rolname = '" + DML_ONLY_ROLE + "') THEN CREATE ROLE "
                    + DML_ONLY_ROLE + " LOGIN PASSWORD '" + DML_ONLY_PASSWORD + "'; END IF; END $prov$");
            jdbcForProvisioning().execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName()
                    + " TO " + DML_ONLY_ROLE);
            jdbcForProvisioning().execute("GRANT USAGE ON SCHEMA public TO " + DML_ONLY_ROLE);
            jdbcForProvisioning().execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                    + DML_ONLY_ROLE);

            DriverManagerDataSource asRuntimeRole = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    DML_ONLY_ROLE, DML_ONLY_PASSWORD);
            dmlOnlyJdbc = new JdbcTemplate(asRuntimeRole);
        }
        return dmlOnlyJdbc;
    }

    // The provisioning runs as the container's own superuser rather than through the injected JdbcTemplate, which
    // is a static-context accessor away from the instance field the tests use.
    private static JdbcTemplate jdbcForProvisioning() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    private static SQLException rootSqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        throw new AssertionError("No SQLException in the cause chain of " + failure);
    }

    private static long ledgerRowsFor(JdbcTemplate target) {
        return count(target, "SELECT count(*) FROM ledger_entry WHERE owner = ?");
    }

    private static long zeroedLedgerRowsFor(JdbcTemplate target) {
        return count(target, "SELECT count(*) FROM ledger_entry WHERE owner = ? AND amount = 0");
    }

    private static long count(JdbcTemplate target, String sql) {
        Long rows = target.queryForObject(sql, Long.class, OWNER);
        return rows == null ? 0L : rows;
    }
}
