@echo off
echo Building DMQ CLI...
cd ..

REM Compile the CLI source files
echo Compiling CLI sources...
mvn compiler:compile -DskipTests

REM Package into JAR with dependencies
echo Creating shaded JAR...
mvn shade:shade -DskipTests

echo.
if exist "target\mycli.jar" (
    echo ✓ CLI JAR built successfully: dmq-client\target\mycli.jar
) else (
    echo ✗ CLI JAR build failed!
)

pause
