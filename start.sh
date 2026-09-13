#!/usr/bin/env bash
#
# kubastion - start everything with one command (macOS / Linux).
#
# Starts the backend, starts the frontend dev server, waits until both actually
# answer, then opens the browser. Ctrl+C stops both.
#
# Only Java 21+ and Node 20+ are required: Maven comes from the wrapper
# committed in backend/, and npm install runs by itself the first time.
#
# Usage:  ./start.sh            BACKEND_PORT=8080 FRONTEND_PORT=4200 ./start.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-4200}"
OPEN_BROWSER="${OPEN_BROWSER:-1}"

backend_pid=""
frontend_pid=""

say()  { printf '  %s\n' "$1"; }
fail() { printf '\n  \033[31mx %s\033[0m\n\n' "$1" >&2; exit 1; }

# "localhost" rather than 127.0.0.1: the dev server may bind ::1 only, and
# checking a single family would miss a process that is in fact listening.
port_open() {
    (exec 3<>"/dev/tcp/localhost/$1") 2>/dev/null && return 0
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

wait_port() {
    local port="$1" seconds="$2" waited=0
    while [ "$waited" -lt "$seconds" ]; do
        if port_open "$port"; then return 0; fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# Kill the whole process group: mvnw and npm each spawn the process that really
# holds the port, so killing only the launcher would leave it running.
cleanup() {
    trap - EXIT INT TERM
    for pid in "$frontend_pid" "$backend_pid"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
        fi
    done
    say "stopped."
}
trap cleanup EXIT INT TERM

printf '\n  \033[36mkubastion\033[0m\n\n'

# --- prerequisites -----------------------------------------------------------
command -v java >/dev/null 2>&1 || fail "Java 21+ not found on PATH. Install a JDK from https://adoptium.net"
java_major="$(java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
if [ -n "$java_major" ] && [ "$java_major" -lt 21 ]; then
    fail "Java $java_major found, but 21 or newer is required. See https://adoptium.net"
fi
command -v node >/dev/null 2>&1 || fail "Node.js 20+ not found on PATH. Install it from https://nodejs.org"
command -v npm  >/dev/null 2>&1 || fail "npm not found on PATH, although node is installed."

port_open "$BACKEND_PORT"  && fail "Port $BACKEND_PORT is already in use. Is kubastion already running?"
port_open "$FRONTEND_PORT" && fail "Port $FRONTEND_PORT is already in use. Close what is using it, or set FRONTEND_PORT."

# --- first-run setup ---------------------------------------------------------
if [ ! -f "$ROOT/config.yml" ]; then
    cp "$ROOT/config.example.yml" "$ROOT/config.yml"
    printf '  \033[33mconfig.yml created from the example - set your namespace in it.\033[0m\n'
fi

if [ ! -d "$ROOT/frontend/node_modules" ]; then
    say "installing frontend dependencies (first run only, a few minutes)..."
    (cd "$ROOT/frontend" && npm install --no-fund --no-audit) || fail "npm install failed."
fi

mkdir -p "$ROOT/.logs"

# --- backend -----------------------------------------------------------------
say "starting backend  on port $BACKEND_PORT ..."
( cd "$ROOT/backend" && setsid ./mvnw spring-boot:run \
    "-Dspring-boot.run.arguments=--server.port=$BACKEND_PORT" \
    >"$ROOT/.logs/backend.log" 2>&1 ) &
backend_pid=$!

wait_port "$BACKEND_PORT" 180 || fail "The backend did not come up. See .logs/backend.log"
printf '  \033[32mbackend  ready\033[0m\n'

# --- frontend ----------------------------------------------------------------
say "starting frontend on port $FRONTEND_PORT ..."
( cd "$ROOT/frontend" && setsid npm start -- --port "$FRONTEND_PORT" \
    >"$ROOT/.logs/frontend.log" 2>&1 ) &
frontend_pid=$!

wait_port "$FRONTEND_PORT" 240 || fail "The frontend did not come up. See .logs/frontend.log"
printf '  \033[32mfrontend ready\033[0m\n'

URL="http://localhost:$FRONTEND_PORT"
if [ "$OPEN_BROWSER" = "1" ]; then
    if command -v open >/dev/null 2>&1; then open "$URL" >/dev/null 2>&1 || true
    elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL" >/dev/null 2>&1 || true
    fi
fi

printf '\n  \033[36mkubastion is running at %s\033[0m\n' "$URL"
say "Log in through the terminal as you always do, then press Start monitoring."
say "Logs: .logs/backend.log and .logs/frontend.log - Ctrl+C here stops both."
printf '\n'

# Stay in the foreground so Ctrl+C reaches the trap above.
while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do
    sleep 1
done
