#!/bin/sh
set -eu

# Smoke-tests the Pl@ntNet identification endpoint the app calls.
# Usage: tools/verify-plantnet.sh path/to/plant.jpg
# The key is read from local.properties so it never appears in shell history.

PLANTNET_ENDPOINT="https://my-api.plantnet.org/v2/identify/all"
PLANTNET_PHOTO=${1:?usage: tools/verify-plantnet.sh path/to/plant.jpg}
PLANTNET_API_KEY=$(sed -n 's/^plantnet.api.key=//p' local.properties)

if [ -z "$PLANTNET_API_KEY" ]; then
    echo "plantnet.api.key is missing from local.properties" >&2
    exit 1
fi

PLANTNET_RESPONSE_FILE=$(mktemp)
trap 'rm -f "$PLANTNET_RESPONSE_FILE"' EXIT HUP INT TERM

PLANTNET_METRICS=$(curl \
    --location \
    --silent \
    --show-error \
    --request POST \
    --form "organs=auto" \
    --form "images=@$PLANTNET_PHOTO" \
    --output "$PLANTNET_RESPONSE_FILE" \
    --write-out '%{http_code} %{time_total}' \
    "$PLANTNET_ENDPOINT?api-key=$PLANTNET_API_KEY&nb-results=8&lang=en")

PLANTNET_HTTP_STATUS=${PLANTNET_METRICS%% *}
PLANTNET_ELAPSED_SECONDS=${PLANTNET_METRICS#* }

printf 'HTTP %s\t%s s\n' "$PLANTNET_HTTP_STATUS" "$PLANTNET_ELAPSED_SECONDS"

if [ "$PLANTNET_HTTP_STATUS" != "200" ]; then
    jq '.' "$PLANTNET_RESPONSE_FILE" >&2
    exit 1
fi

jq --raw-output \
    '"remaining requests: \(.remainingIdentificationRequests)",
     (.results[] | "\(.score * 100 | floor / 100)\t\(.species.scientificNameWithoutAuthor)\t\(.species.commonNames[0] // "-")")' \
    "$PLANTNET_RESPONSE_FILE"
