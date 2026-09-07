# syntax=docker/dockerfile:1

FROM debian:bookworm-slim AS downloader

ARG BARK_VERSION=0.7.0
ARG TARGETARCH
ARG BARKD_SHA256_AMD64=9da7f19150d37e16ec20e91c25a8e76b01b244b8524ebcd534ea72c25d988370
ARG BARKD_SHA256_ARM64=7f689baf69ef7678d135caafcce8c81dd658ed11eb61e58a9220cbdd5688d68d

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
