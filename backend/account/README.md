<!--
       Copyright 2017-2021 IBM Corp All Rights Reserved
       Copyright 2022-2024 Kyndryl, All Rights Reserved


   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

This service manages user *accounts*.  The data is backed by a CouchDB-complient document database (Cloudant, CouchDB).  The following operations are available:

`GET /` - gets all accounts.

`POST /{owner}` - creates a new account for the specified owner.

`GET /{owner}` - gets details for the specified account.

`PUT /{owner}?total={total}` - updates the account total for the specified owner (by adding a stock).

`DELETE /{owner}` - removes the account for the specified owner.

`POST /{owner}/feedback` - submits feedback (to the Watson Tone Analyzer)

All operations return *JSON*.  An *account* object contains fields named *owner*, *total*, *loyalty*, *balance*,
*commissions*, *free*, *sentiment*, and *nextCommission*. The only operation that takes any
query params is the `PUT` operation, which expects a param named *total*.  Also, the `feedback`
operation takes a JSON object in the http body, with a single field named *text*.

This microservice calls out to three other external services. First, there is a business rule that determines the Loyalty Level
of this account. This is called via REST. `POST`ing feedback also makes a REST call to Watson to determine the sentiment and, potentially,
provide free trades depending on the sentiment. Finally, there is a Jakarta Messaging message that is sent when the loyalty level changes.  All three of these
services are optional.

The code should work with any *document-based NoSQL* provider that supports the CouchDB API.  It has been tested with **CouchDB** and with **Cloudant**.
The database can either be another pod in the same *Kubernetes* environment, a VM in the Cloud, or
it can be running on "bare metal" in a traditional on-premises environment.  Endpoint and credential info is
specified in the *Kubernetes* secret and made available as environment variables to the application.properties of Quarkus. See the *manifests/portfolio-values.yaml* for details.

The Jakarta Messaging functionality supports any AMQP 1.0 messaging provider. IBM MQ 9.2.x+ (with [AMQP 1.0 support enabled](https://developer.ibm.com/tutorials/mq-setting-up-amqp-with-mq)) and Apache ActiveMQ have both been tested.

### Build 
#### Build and run Quarkus locally 

To build `account` and run in dev mode, locally, clone this repo and run:
```bash
./mvnw quarkus:dev
```

#### To test with TestContainers

```bash
./mvnw test -Dquarkus.container-image.build=true
```

#### Build container and push to container registry
The first command builds the container image while the second, `docker push`, pushes the image to the registry you configured.

```bash
./mvnw clean install -Dquarkus.container-image.build=true \ 
    -Dquarkus.container-image.tag=<your tag> \
    -Dquarkus.container-image.group=ibmstocktrader \
    -Dquarkus.container-image.registry=<YOUR REGISTRY HOST> 

docker push <YOUR REGISTRY HOST AND REPOSITORY>
```

To do the same but skipping test cases:
```bash
./mvnw clean install -DskipTests=true \
    -Dquarkus.container-image.build=true \  
    -Dquarkus.container-image.tag=<your tag> \
    -Dquarkus.container-image.group=ibmstocktrader \
    -Dquarkus.container-image.registry=<YOUR REGISTRY HOST> 
```


#### Run container

In practice this means you'll run something like:
```bash
docker run -i --rm -p 9080:9080 <your repository>/account 
```



