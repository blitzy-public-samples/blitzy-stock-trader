#       Copyright 2017-2021 IBM Corp All Rights Reserved
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

# FROM websphere-liberty:microProfile4
FROM openliberty/open-liberty:25.0.0.3-full-java17-openj9-ubi

USER root
RUN echo 'cjot' | passwd --stdin root

# Set up ping and nc to work when kubectl exec'd into the pod (note you have to "su" once kubectl exec'd in, for it to work)
# Note I prefer nc over telnet, for security and other reasons - see https://geekflare.com/telnet-on-rhel/ for more info
RUN yum -y install iputils nc

COPY --chown=1001:0 config /config

# This script will add the requested XML snippets to enable Liberty features and grow image to be fit-for-purpose using featureUtility. 
# Only available in 'kernel-slim'. The 'full' tag already includes all features for convenience.
# RUN features.sh

COPY --chown=1001:0 server/target/server-1.0-SNAPSHOT.war /config/apps/looper.war
COPY --chown=1001:0 client/target/client-1.0-SNAPSHOT.jar /loopctl.jar
COPY --chown=1001:0 client/target/prereqs/json-20250107.jar /json.jar
COPY --chown=1001:0 client/loopctl.sh /loopctl.sh
COPY --chown=1001:0 client/cleanup.sh /cleanup.sh

EXPOSE 9080

USER 1001

#configure.sh will try to start the server, to build the shared class cache, unless you use the following line
#normally that's fine, but if building an Intel (amd64) image on a Mac M1 (arm64), that will hang
ENV OPENJ9_SCC=false
RUN configure.sh
