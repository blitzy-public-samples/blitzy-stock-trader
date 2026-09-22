#       Copyright 2020-2021 IBM Corp All Rights Reserved
#       Copyright 2022-2025 Kyndryl, All Rights Reserved

#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at

#       http://www.apache.org/licenses/LICENSE-2.0

#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

# Runtime-only image: it compiles nothing. Run `mvn -B clean package` first so that
# target/ExecutionControl.war exists, then `docker build .` - the sibling .dockerignore
# admits only that artefact and src/main/liberty/config, so a stale or missing WAR
# fails the build here rather than producing an image without the application.

# The 'full' tag already contains every Liberty feature, so the microProfile-7.1,
# mpTelemetry-2.1 and appSecurity-5.0 features server.xml enables need no features.sh
# or featureUtility step (those exist for the 'kernel-slim' tags). MicroProfile 7.1
# shipped in Open Liberty 25.0.0.8, so this 25.0.0.9 base carries it; the module's
# release-17 class files run unchanged on the image's Java 21 runtime.
FROM icr.io/appcafe/open-liberty:25.0.0.9-full-java21-openj9-ubi-minimal

USER root

# server.xml, includes/${AUTH_TYPE}.xml, the configDropins keystore override and the
# PKCS12 key/trust stores all land under the default server in one copy.
COPY src/main/liberty/config /opt/ol/wlp/usr/servers/defaultServer/

# The destination file name must stay ExecutionControl.war: server.xml loads the
# application by that literal location, and any other name starts a server with no
# application deployed and no error to say so.
COPY target/ExecutionControl.war /opt/ol/wlp/usr/servers/defaultServer/apps/ExecutionControl.war

# Grant the non-root runtime user (1001, root group) ownership of everything just
# copied. The absolute server path is used rather than the equivalent /config symlink
# so the instruction does not depend on the image's working directory.
RUN chown -R 1001:0 /opt/ol/wlp/usr/servers/defaultServer

EXPOSE 9080 9443

USER 1001

# Last, and as 1001: configure.sh primes the shared class cache and applies file
# permissions over the configuration and application copied above.
RUN configure.sh
