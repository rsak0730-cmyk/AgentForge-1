#!/bin/sh
# GitHub/Android Studio can use the configured Gradle distribution. The wrapper JAR is intentionally omitted from this offline build artifact.
exec gradle "$@"
