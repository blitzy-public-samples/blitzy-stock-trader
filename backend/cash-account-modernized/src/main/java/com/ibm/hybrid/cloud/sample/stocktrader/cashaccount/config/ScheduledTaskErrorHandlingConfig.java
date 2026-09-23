package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.LogSafeText;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;

import java.util.List;

/** Bounds what a failed scheduled pass records, so a datastore outage costs one line per pass rather than a stack. */
@Configuration
public class ScheduledTaskErrorHandlingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledTaskErrorHandlingConfig.class);

    // Every way the datastore can fail a pass, and nothing else. The first three are the conditions
    // error/ApiExceptionHandler answers as DATASTORE_UNAVAILABLE on the request path and the fourth covers the lock
    // and optimistic-version families it answers as CONCURRENT_MODIFICATION (CannotAcquireLockException and
    // ObjectOptimisticLockingFailureException both arrive as that supertype), so one taxonomy governs both paths
    // and a sweep cannot report as an error what a request reports as an expected outcome.
    //
    // The last two are the shape a pool connection dying MID-transaction takes, which the first four miss and which
    // only appears at the moment an outage begins: measured against a warm pool, "FATAL: terminating connection due
    // to administrator command" surfaced as JpaSystemException - an UncategorizedDataAccessException - and its
    // rollback then failed as JpaSystemException "Unable to rollback against JDBC Connection", while the transaction
    // manager's own commit and rollback failures are TransactionExceptions (CannotCreateTransactionException, the
    // steady state of an outage, is one). Both buckets are broader than the four above, and deliberately so: the
    // sweep issues one fixed query whose only realistic uncategorized failure is infrastructure, ddl-auto=validate
    // rejects a mapping fault at start-up rather than here, and a genuine defect - a constraint violation, a null,
    // an illegal state - is under none of these six and still records its full stack at ERROR below.
    private static final List<Class<? extends Throwable>> EXPECTED_DATASTORE_FAILURES = List.of(
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            QueryTimeoutException.class,
            ConcurrencyFailureException.class,
            UncategorizedDataAccessException.class,
            TransactionException.class);

    /**
     * Replaces the scheduler's default error handler for every {@code @Scheduled} task.
     *
     * @return a customizer installing the bounded handler on the auto-configured {@code ThreadPoolTaskScheduler}
     */
    // WHY this exists: institutional/ReservationService#sweepExpiredReservations runs on a fixed rate
    // (cashaccount.reservation.expiry-sweep-interval, PT60S deployed) and its first act is a datastore read, so an
    // outage makes every pass throw. Spring's default handler for a repeating task is
    // TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER, which records it as logger.error(message, throwable): measured at
    // 109 stack frames per pass, once a minute per pod, for as long as the outage lasts, at a severity that pages -
    // while readiness already reports DOWN and every affected request already answers 503 DATASTORE_UNAVAILABLE.
    // The handler below keeps the suppression semantics (the schedule must survive a failed pass, exactly as the
    // default handler guarantees) and changes only what is recorded.
    @Bean
    ThreadPoolTaskSchedulerCustomizer boundedScheduledTaskErrorHandling() {
        return scheduler -> scheduler.setErrorHandler(ScheduledTaskErrorHandlingConfig::record);
    }

    private static void record(Throwable error) {
        if (isExpectedDatastoreFailure(error)) {
            // One line per failed pass, the throwable deliberately not passed: an unreachable datastore drives this
            // on every pass, and the frames would repeat a fact readiness and the request path both already carry.
            // toString() names the type and the pool's or driver's reason, bounded and escaped by LogSafeText
            // because a driver message quotes values this service did not choose.
            LOGGER.warn("Scheduled pass skipped - {}", LogSafeText.ofMessage(error.toString()));
            LOGGER.debug("Scheduled pass skipped", error);
            return;
        }
        // An unexpected failure in a scheduled pass is a defect rather than a dependency being down, and it has no
        // request to answer and no caller to tell, so this record is the only place it can be seen: full stack,
        // ERROR, exactly as error/ApiExceptionHandler records an unexpected failure on the request path.
        LOGGER.error("Unexpected failure in a scheduled pass", error);
    }

    private static boolean isExpectedDatastoreFailure(Throwable error) {
        return EXPECTED_DATASTORE_FAILURES.stream().anyMatch(expected -> expected.isInstance(error));
    }
}
