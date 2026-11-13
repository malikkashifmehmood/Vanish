@echo off
rem Thin launcher. scripts\run-app.ps1 downloads gradle-wrapper.jar the first time.
setlocal
set APP_HOME=%~dp0
if defined JAVA_HOME (set JAVACMD=%JAVA_HOME%\bin\java.exe) else (set JAVACMD=java.exe)
if not exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  echo gradle-wrapper.jar is missing. Run scripts\run-app.ps1 once, or open this folder in Android Studio.
  exit /b 1
)
"%JAVACMD%" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
