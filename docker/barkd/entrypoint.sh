#!/bin/sh
set -eu

# Mounted volumes may be owned by root. Reclaim the mount before
# dropping privileges so barkd can initialize its nested datadir.
chown barkd:barkd /data
chmod 0700 /data

install --directory --owner barkd --group barkd --mode 0700 "${BARKD_DATADIR}"
chown barkd:barkd "${BARKD_DATADIR}"
chmod 0700 "${BARKD_DATADIR}"

exec gosu barkd:barkd "$@"
