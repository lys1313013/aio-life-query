# syntax=docker/dockerfile:1
# Official Hasura release; preserve its executable and entrypoint unchanged.
FROM ghcr.io/hasura/v3-engine@sha256:17ddce6b669cad105e02d6864ac327fca8efff3febbddf6476d38e2a4ce78238
COPY connector/licenses/hasura-engine.LICENSE /usr/share/licenses/hasura-engine/LICENSE
COPY connector/licenses/NOTICE /usr/share/licenses/hasura-engine/NOTICE
LABEL io.hasura.engine.version="v2026.10.05" io.aio.query.upstream.image="ghcr.io/hasura/v3-engine@sha256:17ddce6b669cad105e02d6864ac327fca8efff3febbddf6476d38e2a4ce78238"
USER 10001:10001
EXPOSE 3000
