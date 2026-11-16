#!/usr/bin/env sh
set -eu
# Usage: ./restore.sh /absolute/state/restore-jobs/JOB/job.properties [--recover]
# This is an OPERATOR fallback. Disable auto-restart and stop the server first.
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo 'Usage: restore.sh /absolute/job.properties [--recover]' >&2; exit 2
fi
if [ "$#" -eq 2 ] && [ "$2" != --recover ]; then
  echo 'Only --recover is accepted as the second argument.' >&2; exit 2
fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# The helper shipped beside this script, unless VAULTBACKUP_HELPER_JAR names another one
# (for example the copy the plugin installed: <state-directory>/helper/vaultbackup-restore.jar).
jar=${VAULTBACKUP_HELPER_JAR:-"$script_dir/vaultbackup-restore.jar"}
if [ ! -f "$jar" ]; then
  printf '%s\n' "復元ヘルパーが見つかりません: $jar" >&2
  printf '%s\n' "Restore helper not found: $jar (set VAULTBACKUP_HELPER_JAR to its path)" >&2
  exit 2
fi
if [ ! -f "$1" ]; then
  printf '%s\n' "ジョブファイルが見つかりません: $1" >&2
  printf '%s\n' "Job file not found: $1" >&2
  exit 2
fi
java=${JAVA_HOME:+"$JAVA_HOME/bin/"}java
# Archive names are UTF-8. Without a UTF-8 locale Java cannot create files with non-ASCII names.
charmap=$(locale charmap 2>/dev/null || true)
if [ "$charmap" != UTF-8 ] && locale -a 2>/dev/null | grep -qiE '^C\.utf-?8$'; then
  LC_ALL=C.UTF-8; export LC_ALL
fi
printf '%s\n' '自動再起動を無効にし、サーバー JVM が完全に停止していることを確認してください。'
printf '%s\n' 'Disable hosting auto-restart/watchdogs. Confirm the server JVM is completely stopped.'
# Invocation is the operator's explicit request; the helper still verifies shutdown/locks.
if [ "$#" -eq 2 ]; then
  exec "$java" -jar "$jar" --job "$1" --recover --i-confirm-server-stopped
else
  exec "$java" -jar "$jar" --job "$1" --manual --i-confirm-server-stopped
fi
