@echo off
cd /d %~dp0
echo Starting DMQ GUI Client (Direct Class)...
echo Classpath: Current directory + dmq-common
java -cp ".;../../dmq-common/target/classes" DMQGuiClientWithAuth
pause

@REM skips jar goof for test live changes
@REM Note: Make sure to compile first with: javac -encoding UTF-8 *.java