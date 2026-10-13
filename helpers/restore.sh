#!/usr/bin/env sh
set -eu
# Usage: ./restore.sh /absolute/state/restore-jobs/JOB/job.properties [--recover]
# This is an OPERATOR fallback. Disable auto-restart and stop the server first.
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo 'Usage: restore.sh /absolute/job.properties [--recover]' >&2; exit 2
fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
jar=${VAULTBACKUP_HELPER_JAR:-"$script_dir/vaultbackup-restore-1.2.0.jar"}
java=${JAVA_HOME:+"$JAVA_HOME/bin/"}java
printf '%s\n' '自動再起動を無効にし、サーバー JVM が完全に停止していることを確認してください。'
printf '%s\n' 'Disable hosting auto-restart/watchdogs. Confirm the server JVM is completely stopped.'
# Invocation is the operator's explicit request; the helper still verifies shutdown/locks.
if [ "${2:-}" = --recover ]; then
  exec "$java" -jar "$jar" --job "$1" --recover --i-confirm-server-stopped
elif [ "$#" -eq 1 ]; then
  exec "$java" -jar "$jar" --job "$1" --manual --i-confirm-server-stopped
else
  echo 'Only --recover is accepted as the second argument.' >&2; exit 2
fi
