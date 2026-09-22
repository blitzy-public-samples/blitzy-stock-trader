package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.CashAccountApplication;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail.RetailCashAccountService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.JwtTestTokens;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support.PostgresTestSupport;

/** Proves the database-level append-only guard on {@code ledger_entry} survives repeated schema application. */
// config/MetricsScrapeController takes a PrometheusMeterRegistry by constructor, and the test slice switches metrics
// export off unless a test asks for it, which would fail this context on a missing bean rather than on its subject.
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LedgerImmutabilityIT extends PostgresTestSupport {

    private static final String OWNER = "LEDGERIMMUT";

    private static final String UPDATE_ATTEMPT = "UPDATE ledger_entry SET amount = 0";

    private static final String DELETE_ATTEMPT = "DELETE FROM ledger_entry";

    // ledger_entry_reject() in schema/cash-account-schema.sql raises without an ERRCODE, so plpgsql reports its
    // default raise_exception rather than a class this test would otherwise have to guess at.
    private static final String RAISE_EXCEPTION_SQLSTATE = "P0001";

    private static ConfigurableApplicationContext secondaryContext;

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
        // The guard is a FOR EACH ROW trigger, so an empty table would let both attacks pass for the wrong reason.
        assertThat(ledgerRowsFor(jdbc)).isPositive();
    }

    @AfterAll
    static void closeSecondaryContext() {
        if (secondaryContext != null) {
            secondaryContext.close();
            secondaryContext = null;
        }
    }

    @Test
    void updateOnLedgerEntryIsRejectedBeforeAndAfterSchemaReapplication() {
        assertUpdateRejected(jdbc);
        assertUpdateRejected(secondaryJdbc());
    }

    @Test
    void deleteOnLedgerEntryIsRejectedBeforeAndAfterSchemaReapplication() {
        assertDeleteRejected(jdbc);
        assertDeleteRejected(secondaryJdbc());
    }

    private void assertUpdateRejected(JdbcTemplate target) {
        assertRejected(target, UPDATE_ATTEMPT, "UPDATE");
    }

    private void assertDeleteRejected(JdbcTemplate target) {
        assertRejected(target, DELETE_ATTEMPT, "DELETE");
    }

    // Raw SQL rather than the repository because that is the whole point: persistence/LedgerEntryRepository exposes
    // only save and two finders, so nothing above the database can even express this attack, and the ledger it
    // protects replaces a VSAM history that nothing ever read back and that silently discarded a second record for
    // the same owner within one second [backend/cash-account-cobol/COBOL/CASH00.cbl:L123-L131].
    private void assertRejected(JdbcTemplate target, String statement, String operation) {
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> target.update(statement))
                .satisfies(failure -> {
                    SQLException raised = rootSqlException(failure);
                    assertThat(raised.getSQLState()).isEqualTo(RAISE_EXCEPTION_SQLSTATE);
                    // The message, not just the state: it is the wording ledger_entry_reject() itself raises.
                    assertThat(raised.getMessage())
                            .contains("ledger_entry is append-only: " + operation + " is rejected");
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
                    // Outside the test context nothing contributes Testcontainers' JdbcConnectionDetails, so
                    // config/DataSourceGuardConfig assembles the URL itself; an explicit spring.datasource.url is the
                    // single input it returns verbatim, which is what lands this start-up on the seeded container.
                    // These arrive as command-line arguments rather than through properties(), which would place
                    // them in defaultProperties where application.yml's own ${JDBC_ID:}/${JDBC_PASSWORD:} - empty
                    // here - outrank them, leaving the connection unauthenticated.
                    .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                            "--spring.datasource.username=" + POSTGRES.getUsername(),
                            "--spring.datasource.password=" + POSTGRES.getPassword(),
                            "--cashaccount.security.jwt.public-key-location=" + JwtTestTokens.publicKeyLocation());
        }
        return secondaryContext.getBean(JdbcTemplate.class);
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
