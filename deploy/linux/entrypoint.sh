#!/bin/sh
set -eu
export MYSQL_PASSWORD="$(cat /run/secrets/mysql_password)"
export REDIS_PASSWORD="$(cat /run/secrets/redis_password)"
export RABBITMQ_PASSWORD="$(cat /run/secrets/rabbit_password)"
export EMAIL_SMTP_PASSWORD="$(cat /run/secrets/smtp_password)"
export AI_API_KEY="$(cat /run/secrets/ai_api_key)"
export KNOWLEDGE_EMBEDDING_API_KEY="$AI_API_KEY"
export KNOWLEDGE_RERANK_API_KEY="$AI_API_KEY"
if [ -f /run/secrets/admin_init_password ]; then export ADMIN_INIT_PASSWORD="$(cat /run/secrets/admin_init_password)"; fi
exec java -XX:MaxRAMPercentage=70 -jar /app/app.jar "$@"
