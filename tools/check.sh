#!/usr/bin/env sh
set -eu

./gradlew testDebugUnitTest lintDebug assembleDebug

echo "BioScout checks completed successfully."
