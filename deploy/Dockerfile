# BanditBox server: one static Go binary on a distroless base.
# Build from the repo root:  docker build -f deploy/Dockerfile -t banditbox .

# The Go stage runs on the build machine and cross-compiles, so multi-arch
# builds need no emulation.
FROM --platform=$BUILDPLATFORM golang:1.24-alpine AS server
ARG TARGETOS TARGETARCH
ARG VERSION=dev
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
COPY schema/ /src/schema/
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/banditbox ./cmd/banditbox

FROM gcr.io/distroless/static-debian12
COPY --from=server /out/banditbox /usr/local/bin/banditbox
# Unraid's nobody:users. The server starts as root only to give /config to
# this user, then switches to it. Set PUID/PGID empty to stay root.
ENV PUID=99 PGID=100 \
    BANDITBOX_ADDR=:8686 \
    BANDITBOX_DATA=/config
VOLUME /config
EXPOSE 8686/tcp 5353/udp
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD ["/usr/local/bin/banditbox", "healthcheck"]
ENTRYPOINT ["/usr/local/bin/banditbox"]
