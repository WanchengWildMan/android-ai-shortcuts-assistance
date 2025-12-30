#!/bin/sh

#
# Gradle wrapper script for Unix
#

# Resolve links
PRG="$0"
while [ -h "$PRG" ]; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/"$link"
    fi
done

PRGDIR=$(dirname "$PRG")
APP_HOME=$(cd "$PRGDIR" && pwd)

# Gradle settings
GRADLE_OPTS=${GRADLE_OPTS:-""}
JAVA_HOME=${JAVA_HOME:-""}

# Gradle wrapper jar location
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# Download wrapper jar if not exist
if [ ! -f "$WRAPPER_JAR" ]; then
    GRADLE_VERSION=$(grep "distributionUrl" "$WRAPPER_PROPERTIES" | sed 's/.*gradle-\([0-9.]*\).*/\1/')
    echo "Downloading Gradle wrapper jar..."
    mkdir -p "$APP_HOME/gradle/wrapper"
    curl -sLo "$WRAPPER_JAR" "https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || \
    curl -sLo "$WRAPPER_JAR" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-wrapper.jar.md5" 2>/dev/null
fi

# Find java
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Execute gradle
exec "$JAVACMD" $GRADLE_OPTS -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
