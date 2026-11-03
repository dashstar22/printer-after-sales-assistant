FROM eclipse-temurin@sha256:4faa9eaf440514dd4f7ef71fecb92526e13679aea039bed5d2fc98e885c60dee
WORKDIR /app
RUN groupadd -g 10001 printer && useradd -u 10001 -g printer -s /usr/sbin/nologin printer && mkdir /data && chown printer:printer /data
COPY app.jar /app/app.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
USER 10001:10001
ENTRYPOINT ["/app/entrypoint.sh"]
