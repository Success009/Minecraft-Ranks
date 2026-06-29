#!/bin/bash
# Multi-Platform Fabric Mod Builder for P2P-PvP-Framework
# Compiles separate, optimized, smaller JARs for Windows, Linux, and macOS.

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="${PROJECT_ROOT}/fabric-bridge/src/main/resources/assets/p2ppvp/bin"
TEMP_BACKUP_DIR="${PROJECT_ROOT}/native_backup"
GRADLE_PROPS="${PROJECT_ROOT}/fabric-bridge/gradle.properties"
CURRENT_VER=$(grep "mod_version=" "${GRADLE_PROPS}" | cut -d'=' -f2 | xargs)

# Smart regex to detect if a compilation counter exists and increment it
if [[ "$CURRENT_VER" =~ beta\.[0-9]+$ ]]; then
    NEW_VER="${CURRENT_VER}.1"
elif [[ "$CURRENT_VER" =~ beta\.[0-9]+\.([0-9]+)$ ]]; then
    LAST_NUM="${BASH_REMATCH[1]}"
    NEXT_NUM=$((LAST_NUM + 1))
    BASE_VER="${CURRENT_VER%.$LAST_NUM}"
    NEW_VER="${BASE_VER}.${NEXT_NUM}"
else
    if [[ "$CURRENT_VER" =~ \.([0-9]+)$ ]]; then
        LAST_NUM="${BASH_REMATCH[1]}"
        NEXT_NUM=$((LAST_NUM + 1))
        BASE_VER="${CURRENT_VER%.$LAST_NUM}"
        NEW_VER="${BASE_VER}.${NEXT_NUM}"
    else
        NEW_VER="${CURRENT_VER}.1"
    fi
fi

# Update gradle.properties using python
python3 -c "
path = '${GRADLE_PROPS}'
with open(path, 'r') as f:
    lines = f.readlines()
with open(path, 'w') as f:
    for line in lines:
        if line.startswith('mod_version='):
            f.write('mod_version=${NEW_VER}\n')
        else:
            f.write(line)
"

MOD_VERSION="${NEW_VER}"


echo "=== MCR MULTI-PLATFORM BUILDER ==="
echo "Mod Version: ${MOD_VERSION}"
echo "Project Root: ${PROJECT_ROOT}"
echo ""

# Clean up old compiled files from target directories to prevent clutter
echo "Cleaning up old compiled builds..."
rm -f "${PROJECT_ROOT}/mods/"[Mm][Cc][Rr]-*.jar
rm -f "/home/success0/.minecraft/mods/"[Mm][Cc][Rr]-*.jar
rm -f "/mnt/data_vault/.minecraft/mods/"[Mm][Cc][Rr]-*.jar

# Ensure native binaries are built fresh
echo "Compiling native Go daemons..."
"${PROJECT_ROOT}/core-daemon/compile_daemon.sh"

# Back up original native files
echo "Backing up native binaries..."
mkdir -p "${TEMP_BACKUP_DIR}"
cp -r "${BIN_DIR}/"* "${TEMP_BACKUP_DIR}/"

# Function to build for a single platform
build_for_platform() {
    local platform=$1
    local final_jar="MCR-${MOD_VERSION}-${platform}.jar"

    echo ""
    echo "================================================"
    echo "Building optimized JAR for platform: ${platform}"
    echo "================================================"

    # Clean bin directory and copy only the platform-specific files
    rm -rf "${BIN_DIR}"
    mkdir -p "${BIN_DIR}"

    if [ "${platform}" = "windows" ]; then
        cp "${TEMP_BACKUP_DIR}/core-daemon-windows-amd64.exe" "${BIN_DIR}/"
        cp "${TEMP_BACKUP_DIR}/core-daemon-windows-amd64.dll" "${BIN_DIR}/"
    elif [ "${platform}" = "linux" ]; then
        cp "${TEMP_BACKUP_DIR}/core-daemon-linux-amd64" "${BIN_DIR}/"
        cp "${TEMP_BACKUP_DIR}/libcore-daemon-linux-amd64.so" "${BIN_DIR}/"
    elif [ "${platform}" = "mac" ]; then
        cp "${TEMP_BACKUP_DIR}/core-daemon-darwin-amd64" "${BIN_DIR}/"
    else
        echo "ERROR: Unknown platform ${platform}!"
        return 1
    fi

    # Run Gradle build
    cd "${PROJECT_ROOT}/fabric-bridge"
    ./gradlew clean build

    # Move output JAR to target mods directory
    mkdir -p "${PROJECT_ROOT}/mods"
    if [ -f "build/libs/mcr-${MOD_VERSION}.jar" ]; then
        mv "build/libs/mcr-${MOD_VERSION}.jar" "${PROJECT_ROOT}/mods/${final_jar}"
        echo "SUCCESS: Created ${PROJECT_ROOT}/mods/${final_jar}"
        ls -lh "${PROJECT_ROOT}/mods/${final_jar}"

        # Automatically copy the Linux build to the local testing mods folder
        if [ "${platform}" = "linux" ]; then
            echo "Copying Linux build to local testing mods directory..."
            cp "${PROJECT_ROOT}/mods/${final_jar}" "/home/success0/.minecraft/mods/"
            cp "${PROJECT_ROOT}/mods/${final_jar}" "/mnt/data_vault/.minecraft/mods/MCR-${MOD_VERSION}-linux.jar"
        fi
    else
        echo "ERROR: Compiled JAR not found!"
        exit 1
    fi
}
build_for_platform "windows"

# Build for Linux
build_for_platform "linux"

# Build for Mac
build_for_platform "mac"

# Restore original binaries to the workspace
echo ""
echo "Restoring original development binaries..."
rm -rf "${BIN_DIR}"
mkdir -p "${BIN_DIR}"
cp -r "${TEMP_BACKUP_DIR}/"* "${BIN_DIR}/"
rm -rf "${TEMP_BACKUP_DIR}"
echo ""
echo "=== RELEASING TO GITHUB ==="
cd "${PROJECT_ROOT}"
git add .
git commit -m "Auto-update compiled binaries for release ${MOD_VERSION}" || echo "No changes to commit."
git push origin main || echo "Failed to push to GitHub, but proceeding with release creation."

echo "Clearing pre-existing release if applicable..."
gh release delete "${MOD_VERSION}" --yes || true

echo "Creating GitHub Release ${MOD_VERSION}..."
gh release create "${MOD_VERSION}" \
    "${PROJECT_ROOT}/mods/MCR-${MOD_VERSION}-windows.jar" \
    "${PROJECT_ROOT}/mods/MCR-${MOD_VERSION}-linux.jar" \
    "${PROJECT_ROOT}/mods/MCR-${MOD_VERSION}-mac.jar" \
    --title "${MOD_VERSION}" \
    --notes "Minecraft Ranks (MCR) Release ${MOD_VERSION} - Silently self-updating platform JARs."

echo ""
echo "=== MULTI-PLATFORM BUILD & RELEASE COMPLETE ==="
ls -lh "${PROJECT_ROOT}/mods"
