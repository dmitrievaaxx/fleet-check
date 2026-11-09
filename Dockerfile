FROM golang:1.25 AS build
# Откуда скачивать модули; по умолчанию — публичное зеркало Go.
ARG GOPROXY=https://proxy.golang.org,direct
ARG GOSUMDB=sum.golang.org
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/server ./cmd/server

FROM debian:bookworm-slim
COPY --from=build /out/server /usr/local/bin/server
ENV PORT=8000
CMD ["server"]
