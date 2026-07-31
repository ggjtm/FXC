# FXC investor load harness — Locust (FxcInvestor/docs/stories/006).
#
# Containerized deliberately, following the precedent README.md sets for Tigase: "a second runtime,
# isolated in a container, never invoked from the host". Locust needs Python and pip, and the repo's
# host toolchain is otherwise JDK-21-only; keeping it in an image means adding a load generator costs
# the developer no host toolchain at all. (A host virtualenv is also supported for iterating on the
# locustfile — see docs/BUILDING.md — but the demo path is this image.)
#
# The build context is the REPO ROOT, not this directory, because the harness source lives in
# loadgen/. That differs from docker/tigase/, whose context is its own directory; the compose service
# sets `context: .` with `dockerfile: docker/locust/Dockerfile`. See .dockerignore for what is excluded.
#
# Unlike Gatling — which resolved through Gradle at zero cost to the developer — this image must be
# built once (`docker compose build locust`). That is the one honest regression in the migration, and
# it buys the thing Gatling could not do: change load *while the run is in progress*.

FROM python:3.12-slim

# Pinned, per the repo's never-depend-on-a-rolling-tag convention (cf. mariadb:11.8, tigase 8.4.1).
ARG LOCUST_VERSION=2.32.5

WORKDIR /app

# Install first, copy source second, so editing the harness does not re-run the install layer.
RUN pip install --no-cache-dir "locust==${LOCUST_VERSION}"

COPY loadgen/pyproject.toml /app/pyproject.toml
COPY loadgen/fxc_loadgen /app/fxc_loadgen
COPY loadgen/locustfile.py /app/locustfile.py

# Locust's web UI. Unclaimed elsewhere in FXC (8081 is reserved for the Mastodon gateway).
EXPOSE 8089

# Every option below can be overridden by a LOCUST_* environment variable or on the command line.
#
# --autostart is OFF: the harness comes up idle so adding load is a deliberate act you can show, the
# same reason the exchange now boots with the market halted. Users, spawn rate and the mix shares still
# prefill the form, so starting is one click at http://localhost:8089 (or one POST /swarm). Set
# LOCUST_AUTOSTART=true for an unattended run — `scripts/demo.sh --batch` starts the swarm itself.
ENV LOCUST_LOCUSTFILE=/app/locustfile.py \
    LOCUST_HOST=http://host.docker.internal:8082 \
    LOCUST_WEB_HOST=0.0.0.0 \
    LOCUST_AUTOSTART=false \
    LOCUST_USERS=64 \
    LOCUST_SPAWN_RATE=2 \
    PYTHONUNBUFFERED=1

CMD ["locust"]
