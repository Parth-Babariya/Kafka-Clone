@echo off
echo Starting Metadata Service Node 1 (Port 9091)...
set SERVER_PORT=9091
set NODE_ID=1
set LOG_DIR=./data/node1/controller-logs
set SPRING_PROFILES_ACTIVE=h2
mvn spring-boot:run
