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

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.*;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import com.ibm.cloud.sdk.core.service.exception.ServiceResponseException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;


/**
 * This class creates any indexes in CouchDB/Cloudant that are needed by JNoSQL
 */
@ApplicationScoped
public class AccountDbStartupBean {
    private static final Logger logger = Logger.getLogger(AccountDbStartupBean.class.getName());

    //For some reason the @ConfigProperty annotation isn't working here. So let's initialize in the onStart() method.

    // @ConfigProperty(name="jnosql.couchdb.database")
    String databaseName;
    // @ConfigProperty(name="jnosql.couchdb.password")
    String password;
    // @ConfigProperty(name="jnosql.couchdb.username")
    String username;
    // @ConfigProperty(name="jnosql.couchdb.host")
    String host;
    // @ConfigProperty(name="jnosql.couchdb.port")
    String port;


    void onStart(@Observes StartupEvent ev) {
        logger.fine("Entering onStart");
        //Gathering the
        Config config = ConfigProvider.getConfig();
        databaseName = config.getConfigValue("jnosql.document.database").getValue();
        password = config.getConfigValue("jnosql.couchdb.password").getValue();
        username = config.getConfigValue("jnosql.couchdb.username").getValue();
        host = config.getConfigValue("jnosql.couchdb.host").getValue();
        port = config.getConfigValue("jnosql.couchdb.port").getValue();

        logger.finest("CouchDB Host: " + host + ", port: " + port);
        logger.finest("CouchDB User: " + username);
        logger.finest("CouchDB Database name: " + databaseName);

        if (host == null || port == null || databaseName == null || username == null || password == null) {
            logger.warning("Some jnosql.* properties are blank. Skipping CouchDB index initialization.");
            return;
        }

        BasicAuthenticator authenticator = new BasicAuthenticator.Builder()
                .username(username)
                .password(password)
                .build();

        Cloudant service = new Cloudant(Cloudant.DEFAULT_SERVICE_NAME, authenticator);

        service.setServiceUrl("http://" + host + ":" + port);

        // Try to create database if it doesn't exist
        PutDatabaseOptions putDbOptions =
                new PutDatabaseOptions.Builder().db(databaseName).build();
        try {
            Ok putDatabaseResult = service
                    .putDatabase(putDbOptions)
                    .execute()
                    .getResult();

            if (putDatabaseResult.isOk()) {
                System.out.println("\"" + databaseName +
                        "\" database created.");
            }
        } catch (ServiceResponseException sre) {
            if (sre.getStatusCode() == 412)
                System.out.println("Cannot create \"" + databaseName +
                        "\" database, it already exists.");
        }

        // This section checks if the index already exists. If it does, then skip index creation.
        GetIndexesInformationOptions getIndexesOptions = new GetIndexesInformationOptions.Builder().db(databaseName).build();
        IndexesInformation indexes = service.getIndexesInformation(getIndexesOptions).execute().getResult();
        logger.finest("here are the pre-existing indexes:");
        logger.finest(indexes.toString());
        AtomicBoolean indexExists = new AtomicBoolean(false);
        indexes.getIndexes().forEach(index -> {
            if (index.getName().equalsIgnoreCase("getByOwner") && !index.getDef().fields().isEmpty()) {
                logger.fine("Index " + index.getName() + " does not exist. Setting boolean to true");
                indexExists.set(true);
            }
        });

        if (!indexExists.get()) {
            System.out.println("Index does not exist. Creating...");
            IndexField field = new IndexField.Builder()
                    .add("owner", "asc")
                    .build();

            IndexDefinition indexDefinition = new IndexDefinition.Builder()
                    .addFields(field)
                    .build();

            // Type "json" index fields require an object that maps the name of a field to a sort direction.
            PostIndexOptions indexOptions = new PostIndexOptions.Builder()
                    .db(databaseName)
                    .ddoc("json-index")
                    .index(indexDefinition)
                    .name("getByOwner")
                    .type("json")
                    .build();

            IndexResult response =
                    service
                            .postIndex(indexOptions)
                            .execute()
                            .getResult();
            logger.finest("Here is the response from CouchDB about index creation");
            logger.finest(response.toString());
            logger.finest("Here are the new indexes:");
            indexes = service.getIndexesInformation(getIndexesOptions).execute().getResult();
            logger.finest(indexes.toString());
        } else {
            logger.fine("CouchDB Index for accounts by owner already exists. Skipping adding new index.");
        }
    }
}
