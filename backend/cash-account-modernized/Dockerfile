# Packaging only: the jar is built by the module's own gate before "docker build", because a second Maven
# run inside the image would resolve a different dependency set than that gate tested.

# The base image of backend/account/src/main/docker/Dockerfile.jvm:L94, so this service inherits the estate's
# Java 21 runtime, its non-root 185 user contract and its entrypoint. The @sha256 digest is the one part this
# line does not share with that sibling: a tag can be re-pointed after the build that validated it, so the
# digest is the immutable build input and the tag only records which release it names. It is the multi-arch
# manifest-list digest, so the image stays buildable on amd64, arm64, ppc64le and s390x. Re-derive with
#   docker image inspect registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24 --format '{{json .RepoDigests}}'
# or "docker manifest inspect <repository>:<tag>", confirming the mediaType is
# application/vnd.docker.distribution.manifest.list.v2+json before pinning; a newer base release is a reviewed
# change of this digest with the tag updated in the same edit.
#
# The TAG deliberately leads that sibling rather than matching it, and this is the whole reason the line differs:
# account still names 1.21, whose content is RHEL 9.5 with java-21-openjdk-headless 21.0.6.0.7-1.el9 (January
# 2025). Every OS and JDK erratum published since ships unpatched in it - measured at 75 HIGH findings, 64 of them
# with fixes available, including ten CVEs against the JDK itself remediated no later than 21.0.12.0.8. The 1.24
# stream carries RHEL 9.8 and java-21-openjdk-headless 21.0.12.1.1-1.2.el9, which reduces that to the 11 HIGH
# findings for which Red Hat ships no fix in any tag of this image. Inheriting a sibling's *provenance* is the
# intent; inheriting the vulnerabilities of a tag frozen a year earlier is not, and the tag is the only place
# that choice can be made. Promotion re-checks it rather than trusting this comment: the blocking scan gate in
# README.md ("Image build, scan and push") fails the build on any CRITICAL or HIGH that has a fix, so a base
# image gone stale since this pin was reviewed stops the next promotion instead of shipping quietly.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24@sha256:908540a90db3dea9dcc8b80de84c9d44ced2dd328d3ed36930b0b04ad8a0f4e8

ENV LANGUAGE='en_US:en'

# The wildcard can match exactly one file, and that is an invariant of pom.xml rather than of this line:
# the repackage goal runs with no classifier, so it replaces the executable jar in place and renames the
# thin jar to <artifact>.jar.original, which this .jar-suffixed pattern cannot select. A classifier would
# add a second matching jar, and several sources copied onto one destination resolve by copy order rather
# than by error, so the image would boot whichever jar landed last.
COPY --chown=185 target/cash-account-modernized-*.jar /deployments/app.jar

# Nothing else is copied, and docs/legacy-characterization.md in particular is NOT a second COPY: pom.xml's
# copy-characterization-document execution packages it inside that jar, so a container `--tool.command=load` or
# `reconcile` records migration_run.characterization_status from the revision this image was built from rather
# than degrading to DRAFT for want of a working directory. A document signed ACCEPTED after the build is newer
# than the image and is named with -Dcashaccount.characterization-doc=<mounted path>, which outranks the
# packaged copy; the runbook's scheduled reconcile mounts it exactly that way. A copy at a second path here
# would be a third answer to "which baseline did this run read", with no way to tell which one it used.

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
