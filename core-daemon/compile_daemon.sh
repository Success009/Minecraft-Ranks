#!/bin/bash
# Multi-Platform Go Daemon Compiler for P2P-PvP-Framework
# Compiles optimized native executables for Windows, Linux, and macOS,
# placing them inside the Fabric mod resource asset directories.

set -e

DAEMON_SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_BIN_DIR="${DAEMON_SRC_DIR}/../fabric-bridge/src/main/resources/assets/p2ppvp/bin"

echo "=== Go Daemon Multi-Platform Compiler ==="
echo "Source Dir: ${DAEMON_SRC_DIR}"
echo "Target Bin Dir: ${TARGET_BIN_DIR}"
echo ""

mkdir -p "${TARGET_BIN_DIR}"

# Crucial: Change directory into the Go module folder so go build finds go.mod
cd "${DAEMON_SRC_DIR}"

echo "Building for Linux AMD64 (Executable Fallback)..."
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o "${TARGET_BIN_DIR}/core-daemon-linux-amd64" main.go

echo "Building for Linux AMD64 (C-Shared Library)..."
CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -ldflags="-s -w" -o "${TARGET_BIN_DIR}/libcore-daemon-linux-amd64.so" main.go

echo "Building for Windows AMD64 (Executable Fallback)..."
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -ldflags="-s -w" -o "${TARGET_BIN_DIR}/core-daemon-windows-amd64.exe" main.go

echo "Building for Windows AMD64 (C-Shared Library)..."
CGO_ENABLED=1 GOOS=windows GOARCH=amd64 CC=x86_64-w64-mingw32-gcc go build -buildmode=c-shared -ldflags="-s -w" -o "${TARGET_BIN_DIR}/core-daemon-windows-amd64.dll" main.go

echo "Building for macOS AMD64 (Executable Fallback)..."
CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -ldflags="-s -w" -o "${TARGET_BIN_DIR}/core-daemon-darwin-amd64" main.go

echo "=== Compilation Complete ==="
ls -la "${TARGET_BIN_DIR}"