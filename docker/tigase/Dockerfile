# Runs the UNMODIFIED Tigase 8.4.1 community distribution (AGPLv3) on a JDK 17 base.
#
# The official `tigase/tigase-xmpp-server:8.4.1` image is a bare JRE skeleton with empty volume
# dirs and no launch script (it does not self-run), and it ships Java 25. This image instead
# fetches the genuine vendor `-dist` tarball and runs it as-is via the shipped `scripts/tigase.sh`.
# Nothing in the distribution is patched — customization is limited to config (config.tdsl) per
# docs/DESIGN.md §4.3, so AGPLv3 constraints are not triggered.
#
# JDK 17 (NOT 21/25): Tigase 8.4.1 bundles a Groovy whose ASM cannot read Java 21+ class files
# ("Unsupported class file major version 65"). Tigase's supported JDK is 17. It runs as a separate
# container, so this is independent of FXC's own Java-21 components (see PROBLEMS.md P5).

FROM eclipse-temurin:17-jre

ARG TIGASE_BUILD=8.4.1-b12419
ENV TIGASE_HOME=/opt/tigase

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates bash \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${TIGASE_HOME}" \
    && curl -fsSL -o /tmp/tigase.tar.gz \
       "https://github.com/tigase/tigase-server/releases/download/tigase-server-8.4.1/tigase-server-${TIGASE_BUILD}-dist.tar.gz" \
    && tar -xzf /tmp/tigase.tar.gz -C "${TIGASE_HOME}" --strip-components=1 \
    && rm /tmp/tigase.tar.gz \
    && chmod +x "${TIGASE_HOME}"/scripts/*.sh

WORKDIR ${TIGASE_HOME}

# FXC configuration (the only server-side input). Baked in (NOT bind-mounted — Tigase rewrites it
# on startup, which fails against a mounted file).
COPY config.tdsl ${TIGASE_HOME}/etc/config.tdsl
COPY entrypoint.sh /entrypoint.sh
COPY init-schema.sh /init-schema.sh
RUN chmod +x /entrypoint.sh /init-schema.sh

EXPOSE 5222 5269 5280 8080

# Default: run the server in the foreground. The tigase-init compose service overrides the
# entrypoint with /init-schema.sh (one-shot schema load) and must complete first.
CMD ["/entrypoint.sh"]
