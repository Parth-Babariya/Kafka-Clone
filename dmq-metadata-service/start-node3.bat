@echo off
echo Starting Metadata Service Node 3 (Port 9093)...
set SERVER_PORT=9093
set NODE_ID=3
set LOG_DIR=./data/node3/controller-logs
set SPRING_PROFILES_ACTIVE=h2
mvn spring-boot:run
