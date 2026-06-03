FROM golang:1.25 AS build
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 go build -o /out/adapter .

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/adapter /adapter
EXPOSE 9095
ENTRYPOINT ["/adapter"]
