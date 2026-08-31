@ECHO OFF
SETLOCAL
SET APP_HOME=%~dp0
SET WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
SET WRAPPER_URL=https://github.com/gradle/gradle/raw/refs/tags/v8.13.0/gradle/wrapper/gradle-wrapper.jar

IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO FloraGuide: downloading the Gradle 8.13 wrapper bootstrap JAR...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
  IF ERRORLEVEL 1 EXIT /B 1
)

IF DEFINED JAVA_HOME (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_EXE=java.exe
)

"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL
