#!/usr/bin/env bash
# Agent-facing wrapper around the Compose Driver harness (patcher-app/tools/compose-driver): renders one composable headlessly (Robolectric)
# and drives it over HTTP. Run `patcher-app/tools/compose-driver/compose-driver.sh help` for usage.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${COMPOSE_DRIVER_PORT:-8137}"
BASE="http://localhost:$PORT"
OUT="$ROOT/build/compose-driver"
SHOTS="$OUT/screenshots"
LOG="$OUT/server.log"
PIDFILE="$OUT/gradle.pid"
START_TIMEOUT="${COMPOSE_DRIVER_START_TIMEOUT:-900}"
# Extra Gradle arguments, e.g. COMPOSE_DRIVER_GRADLE_ARGS="--max-workers=2" on a loaded host.
read -r -a GRADLE_ARGS <<<"${COMPOSE_DRIVER_GRADLE_ARGS:-}"

usage() {
    cat <<EOF
Usage: patcher-app/tools/compose-driver/compose-driver.sh <command> [args]

Server
  list                          List renderable previews (app/src/debug/**/*DriverPreviews.kt)
  start <composable> [opts]     Build and serve a composable; returns once it is ready
        --dark                  Night mode (Robolectric qualifier night)
        --qualifiers <q>        Robolectric qualifiers layered on the default, e.g. w360dp-h640dp
        --font-scale <f>        Font scale, e.g. 1.3
  status                        Print "ok" when the server is ready
  reset [composable]            Recreate the UI state, optionally switching composable
  stop                          Stop the server and its Gradle build

Observe
  tree [selector]               Semantics tree as text
  shot [name] [selector]        Save a PNG to build/compose-driver/screenshots/<name>.png

Act (selector picks the node; without one the root is used)
  click | longclick | doubleclick | scrollto | waitfor [selector]
  type <text> [selector]        Type into a text field
  replace <text> [selector]     Replace a text field's content
  clear [selector]              Clear a text field
  swipe <UP|DOWN|LEFT|RIGHT> [selector]
  back                          System back
  idle                          Wait for the UI to be idle
  call <endpoint> [k=v ...]     Any other Compose Driver endpoint, e.g. call keyEvent key=Enter

Selectors: tag=<testTag>  text=<text>  substring  ignorecase  gif=<ms>
<composable> is a fully qualified name (pkg.FileKt.Function) or just the function name of a
preview listed by "list".

Environment: COMPOSE_DRIVER_PORT (default 8137), COMPOSE_DRIVER_GRADLE_ARGS, COMPOSE_DRIVER_START_TIMEOUT
EOF
}

die() { echo "compose-driver: $*" >&2; exit 1; }

# Turns selector words into curl --data-urlencode arguments.
selector_args() {
    local arg
    for arg in "$@"; do
        case "$arg" in
            tag=*) printf '%s\0' --data-urlencode "nodeTag=${arg#tag=}" ;;
            text=*) printf '%s\0' --data-urlencode "nodeText=${arg#text=}" ;;
            substring) printf '%s\0' --data-urlencode "nodeTextSubstring=true" ;;
            ignorecase) printf '%s\0' --data-urlencode "nodeTextIgnoreCase=true" ;;
            gif=*) printf '%s\0' --data-urlencode "gifDurationMs=${arg#gif=}" ;;
            *=*) printf '%s\0' --data-urlencode "$arg" ;;
            *) die "unknown selector '$arg' (use tag=, text=, substring, ignorecase, gif=)" ;;
        esac
    done
}

