# syntax=docker/dockerfile:1
#
# The FIX adapter (Design.md §7), as most-exchange's JVM images are built: from the host's
# installDist, so `./gradlew :adapter:installDist` comes first. No Gradle and no GitHub Packages
# token inside Docker; CI runs the same two steps.
#
# The adapter brings its own media drivers, embedded (Design.md §3.5): one for order entry's
# Artio engine and the SDK, one for market data's engine (§4). It shares no Aeron directory with
# any other container, which is why it can join the exchange's network over UDP alone.

FROM eclipse-temurin:21-jre-jammy

COPY adapter/build/install/adapter /opt/fix/adapter
ENV PATH="/opt/fix/adapter/bin:${PATH}"

# Same uid as most-exchange's images. /data is order entry's (the fsynced archive, Artio's logs,
# the order store); /data-md is market data's, emptied at every start. Created and owned here so a
# named volume mounted on them inherits the owner.
RUN useradd --system --uid 10001 most \
 && mkdir -p /data /data-md \
 && chown most /data /data-md
USER most

# The start script carries the --add-opens flags and -Dfix.codecs.reject_unknown_field=true;
# ADAPTER_OPTS adds to them.
ENTRYPOINT ["adapter"]
CMD ["/config/adapter.properties"]
