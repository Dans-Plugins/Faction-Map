#!/usr/bin/env bash
# Rebuild the plugin and restart the running Docker test server so it loads the new JAR.
set -euo pipefail

CONTAINER="factionmap-test-mc-server"
# post-create.sh copies the JAR from this directory into plugins/ every time the container starts.
BUILD_TARGET="/testmcserver-build/FactionMap/target"
PLUGINS_DIR="/testmcserver/plugins"

if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != "true" ]; then
  echo "❌ Container $CONTAINER is not running. Start the test server with ./up.sh first."
  exit 1
fi

echo "Building plugin..."
./gradlew build

# Newest first, so a JAR left in build/libs by an earlier version is not picked up.
JAR=$(ls -t build/libs/FactionMap-*.jar 2>/dev/null | grep -v -e '-sources\.jar$' -e '-javadoc\.jar$' | head -1 || true)

if [ -z "$JAR" ]; then
  echo "❌ No JAR found in build/libs/"
  exit 1
fi

JAR_NAME=$(basename "$JAR")

echo "Replacing the plugin JAR in the container..."
# Stage the new JAR first so a failed copy leaves the installed plugin untouched.
docker cp "$JAR" "$CONTAINER:/tmp/$JAR_NAME"
# Remove every Faction Map JAR before moving the new one in, so a version bump, or a FactionMap.jar
# left by an older version of this script, does not leave two copies of the plugin in plugins/.
docker exec "$CONTAINER" sh -c "rm -f $BUILD_TARGET/FactionMap-*.jar $PLUGINS_DIR/FactionMap*.jar && mv /tmp/$JAR_NAME $BUILD_TARGET/"

echo "Restarting the test server..."
docker restart "$CONTAINER" > /dev/null

echo "✅ Copied $JAR_NAME and restarted $CONTAINER. Follow startup with: docker logs -f $CONTAINER"
