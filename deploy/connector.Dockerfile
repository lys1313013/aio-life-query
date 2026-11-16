# syntax=docker/dockerfile:1
FROM rust:1.94.1-alpine3.23@sha256:77237dd363a0b127bb5ef532c2d64c0deb380b738e43a9c4bdac73398d6d0a08 AS guard
WORKDIR /build
COPY deploy/connector-entrypoint.rs .
RUN rustc --edition=2024 -C opt-level=s -C strip=symbols -C target-feature=+crt-static connector-entrypoint.rs -o /connector-entrypoint

FROM ghcr.io/hasura/ndc-postgres@sha256:f91910ef5107aa80d31d82639e149b7f41f4a5bb3af9a369397d7d5965d79a57
COPY --from=guard /connector-entrypoint /bin/aio-connector-entrypoint
COPY connector/licenses/ndc-postgres.LICENSE /usr/share/licenses/ndc-postgres/LICENSE
COPY connector/licenses/NOTICE /usr/share/licenses/ndc-postgres/NOTICE
LABEL org.opencontainers.image.licenses="Apache-2.0" io.hasura.connector.version="v3.1.0" io.hasura.connector.revision="5ea4370a7ed4bb68a0136bf73447b0c92c0db5cc"
ENV HASURA_CONFIGURATION_DIRECTORY=/etc/connector HASURA_CONNECTOR_HOST=0.0.0.0 HASURA_MAX_REQUEST_SIZE=65536 RUST_LOG=warn
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/bin/aio-connector-entrypoint"]
CMD ["serve", "--configuration", "/etc/connector"]
