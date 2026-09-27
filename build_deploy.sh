#!/bin/bash
set -e

# Minecraft Ranks (MCR) Mod - GitHub Actions Cloud Build & Publish System
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR_INSTANCE="/home/success0/.minecraft/instances/fabric_26.1.2/mods"
TARGET_DIR_HOME="/home/success0/.minecraft/mods"
TARGET_DIR_VAULT="/mnt/data_vault/.minecraft/mods"
OUTPUT_DIR="${PROJECT_ROOT}/mods"
REPO_OWNER="Success009"
REPO_NAME="Minecraft-Ranks"
GITHUB_REPO="${REPO_OWNER}/${REPO_NAME}"
GRADLE_PROPS="${PROJECT_ROOT}/fabric-bridge/gradle.properties"

print_usage() {
    echo "================================================================="
    echo "Minecraft Ranks (MCR) Cloud-Offloaded Build & Deploy System"
    echo "================================================================="
    echo "Usage: $0 [options]"
    echo ""
    echo "Options / Flags:"
    echo "  -c, --check             Verify Java syntax and compile via GitHub Actions"
    echo "  -d, --deploy            Build Linux JAR via GitHub Actions & deploy to local test folders"
    echo "  -p, --publish           Build multiplatform JARs via GitHub Actions & publish to GitHub Releases"
    echo "  -s, --skip-compile      Skip build triggering (uses existing release artifacts)"
    echo "  --no-bump               Skip automatic version bumping"
    echo "  --skip-tests            Skip python3 test_all.py validation suite"
    echo ""
    echo "Native Go Daemon Cross-Compilation Options:"
    echo "  --build-go-linux        Trigger cloud compilation of Linux Go daemon"
    echo "  --build-go-windows      Trigger cloud cross-compilation of Windows Go daemon"
    echo "  --build-go-mac          Trigger cloud compilation of macOS Go daemon"
    echo "  --build-go-all          Trigger cloud compilation of all Go daemons"
    echo ""
    echo "Target Platform Selectors (Used with --publish):"
    echo "  --linux                 Limit cloud build to Linux only"
    echo "  --windows               Limit cloud build to Windows only"
    echo "  --mac                   Limit cloud build to macOS only"
    echo ""
    echo "Legacy / Positional Commands:"
    echo "  check                   Equivalent to --check"
    echo "  deploy                  Equivalent to --deploy"
    echo "  publish                 Equivalent to --publish"
    echo "  all                     Equivalent to --deploy followed by --publish"
    echo "  build                   Triggers cloud build and downloads artifacts"
    echo "================================================================="
}

CHECK=false
DEPLOY=false
PUBLISH=false
SKIP_COMPILE=false
NO_BUMP=false
SKIP_TESTS=false

BUILD_GO_LINUX=false
BUILD_GO_WINDOWS=false
BUILD_GO_MAC=false

COMPILE_LINUX=false
COMPILE_WINDOWS=false
COMPILE_MAC=false

if [ "$1" == "check" ]; then
    CHECK=true
elif [ "$1" == "deploy" ]; then
    DEPLOY=true
elif [ "$1" == "publish" ]; then
    PUBLISH=true
elif [ "$1" == "all" ]; then
    DEPLOY=true
    PUBLISH=true
elif [ "$1" == "build" ]; then
    DEPLOY=true
elif [ -z "$1" ]; then
    print_usage
    exit 1
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            print_usage
            exit 0
            ;;
        -c|--check|check)
            CHECK=true
            shift
            ;;
        -d|--deploy|deploy)
            DEPLOY=true
            shift
            ;;
        -p|--publish|publish)
            PUBLISH=true
            shift
            ;;
        -s|--skip-compile)
            SKIP_COMPILE=true
            shift
            ;;
        --no-bump)
            NO_BUMP=true
            shift
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        all)
            DEPLOY=true
            PUBLISH=true
            shift
            ;;
        --build-go-linux)
            BUILD_GO_LINUX=true
            shift
            ;;
        --build-go-windows)
            BUILD_GO_WINDOWS=true
            shift
            ;;
        --build-go-mac)
            BUILD_GO_MAC=true
            shift
            ;;
        --build-go-all)
            BUILD_GO_LINUX=true
            BUILD_GO_WINDOWS=true
            BUILD_GO_MAC=true
            shift
            ;;
        --linux)
            COMPILE_LINUX=true
            shift
            ;;
        --windows)
            COMPILE_WINDOWS=true
            shift
            ;;
        --mac)
            COMPILE_MAC=true
            shift
            ;;
        *)
            if [ "$1" == "build" ]; then
                shift
            else
                echo "ERROR: Unknown option '$1'"
                print_usage
                exit 1
            fi
            ;;
    esac
done

