#!/bin/sh
set -eu

ALA_ENDPOINT="https://api.ala.org.au/occurrences/occurrences/search"
ALA_LATITUDE="-37.7963"
ALA_LONGITUDE="144.9614"
ALA_RADIUS_KM="8"

for ALA_SCIENTIFIC_NAME in \
    "Eucalyptus camaldulensis" \
    "Acacia melanoxylon" \
    "Platanus × acerifolia"
do
    ALA_RESPONSE_FILE=$(mktemp)
    trap 'rm -f "$ALA_RESPONSE_FILE"' EXIT HUP INT TERM

    ALA_METRICS=$(curl \
        --fail \
        --location \
        --silent \
        --show-error \
        --get "$ALA_ENDPOINT" \
        --data-urlencode "q=scientificName:\"$ALA_SCIENTIFIC_NAME\"" \
        --data-urlencode "lat=$ALA_LATITUDE" \
        --data-urlencode "lon=$ALA_LONGITUDE" \
        --data-urlencode "radius=$ALA_RADIUS_KM" \
        --data-urlencode "pageSize=0" \
        --data-urlencode "facet=false" \
        --output "$ALA_RESPONSE_FILE" \
        --write-out '%{http_code} %{time_total}')

    ALA_HTTP_STATUS=${ALA_METRICS%% *}
    ALA_ELAPSED_SECONDS=${ALA_METRICS#* }
    ALA_TOTAL_RECORDS=$(jq --exit-status --raw-output '.totalRecords' "$ALA_RESPONSE_FILE")

    printf '%s\tHTTP %s\t%s s\ttotalRecords=%s\n' \
        "$ALA_SCIENTIFIC_NAME" \
        "$ALA_HTTP_STATUS" \
        "$ALA_ELAPSED_SECONDS" \
        "$ALA_TOTAL_RECORDS"

    rm -f "$ALA_RESPONSE_FILE"
    trap - EXIT HUP INT TERM
done
