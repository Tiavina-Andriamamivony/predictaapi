# syntax=docker/dockerfile:1

#################### STAGE DE BUILD ####################
# Gradle 8.5 = version épinglée par le wrapper (gradle/wrapper/gradle-wrapper.properties).
# L'image officielle gradle:8.5-jdk21 embarque exactement cette version : pas de téléchargement
# de distribution, et le JDK 21 correspond à la toolchain du projet.
FROM gradle:8.5-jdk21 AS build
WORKDIR /home/gradle/app

# 1) Fichiers de build seuls : la résolution des dépendances (mavenCentral + plugin portal) est
#    mise en cache dans le layer tant que build.gradle ne change pas.
COPY --chown=gradle:gradle settings.gradle build.gradle lombok.config ./
RUN gradle dependencies --no-daemon

# 2) Sources, puis compilation + jar Spring Boot. On cible bootJar (pas build) : les tests
#    d'intégration reposent sur Testcontainers, qui a besoin d'un Docker, indisponible pendant
#    un build d'image.
COPY --chown=gradle:gradle src src
RUN gradle bootJar --no-daemon

#################### STAGE RUNTIME ####################
# JRE 21, multi-arch (amd64 + arm64, supportés par Render), sans SDK.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Utilisateur non-root : bonne pratique conteneur, exigée par les scans de sécurité Render.
RUN useradd --create-home --uid 1000 --shell /usr/sbin/nologin appuser

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# Le build produit deux jars : le boot jar exécutable (92 Mo) et le jar "plat" (-plain.jar).
# On ne conserve que le boot jar, quel que soit le nom de version.
COPY --from=build /home/gradle/app/build/libs/ /app/libs/
RUN rm -f /app/libs/*-plain.jar \
 && mv /app/libs/*.jar /app/app.jar \
 && rm -rf /app/libs

EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