# 1. Run local validation tests before cloud trigger
if [ "$SKIP_TESTS" != true ]; then
    echo "=== Running pre-build validation tests ==="
    python3 "${PROJECT_ROOT}/test_all.py"
    echo ""
fi

# 2. Resolve version
CURRENT_VER=$(grep "mod_version=" "${GRADLE_PROPS}" | cut -d'=' -f2 | xargs)

if [ "$CHECK" != true ] && [ "$SKIP_COMPILE" != true ] && [ "$NO_BUMP" != true ]; then
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
    VERSION="${NEW_VER}"
    echo "Bumping version from ${CURRENT_VER} to ${VERSION}"
else
    VERSION="${CURRENT_VER}"
    echo "Using version: ${VERSION}"
fi

# Determine workflow mode
if [ "$CHECK" = true ]; then
    MODE="check"
elif [ "$PUBLISH" = true ]; then
    MODE="publish"
else
    MODE="deploy"
fi

# Determine platform targets
IF_ANY_PLATFORM=false
if [ "$COMPILE_LINUX" = true ] || [ "$COMPILE_WINDOWS" = true ] || [ "$COMPILE_MAC" = true ]; then
    IF_ANY_PLATFORM=true
fi

if [ "$IF_ANY_PLATFORM" = false ]; then
    if [ "$MODE" = "deploy" ]; then
        COMPILE_LINUX=true
        COMPILE_WINDOWS=false
        COMPILE_MAC=false
    else
        COMPILE_LINUX=true
        COMPILE_WINDOWS=true
        COMPILE_MAC=true
    fi
fi

echo "=================================================="
echo "Minecraft Ranks (MCR) Cloud Build"
echo "Target Repository: ${GITHUB_REPO}"
echo "Assigned Version:  ${VERSION}"
echo "Execution Mode:    ${MODE}"
echo "Targets: Linux=${COMPILE_LINUX}, Windows=${COMPILE_WINDOWS}, Mac=${COMPILE_MAC}"
echo "=================================================="

# 3. Push clean source state to GitHub via sync cache
if [ "$SKIP_COMPILE" != true ]; then
    echo "Syncing repository source to GitHub..."
    SYNC_DIR="${PROJECT_ROOT}/.git_sync_cache"
    if [ ! -d "$SYNC_DIR/.git" ]; then
        rm -rf "$SYNC_DIR"
        mkdir -p "$SYNC_DIR"
        cd "$SYNC_DIR"
        git init > /dev/null 2>&1
        git remote add origin "https://github.com/${GITHUB_REPO}.git"
        git branch -M main
        cd "${PROJECT_ROOT}"
    fi

    # Clean previous synced source
    rm -rf "$SYNC_DIR"/.github "$SYNC_DIR"/core-daemon "$SYNC_DIR"/fabric-bridge "$SYNC_DIR"/matchmaking-server "$SYNC_DIR"/protocol-shared "$SYNC_DIR"/docs

    # Copy current source files
    cp -r "${PROJECT_ROOT}/.github" "${PROJECT_ROOT}/core-daemon" "${PROJECT_ROOT}/fabric-bridge" "${PROJECT_ROOT}/matchmaking-server" "${PROJECT_ROOT}/protocol-shared" "${PROJECT_ROOT}/docs" "$SYNC_DIR"/
    cp "${PROJECT_ROOT}/test_all.py" "${PROJECT_ROOT}/README.md" "${PROJECT_ROOT}/ARCHITECTURE.md" "${PROJECT_ROOT}/FUTURE_PLAN.md" "${PROJECT_ROOT}/deploy_server.sh" "${PROJECT_ROOT}/tailscale_acl_production.json" "${PROJECT_ROOT}/.gitignore" "$SYNC_DIR"/ 2>/dev/null || true

    # Clean local cache directories from sync copy
    rm -rf "$SYNC_DIR"/fabric-bridge/build "$SYNC_DIR"/fabric-bridge/.gradle "$SYNC_DIR"/fabric-bridge/.loom "$SYNC_DIR"/fabric-bridge/run "$SYNC_DIR"/fabric-bridge/src/main/resources/assets/p2ppvp/bin

    cd "$SYNC_DIR"
    git add -A
    if ! git diff --cached --quiet; then
        git commit -m "Cloud Build Sync v${VERSION} [mode: ${MODE}]" > /dev/null 2>&1 || true
    fi
    echo "Pushing clean source to origin main..."
    GIT_TERMINAL_PROMPT=0 timeout 25s git push -u origin main --force > /dev/null 2>&1 || GIT_TERMINAL_PROMPT=0 timeout 25s git push -u origin main --force
    cd "${PROJECT_ROOT}"

    # 4. Trigger GitHub Actions Workflow
    echo "Triggering cloud build workflow on GitHub Actions..."
    gh workflow run build.yml \
        --repo "$GITHUB_REPO" \
        --ref main \
        -f version="${VERSION}" \
        -f mode="${MODE}" \
        -f compile_linux="${COMPILE_LINUX}" \
        -f compile_windows="${COMPILE_WINDOWS}" \
        -f compile_mac="${COMPILE_MAC}" \
        -f build_go_linux="${BUILD_GO_LINUX}" \
        -f build_go_windows="${BUILD_GO_WINDOWS}" \
        -f build_go_mac="${BUILD_GO_MAC}"

    # Wait for workflow run to register
    sleep 3
    RUN_ID=$(gh run list --workflow=build.yml --repo "$GITHUB_REPO" --limit 1 --json databaseId --jq '.[0].databaseId')

    if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
        echo "ERROR: Failed to retrieve active GitHub Actions run ID."
        exit 1
    fi
    echo "Offloading build execution to GitHub Actions (Run ID: ${RUN_ID})..."
    echo "Streaming cloud build execution..."
    echo "--------------------------------------------------"

    # Stream live progress
    gh run watch "$RUN_ID" --repo "$GITHUB_REPO" || true

    echo "--------------------------------------------------"
    echo "Cloud Build Logs Summary:"
    echo "--------------------------------------------------"
    gh run view "$RUN_ID" --repo "$GITHUB_REPO" --log | grep -E "Task :|SUCCESS:|Building Master|BUILD SUCCESSFUL|BUILD FAILED|error:|ERROR:" || true
    echo "--------------------------------------------------"

    CONCLUSION=$(gh run view "$RUN_ID" --repo "$GITHUB_REPO" --json conclusion --jq .conclusion)

    if [ "$CONCLUSION" != "success" ]; then
        echo ""
        echo "=================================================="
        echo "ERROR: Cloud Build Failed (Conclusion: ${CONCLUSION})"
        echo "=================================================="
        gh run view "$RUN_ID" --repo "$GITHUB_REPO" --log-failed
        exit 1
    fi

    echo ""
    echo "SUCCESS: Cloud build completed successfully!"
