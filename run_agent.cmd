@echo off
title RAG Agent - Spring Boot :8090
cd /d "C:\Users\kysik\IdeaProjects\corp-agent"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Starting RAG agent on http://localhost:8090 ...
call mvnw.cmd spring-boot:run
echo.
echo APP STOPPED - press any key to close
pause >nul
