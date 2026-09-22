/*
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

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

    // The JPQL is declared rather than derived because "ForUpdate" is not a property of CashAccount: a derived
    // query would read the method name as the property path ownerForUpdate and break the repository factory at
    // context start-up, whereas a declared query pre-empts derivation under the default CREATE_IF_NOT_FOUND
    // lookup strategy.
    //
    // This row lock is the FIRST lock every balance mutation takes - the account row here, the reservation row
    // FOR UPDATE afterwards - and that single fixed order is what keeps the design cycle-free, the expiry sweep
    // included: a sweeper and a concurrent settle contend for the account row before either reaches the
    // reservation, so one waits rather than the two deadlocking.
    //
    // The caller must already be inside its own @Transactional unit. Invoked bare, Spring Data's default
    // read-only transaction commits as the method returns and surrenders the lock before the balance it was
    // taken to protect has been read, decided upon and written.
    //
    // Pessimistic row locking is the estate's established strategy
    // (backend/portfolio/src/main/resources/META-INF/persistence.xml:L13-L14), narrowed here on purpose from
    // that unit-wide eclipselink.pessimistic-lock setting to this one query so plain reads stay lock-free; the
    // same file's cache.shared.default=false is why no second-level or query cache is introduced alongside it.
    //
    // PESSIMISTIC_WRITE reaches PostgreSQL as FOR NO KEY UPDATE, the rendering Hibernate's dialect gives every
    // write lock, so the logged SQL reads weaker than it is: that mode conflicts with itself and with any
    // UPDATE or DELETE of the row, leaving only a foreign-key reference on the weaker KEY SHARE lock - and no
    // table references cash_account.
    //
    // No jakarta.persistence.lock.timeout hint accompanies it: PostgreSQL's FOR UPDATE expresses only NOWAIT
    // and SKIP LOCKED, so a positive wait would be silently ignored. Blocking is therefore left to the server,
    // whose own deadlock detection surfaces as CannotAcquireLockException - which the error package already
    // renders as 409 CONCURRENT_MODIFICATION, never a 500, so nothing is caught or translated here.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAccount a where a.owner = :owner")
    Optional<CashAccount> findByOwnerForUpdate(@Param("owner") String owner);

    boolean existsByOwner(String owner);
}
