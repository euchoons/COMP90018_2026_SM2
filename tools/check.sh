#!/usr/bin/env sh
set -eu

./gradlew testDebugUnitTest lintDebug assembleDebug

echo "FloraGuide checks completed successfully."
