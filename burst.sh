#!/usr/bin/env sh
# One-command on-sale stampede against a running service.
#
#   ./burst.sh <BASE_URL> <ADMIN_KEY> [--profile smoke|full] [--max-in-flight N]
#
# Runs with a local JDK 21 if present, otherwise in a container (docker or
# podman). Force one with BURST_RUNTIME=java|docker|podman.
# Exit code 0 only if every invariant check passes.
set -eu

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <BASE_URL> <ADMIN_KEY> [--profile smoke|full] [--max-in-flight N]" >&2
  exit 2
fi

cd "$(dirname "$0")"
runtime="${BURST_RUNTIME:-}"

has_java21() {
  command -v java >/dev/null 2>&1 || return 1
  v=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
  [ "${v:-0}" -ge 21 ] 2>/dev/null
}

if [ -z "$runtime" ]; then
  if has_java21; then runtime=java
  elif command -v docker >/dev/null 2>&1; then runtime=docker
  elif command -v podman >/dev/null 2>&1; then runtime=podman
  else echo "need JDK 21, docker or podman" >&2; exit 2
  fi
fi

case "$runtime" in
  java)
    if [ ! -f burst/target/burst.jar ]; then
      ./mvnw -B -q -f burst/pom.xml package
    fi
    exec java -jar burst/target/burst.jar "$@"
    ;;
  docker|podman)
    "$runtime" build -q -f burst/Dockerfile -t seat-burst . >/dev/null
    url="$1"; shift
    # Inside a container, localhost is the container itself: point at the host instead.
    host_alias=host.docker.internal
    [ "$runtime" = podman ] && host_alias=host.containers.internal
    url=$(printf '%s' "$url" | sed -E "s#//(localhost|127\.0\.0\.1)#//${host_alias}#")
    exec "$runtime" run --rm --add-host "${host_alias}:host-gateway" seat-burst "$url" "$@"
    ;;
  *)
    echo "unknown BURST_RUNTIME=$runtime" >&2; exit 2
    ;;
esac
