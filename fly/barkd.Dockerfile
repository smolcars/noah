# syntax=docker/dockerfile:1

FROM debian:bookworm-slim AS downloader

ARG BARK_VERSION=0.7.1
ARG TARGETARCH
ARG BARKD_SHA256_AMD64=8067b9aab31e250e5580620e3fd0be61f5b778860346d2433b11eae9992db85a
ARG BARKD_SHA256_ARM64=b9604e1dc4ec409d49ac842aa39dd77e0177f7b3c409a63f255f5b36d2941dd7

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && case "${TARGETARCH}" in \
        amd64) release_arch="x86_64"; checksum="${BARKD_SHA256_AMD64}" ;; \
        arm64) release_arch="arm64"; checksum="${BARKD_SHA256_ARM64}" ;; \
        *) echo "Unsupported Barkd architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl --fail --location --show-error \
        "https://gitlab.com/ark-bitcoin/bark/-/releases/bark-${BARK_VERSION}/downloads/barkd-${BARK_VERSION}-linux-${release_arch}" \
        --output /barkd \
    && echo "${checksum}  /barkd" | sha256sum --check \
    && chmod 0755 /barkd

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates gosu \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system barkd \
    && useradd --system --gid barkd --home-dir /data --no-create-home barkd \
    && install --directory --owner barkd --group barkd --mode 0700 /data

COPY --from=downloader /barkd /usr/local/bin/barkd
COPY fly/barkd-entrypoint.sh /usr/local/bin/barkd-entrypoint

ENV BARKD_DATADIR=/data/barkd \
    BARKD_BIND_HOST=:: \
    BARKD_BIND_PORT=3000

EXPOSE 3000

ENTRYPOINT ["/usr/local/bin/barkd-entrypoint"]
CMD ["barkd"]