# GET <endpoint> with selector words; prints the response body, fails on HTTP errors.
request() {
    local endpoint="$1"; shift
    local -a args=()
    if (($#)); then mapfile -d '' args < <(selector_args "$@"); fi
    curl -sS --fail-with-body --get "${args[@]}" "$BASE/$endpoint"
    echo
}

# Lists "fully.qualified.FileKt.Function" for every zero-argument preview in debug source sets.
previews() {
    local file pkg cls
    while IFS= read -r file; do
        pkg="$(sed -n 's/^package \(.*\)$/\1/p' "$file" | head -n1)"
        cls="$(basename "$file" .kt)Kt"
        sed -n 's/^fun \([A-Za-z0-9_]*\)().*/\1/p' "$file" | while IFS= read -r fn; do
            echo "$pkg.$cls.$fn"
        done
    done < <(find "$ROOT/app/src/debug" -name "*DriverPreviews.kt" 2>/dev/null | sort)
}

resolve() {
    local name="$1" match
    if [[ "$name" == *.*Kt.* ]]; then echo "$name"; return; fi
    match="$(previews | grep -E "\.${name}\$" || true)"
    [[ -n "$match" ]] || die "no preview named '$name'; run: patcher-app/tools/compose-driver/compose-driver.sh list"
    [[ "$(wc -l <<<"$match")" -eq 1 ]] || die "ambiguous name '$name':"$'\n'"$match"
    echo "$match"
}

listener_pid() {
    ss -ltnpH "sport = :$PORT" 2>/dev/null | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -n1
}

is_ready() { [[ "$(curl -s --max-time 2 "$BASE/status" 2>/dev/null)" == ok ]]; }

stop() {
    local pid
    pid="$(listener_pid)"
    # Only kill the listener when it is our Robolectric test worker, never an unrelated server.
    if [[ -n "$pid" ]] && tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -q "Gradle Test Executor"; then
        kill "$pid" 2>/dev/null || true
    fi
    if [[ -f "$PIDFILE" ]]; then
        kill "$(cat "$PIDFILE")" 2>/dev/null || true
        rm -f "$PIDFILE"
    fi
    for _ in $(seq 1 20); do [[ -z "$(listener_pid)" ]] && break; sleep 0.5; done
}

start() {
    (($#)) || die "start needs a composable; run: patcher-app/tools/compose-driver/compose-driver.sh list"
    local composable qualifiers="" font_scale="" dark=""
    composable="$(resolve "$1")"; shift
    while (($#)); do
        case "$1" in
            --dark) dark=1 ;;
            --qualifiers) qualifiers="${qualifiers:+$qualifiers-}${2#+}"; shift ;;
            --font-scale) font_scale="$2"; shift ;;
            *) die "unknown start option '$1'" ;;
        esac
        shift
    done
    stop
    [[ -z "$(listener_pid)" ]] || die "port $PORT is taken by another process; set COMPOSE_DRIVER_PORT"
    mkdir -p "$OUT" "$SHOTS"
    local -a props=("-PcomposeDriver.composable=$composable" "-PcomposeDriver.port=$PORT" "-Popenshm.composeDriver")
    # Android parses qualifiers in canonical order, where night follows size and orientation.
    [[ -n "$dark" ]] && qualifiers="${qualifiers:+$qualifiers-}night"
    # A leading "+" layers the qualifiers over the default viewport instead of replacing it.
    [[ -n "$qualifiers" ]] && props+=("-PcomposeDriver.qualifiers=+$qualifiers")
    [[ -n "$font_scale" ]] && props+=("-PcomposeDriver.fontScale=$font_scale")
    (cd "$ROOT" && exec nohup ./gradlew :app:testDebugUnitTest \
        --tests '*.ComposeDriverServer' "${props[@]}" "${GRADLE_ARGS[@]}" >"$LOG" 2>&1) &
    echo $! >"$PIDFILE"
    echo "Starting $composable on $BASE (log: $LOG)"
    local waited=0
    until is_ready; do
        if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
            rm -f "$PIDFILE"
            grep -E '^e: |FAILED|What went wrong|Exception' "$LOG" | head -n 20 >&2 || true
            die "server exited before it was ready; see $LOG"
        fi
        ((waited >= START_TIMEOUT)) && die "not ready after ${START_TIMEOUT}s; see $LOG"
        sleep 2; waited=$((waited + 2))
    done
    echo "ready after ${waited}s"
}

shot() {
    local name="screenshot"
    if (($#)) && [[ "$1" != *=* && "$1" != substring && "$1" != ignorecase ]]; then name="$1"; shift; fi
    mkdir -p "$SHOTS"
    local file="$SHOTS/$name.png"
    local -a args=()
    if (($#)); then mapfile -d '' args < <(selector_args "$@"); fi
    curl -sS --fail-with-body --get "${args[@]}" -o "$file" "$BASE/screenshot" || { cat "$file" >&2; echo >&2; exit 1; }
    echo "$file"
}

cmd="${1:-help}"; (($#)) && shift
case "$cmd" in
    list) previews ;;
    start) start "$@" ;;
    status) is_ready && echo ok || { echo "not running"; exit 1; } ;;
    stop) stop; echo stopped ;;
    reset)
        if (($#)); then request reset "composable=$(resolve "$1")"; else request reset; fi ;;
    tree) request printTree "$@" ;;
    shot) shot "$@" ;;
    click) request click "$@" ;;
    longclick) request longClick "$@" ;;
    doubleclick) request doubleClick "$@" ;;
    scrollto) request scrollTo "$@" ;;
    waitfor) request waitForNode "$@" ;;
    idle) request waitForIdle ;;
    back) request navigateBack ;;
    type) (($#)) || die "type needs text"; t="$1"; shift; request textInput "text=$t" "$@" ;;
    replace) (($#)) || die "replace needs text"; t="$1"; shift; request textReplacement "text=$t" "$@" ;;
    clear) request textClearance "$@" ;;
    swipe) (($#)) || die "swipe needs a direction"; d="$1"; shift; request swipe "direction=$d" "$@" ;;
    call) (($#)) || die "call needs an endpoint"; e="$1"; shift; request "$e" "$@" ;;
    help | -h | --help) usage ;;
    *) usage >&2; exit 2 ;;
esac
