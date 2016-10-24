FROM alpine:3.4
COPY ["target/universal/pulptunes-relay-2.0.0-SNAPSHOT.zip", "/"]
RUN unzip pulptunes-relay-2.0.0-SNAPSHOT.zip
MAINTAINER Alejandro Pedraza "alejandro.pedraza@gmail.com"
ENV PULPTUNES_DB_HOST=192.168.0.11 \
    PULPTUNES_DB_PASSWORD=
RUN apk --update add openjdk8-jre && \
    apk add bash
EXPOSE 9000
CMD ["pulptunes-relay-2.0.0-SNAPSHOT/bin/pulptunes-relay"]
