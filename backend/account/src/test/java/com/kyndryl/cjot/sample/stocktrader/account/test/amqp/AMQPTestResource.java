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
package com.kyndryl.cjot.sample.stocktrader.account.test.amqp;


import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Creates an Apache ActiveMQ Artemis (AMQP 1.0) testcontainer for unit testing purposes only
 */
public class AMQPTestResource implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {
    public GenericContainer<?> amqp;
    private static final String AMQP_USER = "artemis";
    private static final String AMQP_PASSWORD = "artemis";

    private Optional<String> containerNetworkId;

    @Override
    public Map<String, String> start() {
        amqp = new GenericContainer<>(DockerImageName.parse("apache/activemq-artemis:2.37.0"))
                .withExposedPorts(61616)
                .waitingFor(new AMQPStartupLogWaitStrategy())
                .withStartupTimeout(Duration.ofSeconds(240));
        amqp.start();

        containerNetworkId.ifPresent(amqp::withNetworkMode);

        // Configure QPID JMS in the Unit Test environment
        Map<String, String> conf = new HashMap<>();
        conf.put("quarkus.qpid-jms.url", "amqp://" + amqp.getHost() + ":" + amqp.getMappedPort(61616).toString());
        conf.put("quarkus.qpid-jms.username", AMQP_USER);
        conf.put("quarkus.qpid-jms.password", AMQP_PASSWORD);
        conf.put("quarkus.qpid-jms.wrap", "true");
        return conf;
    }

    @Override
    public void stop() {
        //AMQP stop
        //System.out.println(amqp.getLogs());
        amqp.stop();
    }

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        containerNetworkId = context.containerNetworkId();
    }
}
