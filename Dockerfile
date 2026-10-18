FROM eclipse-temurin:25-jdk

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git wget && rm -rf /var/lib/apt/lists/*

# Build the server
ARG MINECRAFT_VERSION=26.3
WORKDIR /testmcserver-build
RUN wget -O BuildTools.jar https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar
RUN git config --global --unset core.autocrlf || :
RUN java -jar BuildTools.jar --rev ${MINECRAFT_VERSION}

# The plugin and Medieval Factions are built or fetched outside the image (./gradlew build), to save rebuilding it
COPY ./build/libs /testmcserver-build/FactionMap/target
COPY ./libs /testmcserver-build/MedievalFactions

COPY ./.testcontainer /resources
RUN chmod +x /resources/post-create.sh

WORKDIR /testmcserver
EXPOSE 25565 8199
ENTRYPOINT ["/resources/post-create.sh"]
