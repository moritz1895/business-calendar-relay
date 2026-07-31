#!/usr/bin/env bash
# Builds the current source tree into the business-calendar-relay:latest image and
# exports it as a portable tarball, so a target Docker host that has no access to this
# machine's local .m2 cache or the internal Maven repository (see settings.xml) can still
# run the exact same image -- just `docker load` the tarball, no build toolchain needed.
#
# Usage: ./build-and-export-image.sh [output-file]
#   Re-run this any time the source changes; it always rebuilds from scratch (no cache)
#   so the exported tarball never silently carries a stale layer.
#
# On the target system:
#   docker load -i business-calendar-relay-image.tar
#   docker compose -f docker-compose.deploy.yml up -d
# (copy docker-compose.deploy.yml, .env, and config/relay-calendars.yml there too --
# see README.md's Docker section)

set -euo pipefail

cd "$(dirname "$0")"

IMAGE_NAME="business-calendar-relay:latest"
OUTPUT_FILE="${1:-business-calendar-relay-image.tar}"

echo "Building ${IMAGE_NAME} (no cache) ..."
docker compose build --no-cache

echo "Exporting to ${OUTPUT_FILE} ..."
docker save -o "${OUTPUT_FILE}" "${IMAGE_NAME}"

SIZE=$(du -h "${OUTPUT_FILE}" | cut -f1)
echo "Done: $(pwd)/${OUTPUT_FILE} (${SIZE})"
