@echo off
title RAG Agent :8090 (logged)
cd /d "C:\Users\kysik\IdeaProjects\corp-agent"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo === START %date% %time% === >> app.log
call mvnw.cmd spring-boot:run >> app.log 2>&1
echo === EXITED code %errorlevel% %date% %time% === >> app.log
exit
