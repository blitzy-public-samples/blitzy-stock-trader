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

# The 'full' tag already contains every Liberty feature, so the microProfile-7.1,
# mpTelemetry-2.1 and appSecurity-5.0 features server.xml enables need no features.sh
# or featureUtility step (those exist for the 'kernel-slim' tags). 26.0.0.9 is a
# security floor, not a preference: the servlet request-smuggling advisories are fixed
# only from 26.0.0.8 onwards. The digest follows the tag because the tag is mutable -
# it holds every rebuild to the base layers that were reviewed, while the tag keeps the
# release readable; re-review both on the next runtime upgrade.
FROM icr.io/appcafe/open-liberty:26.0.0.9-full-java21-openj9-ubi-minimal@sha256:4c84a4fc73413adf3406513b7827eee721591bf139b245fea51b71e00b0dc4bc

USER root

# The whole server configuration directory, which carries jvm.options as well as server.xml
# and the includes. That file is what bounds the heap: the base image supplies its JVM
# options through JAVA_TOOL_OPTIONS and ships no jvm.options of its own, so this copy adds
# one rather than replacing anything, and a command-line -Xmx from it takes precedence over
# the image's environment.
COPY src/main/liberty/config /opt/ol/wlp/usr/servers/defaultServer/

# The destination file name must stay ExecutionControl.war: server.xml loads the
# application by that literal location, so any other name leaves the application
# undeployed.
COPY target/ExecutionControl.war /opt/ol/wlp/usr/servers/defaultServer/apps/ExecutionControl.war

# Grant the non-root runtime user (1001, root group) ownership of everything just
# copied. The absolute server path is used rather than the equivalent /config symlink
# so the instruction does not depend on the image's working directory. Ownership is the
# whole of what this settles: the base image ships /opt/ol/wlp and /opt/ol/wlp/lib
# group-writable (775 default:root) for platforms that run it under an arbitrary UID, so
# 1001 can still write the runtime it executes and the server.xml it re-reads on restart.
# Those two are closed at the pod rather than here, because no image-layer permission can
# take write access away from the identity that must own the configuration - see
# readOnlyRootFilesystem in the README's deployment manifest.
RUN chown -R 1001:0 /opt/ol/wlp/usr/servers/defaultServer

# Both PKCS12 stores hold a private key and COPY preserved the source mode (0644), which
# would leave them readable by every identity in the image. The tightening belongs to the
# image and not to git, because the tracked copies must stay identical to the broker's down
# to their file mode, and it follows the chown because it is the new owner that keeps the
# access: 1001 is the only identity that ever runs this server - including the build-time
# start configure.sh performs below, which reads key.p12 to prime the class cache - so
# owner-only is the whole of the access the runtime needs.
RUN chmod 600 /opt/ol/wlp/usr/servers/defaultServer/resources/security/*.p12

EXPOSE 9080 9443

USER 1001

# Last, and as 1001: configure.sh primes the shared class cache by starting the server
# once, and writes whichever configuration dropins the base image's helpers ask for. It
# touches nothing under resources/, so the modes set above are the modes that ship.
RUN configure.sh

# Priming the class cache starts the server, which creates an LTPA key file under the
# password in effect at build time. Two reasons it must not ship inside the image: a
# deployment that supplies its own LTPA_KEYS_PASSWORD could not read a file encrypted with
# the default one, and key material built into a published image is material every copy of
# that image shares. Removed here, each deployment generates its own keys on first boot,
# under whatever password is effective then, and reads them back on every boot after.
RUN rm -f /opt/ol/wlp/output/defaultServer/resources/security/ltpa.keys
