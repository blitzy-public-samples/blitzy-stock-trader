#       Copyright 2025 Kyndryl, All Rights Reserved
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

# Packaging only: the jar is built outside the image ("./mvnw -B clean verify", then
# "docker build -t <registry>/cash-account:<version> ."). No builder stage compiles here, because a
# second Maven run inside the image would resolve a different dependency set than the one the module's
# own gate tested. README.md carries the full build -> scan -> push path and the --memory=2g --cpus=1
# memory-fit check. That check is needed because the heap is never stated here: this base image's
# run-java.sh sizes it from the container memory limit, emitting -XX:MaxRAMPercentage=80.0 unless
# JAVA_MAX_MEM_RATIO overrides it (0 disables the mechanism), so the chart's limits - not this file -
# decide how much heap the service gets.

# Byte-for-byte the base image and tag of backend/account/src/main/docker/Dockerfile.jvm:L94, so this
# service inherits the estate's Java 21 runtime, its non-root 185 user contract and its vulnerability
# scan baseline instead of adding a second image provenance to audit.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21

ENV LANGUAGE='en_US:en'

# One COPY where the Quarkus sibling needs four (lib/, *.jar, app/, quarkus/): a Spring Boot fat jar is a
# single artifact, so there is no separable library layer to keep re-usable across application changes.
# Exactly one file can match, and that is an invariant of pom.xml rather than of this line: the repackage
# goal runs with no classifier, so it replaces the executable jar in place and renames the thin jar to
# target/cash-account-modernized-<version>.jar.original, which this .jar-suffixed pattern cannot select.
# Configuring a repackage classifier would add a second matching jar and, because several sources copied
# onto one destination file resolve by copy order rather than by error, the image would then boot
# whichever jar landed last - so the classifier stays unset and the version stays a wildcard.
COPY --chown=185 target/cash-account-modernized-*.jar /deployments/app.jar

# run-java.sh launches exactly what JAVA_APP_JAR names; nothing else under /deployments is runnable.
# No JAVA_OPTS_APPEND is set: the sibling's value is Quarkus-specific, and pinning a profile here would
# override the one the migration tooling selects per invocation (--spring.profiles.active=tool).
ENV JAVA_APP_JAR="/deployments/app.jar"

# The chart declares both container ports and its Service maps both
# (infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L199-L201, L275-L298),
# yet it provisions no TLS material for this service, so only 8080 carries a listener. 8443 is declared
# for template conformance - Kubernetes never requires a declared port to be bound - and adding an HTTPS
# connector to satisfy it would invent key material the deployment does not supply.
EXPOSE 8080 8443

USER 185

# No HEALTHCHECK: Kubernetes owns startup, readiness and liveness through the actuator endpoints on 8080
# (.../templates/cash-account.yaml:L204-L222), and a second, Docker-level definition could only drift
# from the probes that actually gate traffic and restarts. Nothing about a deployment is baked in either:
# CURRENCY_API_URL, JDBC_*, JWT_*, OIDC_JWKS_URL and AUTH_TYPE all arrive as container environment, and
# the only security material in the image is the public jwtSigner certificate carried inside the jar.
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
