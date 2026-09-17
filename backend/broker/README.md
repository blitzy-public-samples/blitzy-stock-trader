<!--
       Copyright 2020 IBM Corp All Rights Reserved

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


This microservice is the controller for the IBM Stock Trader application.  The various clients talk solely
to this microservice, which then takes care of coordinating calls to the downstream microservices and
merging the results.  The following operations are available:

`GET /` - gets summary data for all brokers.

`POST /{owner}` - creates a new broker for the specified owner.

`GET /{owner}` - gets details for the specified broker.

`PUT /{owner}` - updates the broker for the specified owner (by adding a stock).

`DELETE /{owner}` - removes the broker for the specified owner.

`GET /{owner}/returns` - gets the return on investment for this broker.

`POST /{owner}/feedback` - submits feedback (to the Watson Tone Analyzer)

All operations return *JSON*.  A *broker* object contains fields named *owner*, *total*, *loyalty*, *balance*,
*commissions*, *free*, *sentiment*, and *nextCommission*, plus an array of *stocks*.  A *stock* object contains
fields named *symbol*, *shares*, *commission*, *price*, *total*, and *date*.  The only operation that takes any
query params is the `PUT` operation, which expects params named *symbol* and *shares*.  Also, the `feedback`
operation takes a JSON object in the http body, with a single field named *text*.

For example, doing a `PUT http://localhost:9080/broker/John?symbol=IBM&shares=123` (against a freshly
created broker for *John*) would return *JSON* like `{"owner": "John", "total": 19120.35, "loyalty": "Bronze",
"balance": 40.01, "commissions": 9.99, "free": 0, "sentiment": "Unknown", "nextCommission": 8.99, "stocks":
[{"symbol": "IBM", "shares": 123, "commission": 9.99, "price": 155.45, "total": 19120.35, "date": "2017-06-26"}]}`.

The above REST call would call the Portfolio microservice to buy the shares of stock.  If configured (via the
`USE_ACCOUNT` environment variable), it would also call the Account microservice to determine the new loyalty
level, the account balance, and other such optional fields.  If not configured to use the Account microservice,
it will return -1 for each optional number and "Unknown" for each optional string.

### Prerequisites for Kubernetes Deployment
 This project requires one secret called `jwt`.
  ```bash
  kubectl create secret generic jwt -n stock-trader --from-literal=audience=stock-trader --from-literal=issuer=http://stock-trader.ibm.com
  ```
  
 
 ### Build and Deploy to Kubernetes
To build `broker` clone this repo and run:
```bash
mvn package
docker build -t broker:latest -t <REPO>/stock-trader/broker:latest .
docker tag broker:latest <REPO>/stock-trader/broker:latest
docker push <REPO>/stock-trader/broker:latest
```

Use the WebSphere Liberty helm chart to deploy the Broker microservice:
```bash
helm repo add ibm-charts https://raw.githubusercontent.com/IBM/charts/master/repo/stable/
helm install ibm-charts/ibm-websphere-liberty -f <VALUES_YAML> -n <RELEASE_NAME> --tls
```



