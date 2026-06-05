FROM golang:1.25 AS build
WORKDIR /src
COPY . .
ARG VERSION=0.1.0-SNAPSHOT
ARG GIT_SHA=dev
ARG BUILD_TIME=unknown
RUN CGO_ENABLED=0 go build -ldflags "-X main.version=${VERSION} -X main.commit=${GIT_SHA} -X main.buildTime=${BUILD_TIME}" -o /out/adapter .

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/adapter /adapter
EXPOSE 9096
ENTRYPOINT ["/adapter"]
