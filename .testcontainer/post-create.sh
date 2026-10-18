#!/bin/bash
# Prepares and starts the Docker test server. Runs at every container start.
set -e
cd /testmcserver

if [ ! -f spigot.jar ] || [ "$OVERWRITE_EXISTING_SERVER" = "true" ]; then
  cp /testmcserver-build/spigot-*.jar spigot.jar
  echo "eula=true" > eula.txt
  # The map's web server must listen on every interface inside the container for the port mapping to
  # reach it; compose.yml publishes it on the host's 127.0.0.1 only.
  mkdir -p plugins/FactionMap
  if [ ! -f plugins/FactionMap/config.yml ]; then
    printf 'web:\n  host: 0.0.0.0\n  port: 8199\n' > plugins/FactionMap/config.yml
  fi
  if [ -n "$OPERATOR_UUID" ] && [ -n "$OPERATOR_NAME" ]; then
    printf '[{"uuid":"%s","name":"%s","level":%s,"bypassesPlayerLimit":false}]\n' \
      "$OPERATOR_UUID" "$OPERATOR_NAME" "${OPERATOR_LEVEL:-4}" > ops.json
  fi
fi

mkdir -p plugins
rm -f plugins/FactionMap-*.jar plugins/medieval-factions-*.jar
cp /testmcserver-build/FactionMap/target/FactionMap-*.jar plugins/
cp /testmcserver-build/MedievalFactions/medieval-factions-*-all.jar plugins/

exec java -Xms1G -Xmx3G -jar spigot.jar nogui
