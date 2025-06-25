FROM ghcr.io/spinalhdl/docker:master AS base

ARG JAVA_EXTRA_OPTS="-Xmx2g -Xms2g"
ENV JAVA_OPTS="${JAVA_OPTS} ${JAVA_EXTRA_OPTS}"

FROM base AS builder

RUN git clone https://github.com/jaynerlin/SpinalHDL.git && \ 
    cd SpinalHDL && \
    git checkout my_fix && \
    git submodule update --init --recursive && \
    sbt +clean +reload +compile && \
    sbt +publishLocal

FROM base AS run

COPY --from=builder /opt /opt
COPY --from=builder /sbt /sbt