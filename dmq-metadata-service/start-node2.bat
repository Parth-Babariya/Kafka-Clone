@echo off
echo Starting Metadata Service Node 2 (Port 9092)...
set SERVER_PORT=9092
set NODE_ID=2
set LOG_DIR=./data/node2/controller-logs
set SPRING_PROFILES_ACTIVE=h2
mvn spring-boot:run
