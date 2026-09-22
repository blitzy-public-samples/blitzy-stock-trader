package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for {@code cash_account}: an owner's balance row, read plainly or claimed under a write lock. */
public interface CashAccountRepository extends JpaRepository<CashAccount, String> {

    Optional<CashAccount> findByOwner(String owner);

    // This row lock is the FIRST lock every balance mutation takes - the account row here, the reservation row
    // FOR UPDATE afterwards - and that one fixed order is what keeps the design cycle-free, the expiry sweep
    // included: a sweeper and a concurrent settle contend for the account row before either reaches the
    // reservation, so one waits rather than the two deadlocking.
    //
    // The caller must already be inside its own @Transactional unit: invoked bare, Spring Data's default
    // read-only transaction commits as the method returns and surrenders the lock before the balance it was
    // taken to protect has been read, decided upon and written.
    //
    // Pessimistic row locking is the estate's established strategy
    // (backend/portfolio/src/main/resources/META-INF/persistence.xml:L13-L14), narrowed here from that
    // unit-wide setting to the queries that need it so plain reads stay lock-free; the same file's
    // cache.shared.default=false is why no second-level or query cache is introduced alongside it.
    //
    // The JPQL is declared because "ForUpdate" is not a property of CashAccount: derivation would read the
    // method name as the property path ownerForUpdate and fail the repository factory at start-up. No
    // lock-timeout hint accompanies the lock, because PostgreSQL's row locks express only NOWAIT and SKIP
    // LOCKED; blocking is the server's to arbitrate, and its deadlock detection reaches the error package as
    // CannotAcquireLockException, already rendered there as 409 CONCURRENT_MODIFICATION.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAccount a where a.owner = :owner")
    Optional<CashAccount> findByOwnerForUpdate(@Param("owner") String owner);

    // A courtesy check, never the guard: two concurrent creates of one owner can both see false here, so the
    // authority is the pk_cash_account primary key and the 409 ACCOUNT_ALREADY_EXISTS its violation becomes.
    // For that authority to speak, the inherited saveAndFlush has to reach the database as an INSERT, which
    // is why domain/CashAccount declares a nullable @Version - Spring Data then persists an unwritten account
    // instead of merging it into whichever row a concurrent create had just committed.
    boolean existsByOwner(String owner);
}
