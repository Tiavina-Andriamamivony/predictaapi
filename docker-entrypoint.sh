#!/bin/sh
# Entrypoint Predicta pour Render : adapte les variables d'environnement Render à Spring Boot.
set -eu

# Render injecte DATABASE_URL au format postgres://user:pass@host:port/dbname, alors que
# Spring Boot attend un URL JDBC. On traduit le schéma (postgres:// comme postgresql://).
# Si DATABASE_URL est déjà un URL JDBC (ou si SPRING_DATASOURCE_URL est posé), rien à faire.
DATABASE_URL="${DATABASE_URL:-}"
case "$DATABASE_URL" in
  postgres://* | postgresql://*)
    SPRING_DATASOURCE_URL="jdbc:postgresql://${DATABASE_URL#*://}"
    export SPRING_DATASOURCE_URL
    ;;
esac

# Render choisit le port d'écoute de chaque instance via PORT (variable posée par la plateforme).
PORT="${PORT:-8080}"

# -XX:MaxRAMPercentage=75 : heap JVM adaptée à la limite mémoire du conteneur (les plans les plus
#   petits font 512 Mo ; le filtre gzip de /traffic bufferise la réponse brute en mémoire).
# -XX:+ExitOnOutOfMemoryError : OOM => sortie du process => Render redémarre proprement.
# JAVA_OPTS : réglages supplémentaires passés depuis le dashboard Render si besoin.
exec java ${JAVA_OPTS:-} \
  -XX:MaxRAMPercentage=75 \
  -XX:+ExitOnOutOfMemoryError \
  -Dserver.port="$PORT" \
  -jar /app/app.jar
