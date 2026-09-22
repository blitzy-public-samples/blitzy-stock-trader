# Packaging only: the jar is built by the module's own gate before "docker build", because a second Maven
# run inside the image would resolve a different dependency set than that gate tested.

# The base image and tag of backend/account/src/main/docker/Dockerfile.jvm:L94, so this service inherits the
# estate's Java 21 runtime, its non-root 185 user contract and its scan baseline. The @sha256 digest is the one
# part this line does not share with that sibling: a tag can be re-pointed after the build that validated it,
# so the digest is the immutable build input and the tag only records which release it names. It is the
# multi-arch manifest-list digest, so the image stays buildable on amd64, arm64, ppc64le and s390x. Re-derive with
#   docker image inspect registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21 --format '{{json .RepoDigests}}'
# or "docker manifest inspect <repository>:<tag>", confirming the mediaType is
# application/vnd.docker.distribution.manifest.list.v2+json before pinning; a newer base release is a reviewed
# change of this digest with the tag updated in the same edit.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21@sha256:360822c35c5741f542ab78fe123e6c4d9b68e0113a88d6e0250bb1f377b17f29

ENV LANGUAGE='en_US:en'

# The wildcard can match exactly one file, and that is an invariant of pom.xml rather than of this line:
# the repackage goal runs with no classifier, so it replaces the executable jar in place and renames the
# thin jar to <artifact>.jar.original, which this .jar-suffixed pattern cannot select. A classifier would
# add a second matching jar, and several sources copied onto one destination resolve by copy order rather
# than by error, so the image would boot whichever jar landed last.
COPY --chown=185 target/cash-account-modernized-*.jar /deployments/app.jar

# No JAVA_OPTS_APPEND: the sibling's value is Quarkus-specific, and pinning a profile here would override
# the one the migration tooling selects per invocation (--spring.profiles.active=tool).
ENV JAVA_APP_JAR="/deployments/app.jar"

# The chart declares both container ports and its Service maps both
# (infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L199-L201, L275-L298),
# yet provisions no TLS material for this service, so only 8080 carries a listener; 8443 is declared for
# template conformance, and satisfying it would mean inventing key material the deployment never supplies.
EXPOSE 8080 8443

USER 185

# No HEALTHCHECK: Kubernetes owns startup, readiness and liveness through the actuator endpoints on 8080
# (.../templates/cash-account.yaml:L204-L222), and a second Docker-level definition could only drift from
# the probes that actually gate traffic and restarts. The heap is unstated for the same reason: run-java.sh
# derives -XX:MaxRAMPercentage=80.0 from the container memory limit, so the chart's limits decide it.
ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
