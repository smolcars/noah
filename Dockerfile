# Keep builder and runtime on the same Debian release so the compiled
# binaries do not require a newer glibc than the runtime image provides.
ARG RUST_VERSION=1.95
ARG DEBIAN_RELEASE=bookworm

# Stage 1: cargo-chef base image
FROM lukemathwalker/cargo-chef:latest-rust-${RUST_VERSION}-${DEBIAN_RELEASE} AS chef
WORKDIR /app

# Stage 2: Analyze dependencies
FROM chef AS planner
COPY ./Cargo.toml ./Cargo.toml
COPY ./server/Cargo.toml ./server/Cargo.toml
COPY ./Cargo.lock ./Cargo.lock
COPY ./server/src/ ./server/src
COPY ./server/migrations ./server/migrations
RUN cargo chef prepare --recipe-path recipe.json

# Stage 3: Build dependencies (cached layer)
FROM chef AS builder
RUN apt-get update && apt-get install -y protobuf-compiler && rm -rf /var/lib/apt/lists/*

COPY --from=planner /app/recipe.json recipe.json
RUN cargo chef cook --release --recipe-path recipe.json -p server

# Stage 4: Build application
COPY ./Cargo.toml ./Cargo.toml
COPY ./server/Cargo.toml ./server/Cargo.toml
COPY ./Cargo.lock ./Cargo.lock
COPY ./server/src/ ./server/src
COPY ./server/migrations ./server/migrations
WORKDIR /app/server
RUN cargo build --release

# Stage 5: Runtime image
FROM debian:${DEBIAN_RELEASE}-slim AS runtime
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/server /usr/local/bin/
COPY --from=builder /app/target/release/noah-cli /usr/local/bin/

EXPOSE 3000
CMD ["server"]
