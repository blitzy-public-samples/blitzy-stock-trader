/*
       Copyright 2022-2024 Kyndryl Corp, All Rights Reserved

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
package com.ibm.hybrid.cloud.sample.stocktrader.account.db;

import com.ibm.hybrid.cloud.sample.stocktrader.account.json.Account;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


// TODO move to PageableRepository
@Repository
public interface AccountRepository extends BasicRepository<Account, String> {
    Optional<Account> findByOwner(String owner);

    // This requires an index on the owner field in CouchDB to already exist
    // See AccountDbStartupBean.java for how this index is created
    // And even then, it is still a slow query.
    @WithSpan
    List<Account> findByOwnerInOrderByOwnerAsc(List<String> owner, PageRequest pageable);

    @WithSpan
    List<Account> findByOwnerIn(List<String> owner, PageRequest pageable);

    //TODO query all and order by owner ascending is not yet supported in jnosql-lite.
    //List<Account> findAllOrderByOwnerAsc(PageRequest pageable);
}
