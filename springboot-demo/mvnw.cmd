@echo off
rem Compatibility launcher: Maven must already be installed.
where mvn >nul 2>nul
if errorlevel 1 (
  echo Install Maven 3.9.x and Java 17+ first.
  exit /b 1
)
call mvn %*
exit /b %errorlevel%
