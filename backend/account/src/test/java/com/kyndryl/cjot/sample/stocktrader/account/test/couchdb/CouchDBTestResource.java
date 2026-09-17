/*
       Copyright 2024 Kyndryl Corp, All Rights Reserved

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
package com.kyndryl.cjot.sample.stocktrader.account.test.couchdb;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Creates a CouchDB testcontainer for unit testing purposes only
 */
public class CouchDBTestResource implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {
    public GenericContainer<?> couchDB;

    private static final String COUCHDB_USER = "admin";
    private static final String COUCHDB_PASSWORD = "password";

    private Optional<String> containerNetworkId;

    @Override
    public Map<String, String> start() {
//        System.out.println("Starting CouchDB...");
        couchDB = new GenericContainer<>(DockerImageName.parse("couchdb:3.3"))
                .withExposedPorts(5984)
                .withEnv("COUCHDB_USER", COUCHDB_USER)
                .withEnv("COUCHDB_PASSWORD", COUCHDB_PASSWORD)
                //.withCopyFileToContainer(MountableFile.forClasspathResource("couchdb/local.ini"), "/opt/couchdb/etc/local.ini")
                .waitingFor(new CouchDBStartupLogWaitStrategy())
                .withStartupTimeout(Duration.ofSeconds(240));
        couchDB.start();

        containerNetworkId.ifPresent(couchDB::withNetworkMode);

        // Configure JNoSQL-CouchDB/Cloudant ORM in the Unit Test environment
        Map<String, String> conf = new HashMap<>();
        conf.put("jnosql.couchdb.host", couchDB.getHost());
        conf.put("jnosql.couchdb.port", couchDB.getMappedPort(5984).toString());
        conf.put("jnosql.couchdb.username", COUCHDB_USER);
        conf.put("jnosql.couchdb.password", COUCHDB_PASSWORD);
        conf.put("jnosql.document.database", "account");
        return conf;
    }

    @Override
    public void stop() {
        //CouchDB stop
        //System.out.println(couchDB.getLogs());
        couchDB.stop();
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }
}