fi

mkdir -p "$OUTPUT_DIR"

if [ "$MODE" = "check" ]; then
    echo "=================================================="
    echo "Java Syntax & Compilation Check Passed on Cloud!"
    echo "=================================================="
    exit 0
fi

echo "Retrieving compiled artifacts..."
mkdir -p "$OUTPUT_DIR"

if [ "$PUBLISH" = true ]; then
    gh release download "${VERSION}" --repo "$GITHUB_REPO" --dir "$OUTPUT_DIR" --clobber 2>/dev/null || true
fi

# Artifact download fallback
if [ ! -f "${OUTPUT_DIR}/MCR-${VERSION}-linux.jar" ] && [ -n "$RUN_ID" ]; then
    rm -rf "${PROJECT_ROOT}/temp_artifacts"
    mkdir -p "${PROJECT_ROOT}/temp_artifacts"
    gh run download "$RUN_ID" --repo "$GITHUB_REPO" --dir "${PROJECT_ROOT}/temp_artifacts" > /dev/null 2>&1 || true
    find "${PROJECT_ROOT}/temp_artifacts" -type f -name "MCR-*.jar" -exec cp {} "$OUTPUT_DIR"/ \;
    rm -rf "${PROJECT_ROOT}/temp_artifacts"
fi

DEPLOY_JAR="${OUTPUT_DIR}/MCR-${VERSION}-linux.jar"
if [ ! -f "$DEPLOY_JAR" ]; then
    DEPLOY_JAR=$(find "$OUTPUT_DIR" -name "MCR-*-linux.jar" | head -n 1)
fi

if [ -n "$DEPLOY_JAR" ] && [ -f "$DEPLOY_JAR" ]; then
    echo "Deploying $(basename "$DEPLOY_JAR") to local testing folders and purging old versions..."
    for TARGET in "$TARGET_DIR_INSTANCE" "$TARGET_DIR_HOME" "$TARGET_DIR_VAULT"; do
        if [ -d "$TARGET" ]; then
            mkdir -p "$TARGET"
            rm -f "$TARGET"/[Mm][Cc][Rr]-*.jar
            rm -f "$TARGET"/[Mm][Cc][Rr]-*.jar.bak
            cp "$DEPLOY_JAR" "$TARGET"/
            echo "Deployed $(basename "$DEPLOY_JAR") -> $TARGET"
        fi
    done
fi

echo ""
echo "=================================================="
echo "SUCCESS: Cloud Build & Deployment Complete!"
echo "Version:     ${VERSION}"
echo "Output Path: ${OUTPUT_DIR}/"
ls -lh "${OUTPUT_DIR}"/MCR-*.jar 2>/dev/null || true
if [ "$MODE" = "publish" ]; then
    echo "Release URL: https://github.com/${GITHUB_REPO}/releases/tag/${VERSION}"
fi
echo "=================================================="
