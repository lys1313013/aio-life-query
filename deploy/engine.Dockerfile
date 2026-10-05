FROM rust:1.94.1 AS build
RUN apt-get update && apt-get install -y --no-install-recommends protobuf-compiler libssl-dev pkg-config lld && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY . .
ENV RELEASE_VERSION=aio-life-query-time-record-v1
RUN cargo build --locked --release --bin engine

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libssl3 && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 app
COPY --from=build /build/target/release/engine /usr/local/bin/engine
USER 10001
ENTRYPOINT ["engine"]
