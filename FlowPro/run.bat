@echo off
rem Runs the simulation given in args.txt (or use: run.bat -g GEOMETRY -s SIMULATION -p KEY=VALUE)
rem Option -d64 was removed, Java 10+ refuses to start with it.
java -Xmx16g -Xss10m -jar FlowPro.jar run %*
pause
