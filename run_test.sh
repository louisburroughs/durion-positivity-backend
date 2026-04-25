#!/bin/bash
source $HOME/.sdkman/bin/sdkman-init.sh
sdk use java 25.0.2-tem
./mvnw -pl pos-catalog -am -DskipTests=false verify --no-transfer-progress
