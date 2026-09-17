#!/bin/bash
# Load environment
. /etc/profile
. ~/.bash_profile
. ~/.bashrc


sbt clean GatlingIt/test -DpublicBaseEndpoint=https://<HOSTNAME>:9443

time_stamp=$(date +%Y-%m-%d_%T)
 
cp -r target "/home/ec2-user/caddy/site/target_${time_stamp}"

