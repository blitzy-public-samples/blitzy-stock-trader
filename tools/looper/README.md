<!--
       Copyright 2017-2020 IBM Corp All Rights Reserved
       Copyright 2021-2024 Kyndryl, All Rights Reserved

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


The *looper* microservice runs a dozen calls to *broker* REST APIs in a loop.  You can use this for
performance and load testing.  It calls the *broker* service via the **mpRestClient** from
**MicroProfile**, and builds and passes the **JWT** that it requires.

Note it deliberately does not cause any changes in loyalty level, since **Twitter** will disable your
account if you blast in too many tweets per second.  It also doesn't talk to **Watson**, since you only
get 2500 free calls per month before it starts charging you per call.

It responds to a `GET ?count={count}` REST request, where you pass in the count of how many iterations
to run.  If you omit the **count** query param, it assumes 1 iteration.

For example, if you hit the `http://localhost:9080/looper?count=5` URL, it would run 5 iterations.  It
returns the output from the various calls (various collections of JSON) as `text/plain`.

Note that the call doesn't return until all the iterations are complete.  So you might be waiting a
long time in a browser (or curl) if you request a high count.  To address that, there's also a
command-line client that will also get installed to the pod running the looper servlet.  Just
`kubectl exec` into the pod, and then run `./loopctl.sh`, passing it parameters as explained when you
run it with no parameters.  It will run a specified number of iterations, on a specified number of
parallel threads.  You will see output from every iteration, with timings.  Or you can build the
CLI client locally and run it from your laptop, passing the *node port*, *ingress* or *route* URL of
where the *looper* servlet is running.

There is also a Cleanup utility that gets built into the `loopctl.jar`, and a `cleanup.sh` script that
gets installed into the Looper pod, alongside the `loopctl.sh` script.  Simple invoke it and pass it a
prefix string, and it will iterate through all portfolios in the system and call the `delete` operation
on any that start with the specified prefix.  Note that all portfolios created by Looper start with the
string `Looper`, and all created by [Ryan's Gatling-based client](https://github.com/rtclauss/loopr)
start with the string `gatling-`.  So if you had to abort a Looper or Gatling run part way through for
whatever reason, and thus have a bunch of leftovers that didn't get the chance to be deleted at the end
as usual, this script will clean those up, so you are ready for the next run.  It can be invoked (once
`'kubectl exec`'d into the Looper pod) via `./cleanup.sh Looper` (or `./cleanup.sh gatling-`).

### Deploy

Use WebSphere Liberty helm chart to deploy Looper microservice:
```bash
helm repo add ibm-charts https://raw.githubusercontent.com/IBM/charts/master/repo/stable/
helm install ibm-charts/ibm-websphere-liberty -f <VALUES_YAML> -n <RELEASE_NAME> --tls
```

In practice this means you'll run something like:
```bash
helm repo add ibm-charts https://raw.githubusercontent.com/IBM/charts/master/repo/stable/
helm install ibm-charts/ibm-websphere-liberty -f manifests/looper-values.yaml -n looper --namespace stock-trader --tls
```


