#!/usr/bin/env sh
set -eu

idea_paths_overlap() {
  overlap_first=$1
  overlap_second=$2
  case "$overlap_first" in
    "$overlap_second"|"$overlap_second"/*) return 0 ;;
  esac
  case "$overlap_second" in
    "$overlap_first"/*) return 0 ;;
  esac
  return 1
}

prepare_idea_cache_structural_directory() {
  idea_structural_path=$1
  idea_structural_name=$2
  if [ -L "$idea_structural_path" ] ||
    { [ -e "$idea_structural_path" ] && [ ! -d "$idea_structural_path" ]; }; then
    echo "[run-intellij] refusing unsafe IDEA cache $idea_structural_name directory: $idea_structural_path" >&2
    return 1
  fi
  mkdir -p "$idea_structural_path"
  if [ -L "$idea_structural_path" ] || [ ! -d "$idea_structural_path" ]; then
    echo "[run-intellij] refusing unsafe IDEA cache $idea_structural_name directory after creation: $idea_structural_path" >&2
    return 1
  fi
  idea_structural_resolved=$(realpath -e -- "$idea_structural_path" 2>/dev/null || true)
  case "$idea_structural_resolved" in
    "$idea_cache_root_resolved"/*) return 0 ;;
  esac
  echo "[run-intellij] refusing IDEA cache $idea_structural_name directory outside cache root: $idea_structural_path" >&2
  return 1
}

: "${DISPLAY:=host.docker.internal:0}"
: "${IDEA_CACHE_DIR:=}"
idea_home_was_explicit=false
if [ -n "${IDEA_HOME:-}" ]; then
  idea_home_was_explicit=true
fi
: "${IDEA_HOME:=/opt/idea}"
normalized_idea_home=$(realpath -m -s -- "$IDEA_HOME")
resolved_idea_home=$(realpath -m -- "$normalized_idea_home" 2>/dev/null || true)
effective_idea_home=
if [ -e "$normalized_idea_home" ] || [ -L "$normalized_idea_home" ]; then
  effective_idea_home=$(realpath -e -- "$normalized_idea_home" 2>/dev/null || true)
fi
if [ -z "$normalized_idea_home" ] || [ "$normalized_idea_home" = "/" ] ||
  [ "$resolved_idea_home" = "/" ] || [ "$effective_idea_home" = "/" ]; then
  echo "[run-intellij] refusing unsafe IDEA_HOME: $IDEA_HOME" >&2
  exit 1
fi
IDEA_HOME=$normalized_idea_home
if [ -n "$IDEA_CACHE_DIR" ]; then
  original_idea_cache_dir=$IDEA_CACHE_DIR
  normalized_idea_cache_dir=$(realpath -m -s -- "$IDEA_CACHE_DIR")
  resolved_idea_cache_dir=$(realpath -m -- "$normalized_idea_cache_dir" 2>/dev/null || true)
  effective_idea_cache_dir=
  if [ -e "$normalized_idea_cache_dir" ] || [ -L "$normalized_idea_cache_dir" ]; then
    effective_idea_cache_dir=$(realpath -e -- "$normalized_idea_cache_dir" 2>/dev/null || true)
  fi
  if [ -z "$normalized_idea_cache_dir" ] || [ "$normalized_idea_cache_dir" = "/" ] ||
    [ "$resolved_idea_cache_dir" = "/" ] || [ "$effective_idea_cache_dir" = "/" ]; then
    echo "[run-intellij] refusing unsafe IDEA_CACHE_DIR: $original_idea_cache_dir" >&2
    exit 1
  fi
  IDEA_CACHE_DIR=$normalized_idea_cache_dir
  if [ "$idea_home_was_explicit" = true ]; then
    idea_home_overlap_path=${resolved_idea_home:-$normalized_idea_home}
    idea_cache_overlap_path=${resolved_idea_cache_dir:-$normalized_idea_cache_dir}
    if idea_paths_overlap "$normalized_idea_home" "$normalized_idea_cache_dir" ||
      idea_paths_overlap "$idea_home_overlap_path" "$idea_cache_overlap_path"; then
      echo "[run-intellij] refusing overlapping IDEA_HOME and IDEA_CACHE_DIR: $IDEA_HOME / $original_idea_cache_dir" >&2
      exit 1
    fi
  fi
fi
: "${IDEA_CONFIG:=/tmp/idea-config}"
: "${IDEA_SYSTEM:=/tmp/idea-system}"
: "${IDEA_LOG:=/tmp/idea-log}"
: "${IDEA_PLUGINS:=$IDEA_CONFIG/plugins}"
: "${IDEA_PROJECT:=}"
: "${IDEA_TRUST_PROJECT:=true}"
: "${IDEA_TRUST_ALL_PROJECTS:=}"
: "${IDEA_CONFIRM_CONSENTS:=false}"
: "${IDEA_ACCEPT_EUA:=true}"
: "${IDEA_EUA_VERSION:=1.0}"
: "${IDEA_REGISTER_JBR_SDK:=true}"
: "${IDEA_DISABLE_ONBOARDING:=true}"
: "${IDEA_REMOTE_X11_WORKAROUND:=false}"
: "${IDEA_X11_DEBUG:=false}"
: "${IDEA_X11_DEBUG_CATEGORIES:=#sun.awt.X11,#sun.awt.X11.XToolkit,#sun.awt.X11.XComponentPeer,#sun.awt.X11.XFramePeer,#sun.awt.X11.XDecoratedPeer,#sun.awt.X11.XErrorHandlerUtil,#sun.awt.X11.XNETProtocol,#sun.awt.X11.XWINProtocol,#sun.awt.X11.xembed,#com.intellij.ui.jcef,#com.intellij.platform.compose,#com.intellij.ide.ui,#com.intellij.openapi.wm.impl.FocusManagerImpl}"
: "${IDEA_X11_TRACE_CATEGORIES:=#java.awt.KeyboardFocusManager,#sun.awt.X11.event.XToolkit,#sun.awt.X11.focus.XComponentPeer,#sun.awt.X11.focus.XDecoratedPeer}"
: "${IDEA_X11_SEPARATE_LOG_CATEGORIES:=#com.intellij.ui.jcef,#sun.awt.X11,#java.awt.KeyboardFocusManager}"
: "${IDEA_LAUNCHER:=native}"
: "${IDEA_OPEN_FILE:=}"
: "${IDEA_PREPARE_ONLY:=false}"
: "${IDEA_CACHE_LOCK_TIMEOUT_SECONDS:=900}"
: "${IDEA_CACHE_STALE_LOCK_SECONDS:=120}"

if [ -z "$IDEA_TRUST_ALL_PROJECTS" ]; then
  IDEA_TRUST_ALL_PROJECTS="$IDEA_TRUST_PROJECT"
fi

if [ -z "$IDEA_PROJECT" ]; then
  if [ -d /workspace/jonnyzzz-x ]; then
    IDEA_PROJECT=/workspace/jonnyzzz-x
  else
    IDEA_PROJECT=/tmp/demo-project
  fi
fi

idea_url_is_default=false
if [ -z "${IDEA_URL:-}" ]; then
  idea_url_is_default=true
  case "$(uname -m)" in
    aarch64|arm64)
      IDEA_URL=https://github.com/JetBrains/intellij-community/releases/download/idea/2026.1.3/idea-2026.1.3-aarch64.tar.gz
      ;;
    *)
      IDEA_URL=https://github.com/JetBrains/intellij-community/releases/download/idea/2026.1.3/idea-2026.1.3.tar.gz
      ;;
  esac
fi

archive_name=$(basename "${IDEA_URL%%\?*}")
archive_name=$(printf '%s' "$archive_name" | tr -c 'A-Za-z0-9._-' '_')
if [ -z "$archive_name" ]; then
  archive_name=idea.tar.gz
fi
url_sha256=$(printf '%s' "$IDEA_URL" | sha256sum | awk '{print $1}')
idea_archive="${TMPDIR:-/tmp}/idea-${url_sha256}-$$.tar.gz"
legacy_url_archive=
legacy_basename_archive=
persistent_idea_home=
idea_cache_marker=.jonnyzzz-x-idea-cache-complete
if [ -n "$IDEA_CACHE_DIR" ]; then
  mkdir -p "$IDEA_CACHE_DIR"
  idea_cache_root_resolved=$(realpath -e -- "$IDEA_CACHE_DIR" 2>/dev/null || true)
  if [ -z "$idea_cache_root_resolved" ] || [ "$idea_cache_root_resolved" = "/" ]; then
    echo "[run-intellij] refusing unresolved IDEA cache root: $IDEA_CACHE_DIR" >&2
    exit 1
  fi
  prepare_idea_cache_structural_directory "$IDEA_CACHE_DIR/archives" archives || exit 1
  prepare_idea_cache_structural_directory "$IDEA_CACHE_DIR/homes" homes || exit 1
  idea_homes_root_resolved=$(realpath -e -- "$IDEA_CACHE_DIR/homes" 2>/dev/null || true)
  legacy_url_checksum=$(printf '%s' "$IDEA_URL" | cksum | awk '{print $1}')
  idea_archive="$IDEA_CACHE_DIR/archives/idea-${url_sha256}-${archive_name}"
  legacy_url_archive="$IDEA_CACHE_DIR/idea-${legacy_url_checksum}-${archive_name}"
  legacy_basename_archive="$IDEA_CACHE_DIR/$archive_name"
  if [ "$idea_home_was_explicit" = false ]; then
    persistent_idea_home="$IDEA_CACHE_DIR/homes/idea-${url_sha256}"
    if [ -L "$persistent_idea_home" ]; then
      echo "[run-intellij] refusing symlinked prepared IDEA home: $persistent_idea_home" >&2
      exit 1
    fi
  fi
fi

mkdir -p "$IDEA_CONFIG" "$IDEA_SYSTEM" "$IDEA_LOG" "$IDEA_PLUGINS" "$IDEA_PROJECT"

idea_archive_listing=
idea_archive_details=
idea_marker_tmp=
cleanup_idea_transient_files() {
  if [ -n "$idea_archive_listing" ]; then
    rm -f "$idea_archive_listing" 2>/dev/null || true
    idea_archive_listing=
  fi
  if [ -n "$idea_archive_details" ]; then
    rm -f "$idea_archive_details" 2>/dev/null || true
    idea_archive_details=
  fi
  if [ -n "$idea_marker_tmp" ]; then
    rm -f "$idea_marker_tmp" 2>/dev/null || true
    idea_marker_tmp=
  fi
}

valid_idea_archive() {
  candidate=$1
  [ ! -L "$candidate" ] && [ -f "$candidate" ] && [ -s "$candidate" ] || return 1
  idea_archive_listing=$(mktemp "${TMPDIR:-/tmp}/idea-archive-list.XXXXXX")
  idea_archive_details=$(mktemp "${TMPDIR:-/tmp}/idea-archive-details.XXXXXX")
  if ! LC_ALL=C tar --quoting-style=escape -tzf "$candidate" >"$idea_archive_listing" 2>/dev/null; then
    cleanup_idea_transient_files
    return 1
  fi
  if grep -Eq '(^|/)[.][.](/|$)|^/' "$idea_archive_listing"; then
    cleanup_idea_transient_files
    return 1
  fi
  if ! LC_ALL=C tar --numeric-owner --full-time --quoting-style=escape -tvzf "$candidate" >"$idea_archive_details" 2>/dev/null; then
    cleanup_idea_transient_files
    return 1
  fi
  for required_member in \
    'bin/idea[.]sh' \
    'bin/idea' \
    'jbr/bin/java' \
    'product-info[.]json'; do
    if ! awk -v member="^[^/]+/${required_member}$" \
      '$1 ~ /^-/ && $NF ~ member { found = 1 } END { exit !found }' "$idea_archive_details"; then
      cleanup_idea_transient_files
      return 1
    fi
  done
  if ! awk '
    function safe_symlink(member, target, count, idx, part, depth) {
      if (target ~ /^\//) return 0
      count = split(member, member_parts, "/")
      depth = 0
      for (idx = 1; idx < count; idx++) {
        part = member_parts[idx]
        if (part != "" && part != ".") resolved[++depth] = part
      }
      count = split(target, target_parts, "/")
      for (idx = 1; idx <= count; idx++) {
        part = target_parts[idx]
        if (part == "" || part == ".") continue
        if (part == "..") {
          if (depth <= 1) return 0
          depth--
        } else {
          resolved[++depth] = part
        }
      }
      return depth >= 1
    }
    $1 ~ /^-/ { next }
    $1 ~ /^d/ { next }
    $1 ~ /^l/ {
      if (NF != 8 || $7 != "->" || !safe_symlink($6, $8)) exit 1
      next
    }
    { exit 1 }
  ' "$idea_archive_details"; then
    cleanup_idea_transient_files
    return 1
  fi
  cleanup_idea_transient_files
}

valid_idea_install() {
  candidate=$1
  [ ! -L "$candidate/bin/idea.sh" ] && [ -f "$candidate/bin/idea.sh" ] && [ -x "$candidate/bin/idea.sh" ] &&
    [ ! -L "$candidate/bin/idea" ] && [ -f "$candidate/bin/idea" ] && [ -x "$candidate/bin/idea" ] &&
    [ ! -L "$candidate/jbr/bin/java" ] && [ -f "$candidate/jbr/bin/java" ] && [ -x "$candidate/jbr/bin/java" ] &&
    [ ! -L "$candidate/product-info.json" ] && [ -f "$candidate/product-info.json" ]
}

valid_prepared_idea_home() {
  candidate=$1
  [ ! -L "$candidate" ] && [ -d "$candidate" ] || return 1
  prepared_home_resolved=$(realpath -e -- "$candidate" 2>/dev/null || true)
  case "$prepared_home_resolved" in
    "$idea_homes_root_resolved"/*) ;;
    *) return 1 ;;
  esac
  marker="$candidate/$idea_cache_marker"
  [ ! -L "$marker" ] && [ -f "$marker" ] || return 1
  marker_value=$(cat "$marker" 2>/dev/null || true)
  valid_idea_install "$candidate" &&
    { [ "$marker_value" = "$url_sha256" ] || [ "$marker_value" = "$IDEA_URL" ]; }
}

migrate_prepared_idea_marker() {
  candidate=$1
  marker="$candidate/$idea_cache_marker"
  [ ! -L "$marker" ] && [ -f "$marker" ] || return 0
  if [ "$(cat "$marker" 2>/dev/null || true)" = "$IDEA_URL" ] && [ "$IDEA_URL" != "$url_sha256" ]; then
    idea_marker_tmp=$(mktemp "$marker.tmp.XXXXXX" 2>/dev/null || true)
    if [ -n "$idea_marker_tmp" ]; then
      if printf '%s\n' "$url_sha256" >"$idea_marker_tmp" && mv -f "$idea_marker_tmp" "$marker"; then
        idea_marker_tmp=
      else
        cleanup_idea_transient_files
      fi
    fi
  fi
}

link_stage=
cleanup_idea_link_stage() {
  if [ -n "$link_stage" ]; then
    rm -rf "$link_stage" 2>/dev/null || true
    link_stage=
  fi
}

link_prepared_idea_home() {
  link_parent=$(dirname "$IDEA_HOME")
  link_name=$(basename "$IDEA_HOME")
  mkdir -p "$link_parent"
  if [ -L "$IDEA_HOME" ] && [ "$(readlink "$IDEA_HOME")" = "$persistent_idea_home" ]; then
    return 0
  fi
  if [ -d "$IDEA_HOME" ] && [ ! -L "$IDEA_HOME" ]; then
    echo "[run-intellij] refusing non-atomic prepared-home replacement: $IDEA_HOME" >&2
    return 1
  fi
  link_stage=$(mktemp -d "$link_parent/.${link_name}.link.XXXXXX")
  if ! ln -s "$persistent_idea_home" "$link_stage/link"; then
    cleanup_idea_link_stage
    return 1
  fi
  if ! mv -Tf "$link_stage/link" "$IDEA_HOME"; then
    cleanup_idea_link_stage
    return 1
  fi
  rmdir "$link_stage"
  link_stage=
}

trap 'cleanup_idea_link_stage; cleanup_idea_transient_files' EXIT
trap 'trap - EXIT; cleanup_idea_link_stage; cleanup_idea_transient_files; exit 1' HUP INT TERM

cleanup_quarantined_idea_home() {
  cleanup_quarantine=$1
  cleanup_target=$2
  if [ ! -e "$cleanup_target" ] && [ ! -L "$cleanup_target" ]; then
    mv -T "$cleanup_quarantine" "$cleanup_target"
  else
    rm -rf "$cleanup_quarantine"
  fi
}

acquire_directory_lock() {
  lock_dir=$1
  lock_label=$2
  waited=0
  waiting_reported=false
  while :; do
    if [ -L "$lock_dir" ] || { [ -e "$lock_dir" ] && [ ! -d "$lock_dir" ]; }; then
      echo "[run-intellij] refusing unsafe $lock_label lock path: $lock_dir" >&2
      return 1
    fi
    if mkdir "$lock_dir" 2>/dev/null; then
      acquired_lock_token=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || printf '%s-%s-%s' "$$" "$lock_label" "$(date +%s)")
      acquired_owner_marker="$lock_dir/owner-$acquired_lock_token"
      if mkdir "$acquired_owner_marker" 2>/dev/null; then
        current_owner_count=0
        for current_owner_marker in "$lock_dir"/owner-*; do
          [ -d "$current_owner_marker" ] || continue
          current_owner_count=$((current_owner_count + 1))
        done
        if [ "$current_owner_count" -eq 1 ] && [ -d "$acquired_owner_marker" ]; then
          return 0
        fi
        rmdir "$acquired_owner_marker" 2>/dev/null || true
      fi
      continue
    fi
    now=$(date +%s)
    owner_marker=
    owner_count=0
    for candidate_owner_marker in "$lock_dir"/owner-*; do
      [ -d "$candidate_owner_marker" ] || continue
      owner_marker=$candidate_owner_marker
      owner_count=$((owner_count + 1))
    done
    if [ "$owner_count" -eq 1 ]; then
      modified=$(stat -c %Y "$owner_marker" 2>/dev/null || printf '0')
    elif [ "$owner_count" -eq 0 ]; then
      modified=$(stat -c %Y "$lock_dir" 2>/dev/null || printf '0')
    else
      modified=0
    fi
    if [ "$modified" -gt 0 ] && [ $((now - modified)) -gt "$IDEA_CACHE_STALE_LOCK_SECONDS" ]; then
      echo "[run-intellij] removing stale $lock_label lock: $lock_dir" >&2
      if [ -n "$owner_marker" ]; then
        rmdir "$owner_marker" 2>/dev/null || true
      fi
      if rmdir "$lock_dir" 2>/dev/null; then
        continue
      fi
    fi
    if [ "$waited" -ge "$IDEA_CACHE_LOCK_TIMEOUT_SECONDS" ]; then
      echo "[run-intellij] timed out waiting for $lock_label lock: $lock_dir" >&2
      return 1
    fi
    if [ "$waiting_reported" = false ]; then
      echo "[run-intellij] waiting for $lock_label lock: $lock_dir" >&2
      waiting_reported=true
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

directory_lock_heartbeat() {
  heartbeat_dir=$1
  heartbeat_token=$2
  heartbeat_marker="$heartbeat_dir/owner-$heartbeat_token"
  while [ -d "$heartbeat_marker" ]; do
    touch -c "$heartbeat_marker"
    sleep 15
  done
}

release_directory_lock() {
  release_dir=$1
  release_token=$2
  rmdir "$release_dir/owner-$release_token" 2>/dev/null || true
  rmdir "$release_dir" 2>/dev/null || true
}

acquire_directory_lock_fence() {
  fence_owner_marker=$1
  fence_name=$2
  acquired_lock_fence="$fence_owner_marker/$fence_name"
  mkdir "$acquired_lock_fence" 2>/dev/null
}

adopt_cached_idea_archive() {
  candidate=$1
  if valid_idea_archive "$candidate"; then
    if ! acquire_directory_lock_fence "$idea_cache_lock_marker" archive-publishing; then
      echo "[run-intellij] lost cache publication lock before legacy archive commit" >&2
      return 1
    fi
    idea_publication_fence=$acquired_lock_fence
    echo "[run-intellij] adopting validated legacy cache archive: $candidate -> $idea_archive" >&2
    if mv -fT "$candidate" "$idea_archive"; then
      rmdir "$idea_publication_fence"
      idea_publication_fence=
      return 0
    fi
    rmdir "$idea_publication_fence" 2>/dev/null || true
    idea_publication_fence=
    return 1
  fi
  echo "[run-intellij] discarding invalid legacy cache archive: $candidate" >&2
  rm -f "$candidate"
  return 1
}

idea_is_prepared=false
if [ -n "$persistent_idea_home" ] && valid_prepared_idea_home "$persistent_idea_home"; then
  migrate_prepared_idea_marker "$persistent_idea_home"
  link_prepared_idea_home || exit 1
  echo "[run-intellij] using prepared IDEA binaries: $persistent_idea_home" >&2
  idea_is_prepared=true
elif [ -z "$persistent_idea_home" ] && valid_idea_install "$IDEA_HOME"; then
  echo "[run-intellij] using existing IDEA_HOME: $IDEA_HOME" >&2
  idea_is_prepared=true
fi

if [ "$idea_is_prepared" = false ]; then
  idea_home_lock_acquired=false
  idea_cache_lock_acquired=false
  idea_home_lock=
  idea_cache_lock=
  idea_home_lock_token=
  idea_cache_lock_token=
  idea_home_lock_marker=
  idea_cache_lock_marker=
  idea_home_heartbeat_pid=
  idea_cache_heartbeat_pid=
  idea_publication_fence=
  quarantined_home=
  quarantined_home_target=
  tmp_archive=
  tmp_home=
  cleanup_idea_work() {
    cleanup_idea_link_stage
    cleanup_idea_transient_files
    if [ -n "$quarantined_home" ]; then
      if [ -n "$quarantined_home_target" ] &&
        cleanup_quarantined_idea_home "$quarantined_home" "$quarantined_home_target"; then
        quarantined_home=
      fi
    fi
    if [ -n "$idea_publication_fence" ]; then
      rmdir "$idea_publication_fence" 2>/dev/null || true
      idea_publication_fence=
    fi
    if [ -n "$idea_cache_heartbeat_pid" ]; then
      kill "$idea_cache_heartbeat_pid" 2>/dev/null || true
      wait "$idea_cache_heartbeat_pid" 2>/dev/null || true
      idea_cache_heartbeat_pid=
    fi
    if [ -n "$idea_home_heartbeat_pid" ]; then
      kill "$idea_home_heartbeat_pid" 2>/dev/null || true
      wait "$idea_home_heartbeat_pid" 2>/dev/null || true
      idea_home_heartbeat_pid=
    fi
    if [ -n "$tmp_archive" ]; then rm -f "$tmp_archive"; tmp_archive=; fi
    if [ -z "$IDEA_CACHE_DIR" ]; then rm -f "$idea_archive"; fi
    if [ -n "$tmp_home" ]; then rm -rf "$tmp_home"; tmp_home=; fi
    if [ "$idea_cache_lock_acquired" = true ]; then
      idea_cache_lock_acquired=false
      release_directory_lock "$idea_cache_lock" "$idea_cache_lock_token"
    fi
    if [ "$idea_home_lock_acquired" = true ]; then
      idea_home_lock_acquired=false
      release_directory_lock "$idea_home_lock" "$idea_home_lock_token"
    fi
  }
  trap 'cleanup_idea_work' EXIT
  trap 'trap - EXIT; cleanup_idea_work; exit 1' HUP INT TERM
  if [ -z "$persistent_idea_home" ]; then
    mkdir -p "$(dirname "$IDEA_HOME")"
    idea_home_lock="$IDEA_HOME.lock.d"
    acquire_directory_lock "$idea_home_lock" IDEA_HOME || exit 1
    idea_home_lock_token=$acquired_lock_token
    idea_home_lock_marker="$idea_home_lock/owner-$idea_home_lock_token"
    idea_home_lock_acquired=true
    directory_lock_heartbeat "$idea_home_lock" "$idea_home_lock_token" &
    idea_home_heartbeat_pid=$!
  fi
  if [ -n "$IDEA_CACHE_DIR" ]; then
    idea_cache_lock="$IDEA_CACHE_DIR/.idea-${url_sha256}.lock.d"
    acquire_directory_lock "$idea_cache_lock" cache || exit 1
    idea_cache_lock_token=$acquired_lock_token
    idea_cache_lock_marker="$idea_cache_lock/owner-$idea_cache_lock_token"
    idea_cache_lock_acquired=true
    directory_lock_heartbeat "$idea_cache_lock" "$idea_cache_lock_token" &
    idea_cache_heartbeat_pid=$!
  fi

  if [ -n "$persistent_idea_home" ] && valid_prepared_idea_home "$persistent_idea_home"; then
    migrate_prepared_idea_marker "$persistent_idea_home"
    link_prepared_idea_home || exit 1
    echo "[run-intellij] using prepared IDEA binaries after cache wait: $persistent_idea_home" >&2
  elif [ -z "$persistent_idea_home" ] && valid_idea_install "$IDEA_HOME"; then
    echo "[run-intellij] using existing IDEA_HOME after cache wait: $IDEA_HOME" >&2
  else
    if [ -d "$idea_archive" ] && [ ! -L "$idea_archive" ]; then
      echo "[run-intellij] refusing wrong-type cached archive destination: $idea_archive" >&2
      exit 1
    fi
    if [ ! -s "$idea_archive" ] && [ -s "$legacy_url_archive" ]; then
      adopt_cached_idea_archive "$legacy_url_archive" || true
    fi
    if [ "$idea_url_is_default" = true ] && [ ! -s "$idea_archive" ] && [ -s "$legacy_basename_archive" ]; then
      adopt_cached_idea_archive "$legacy_basename_archive" || true
    fi
    idea_archive_is_valid=false
    if [ -s "$idea_archive" ] && valid_idea_archive "$idea_archive"; then
      idea_archive_is_valid=true
    elif [ -s "$idea_archive" ]; then
      echo "[run-intellij] discarding invalid cached archive: $idea_archive" >&2
    fi
    if [ "$idea_archive_is_valid" = false ]; then
      echo "[run-intellij] downloading IDEA archive: $IDEA_URL -> $idea_archive" >&2
      if [ -n "$IDEA_CACHE_DIR" ]; then
        tmp_archive=$(mktemp "$IDEA_CACHE_DIR/archives/.idea-${url_sha256}.tmp.XXXXXX")
      else
        tmp_archive=$(mktemp "${TMPDIR:-/tmp}/idea-archive.tmp.XXXXXX")
      fi
      if ! curl -fL --retry 5 --retry-delay 2 --retry-all-errors --connect-timeout 30 --speed-limit 1024 --speed-time 30 "$IDEA_URL" -o "$tmp_archive"; then
        exit 1
      fi
      if ! valid_idea_archive "$tmp_archive"; then
        echo "[run-intellij] downloaded archive is not an IntelliJ distribution: $tmp_archive" >&2
        exit 1
      fi
      if [ -n "$IDEA_CACHE_DIR" ]; then
        if ! acquire_directory_lock_fence "$idea_cache_lock_marker" archive-publishing; then
          echo "[run-intellij] lost cache publication lock before archive commit" >&2
          exit 1
        fi
        idea_publication_fence=$acquired_lock_fence
      fi
      mv -fT "$tmp_archive" "$idea_archive"
      tmp_archive=
      if [ -n "$idea_publication_fence" ]; then
        rmdir "$idea_publication_fence"
        idea_publication_fence=
      fi
    else
      echo "[run-intellij] using cached IDEA archive: $idea_archive" >&2
    fi

    idea_install_target=${persistent_idea_home:-$IDEA_HOME}
    idea_install_parent=$(dirname "$idea_install_target")
    idea_install_name=$(basename "$idea_install_target")
    mkdir -p "$idea_install_parent"
    tmp_home=$(mktemp -d "$idea_install_parent/.${idea_install_name}.tmp.XXXXXX")
    if [ -n "$persistent_idea_home" ]; then
      echo "[run-intellij] extracting IDEA archive into host cache: $tmp_home" >&2
    else
      echo "[run-intellij] extracting IDEA archive into staging home: $tmp_home" >&2
    fi
    tar -xzf "$idea_archive" -C "$tmp_home" --strip-components=1
    if [ -z "$IDEA_CACHE_DIR" ]; then
      rm -f "$idea_archive"
    fi
    if ! valid_idea_install "$tmp_home"; then
      echo "[run-intellij] extracted archive is not a complete IntelliJ distribution: $idea_archive" >&2
      exit 1
    fi
    if [ -n "$persistent_idea_home" ]; then
      printf '%s\n' "$url_sha256" >"$tmp_home/$idea_cache_marker"
      idea_install_lock_marker=$idea_cache_lock_marker
    else
      idea_install_lock_marker=$idea_home_lock_marker
    fi
    idea_publication_fence="$idea_install_lock_marker/publishing"
    if ! acquire_directory_lock_fence "$idea_install_lock_marker" publishing; then
      echo "[run-intellij] lost $idea_install_name publication lock before commit" >&2
      exit 1
    fi
    idea_publication_fence=$acquired_lock_fence
    if { [ -n "$persistent_idea_home" ] && valid_prepared_idea_home "$idea_install_target"; } ||
      { [ -z "$persistent_idea_home" ] && valid_idea_install "$idea_install_target"; }; then
      rm -rf "$tmp_home"
      tmp_home=
    else
      if [ -e "$idea_install_target" ] || [ -L "$idea_install_target" ]; then
        quarantined_home="$idea_install_parent/.${idea_install_name}.replaced.$acquired_lock_token"
        quarantined_home_target=$idea_install_target
        mv -T "$idea_install_target" "$quarantined_home"
      fi
      mv -T "$tmp_home" "$idea_install_target"
      tmp_home=
    fi
    rmdir "$idea_publication_fence"
    idea_publication_fence=
    if [ -n "$quarantined_home" ]; then
      cleanup_quarantined_idea_home "$quarantined_home" "$idea_install_target"
      quarantined_home=
      quarantined_home_target=
    fi
    if [ -n "$persistent_idea_home" ]; then
      link_prepared_idea_home || exit 1
      echo "[run-intellij] IDEA host-cache extraction complete: $persistent_idea_home" >&2
    else
      echo "[run-intellij] IDEA staged extraction complete: $IDEA_HOME" >&2
    fi

  fi
  if [ -n "$idea_cache_heartbeat_pid" ]; then
    kill "$idea_cache_heartbeat_pid" 2>/dev/null || true
    wait "$idea_cache_heartbeat_pid" 2>/dev/null || true
    idea_cache_heartbeat_pid=
  fi
  if [ -n "$idea_home_heartbeat_pid" ]; then
    kill "$idea_home_heartbeat_pid" 2>/dev/null || true
    wait "$idea_home_heartbeat_pid" 2>/dev/null || true
    idea_home_heartbeat_pid=
  fi
  if [ "$idea_cache_lock_acquired" = true ]; then
    idea_cache_lock_acquired=false
    release_directory_lock "$idea_cache_lock" "$idea_cache_lock_token"
  fi
  if [ "$idea_home_lock_acquired" = true ]; then
    idea_home_lock_acquired=false
    release_directory_lock "$idea_home_lock" "$idea_home_lock_token"
  fi
  trap - EXIT HUP INT TERM
fi

if [ "$IDEA_PREPARE_ONLY" = true ]; then
  test -x "$IDEA_HOME/bin/idea.sh"
  test -x "$IDEA_HOME/bin/idea"
  test -x "$IDEA_HOME/jbr/bin/java"
  exit 0
fi

if [ "$IDEA_REGISTER_JBR_SDK" = "true" ] && [ -x "$IDEA_HOME/jbr/bin/java" ]; then
  mkdir -p /usr/lib/jvm "$IDEA_CONFIG/options"
  ln -sfn "$IDEA_HOME/jbr" /usr/lib/jvm/jbr-25
  : "${JAVA_HOME:=/usr/lib/jvm/jbr-25}"
  : "${JDK_HOME:=$JAVA_HOME}"
  export JAVA_HOME JDK_HOME
  if [ ! -f "$IDEA_CONFIG/options/jdk.table.xml" ]; then
    {
      cat <<'EOF'
<application>
  <component name="ProjectJdkTable">
EOF
      for jdk_name in jbr-25 corretto-25; do
        cat <<EOF
    <jdk version="2">
      <name value="$jdk_name" />
      <type value="JavaSDK" />
      <version value="JBR 25" />
      <homePath value="/usr/lib/jvm/jbr-25" />
      <roots>
        <annotationsPath>
          <root type="composite">
            <root url="jar://\$APPLICATION_HOME_DIR\$/plugins/java/lib/resources/jdkAnnotations.jar!/" type="simple" />
          </root>
        </annotationsPath>
        <classPath>
          <root type="composite">
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.base" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.compiler" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.desktop" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.logging" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.management" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.naming" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.net.http" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.prefs" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.sql" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/java.xml" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.compiler" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.jartool" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.jdi" type="simple" />
            <root url="jrt:///usr/lib/jvm/jbr-25!/jdk.unsupported" type="simple" />
          </root>
        </classPath>
        <javadocPath>
          <root type="composite" />
        </javadocPath>
        <sourcePath>
          <root type="composite" />
        </sourcePath>
      </roots>
    </jdk>
EOF
      done
      cat <<'EOF'
  </component>
</application>
EOF
    } > "$IDEA_CONFIG/options/jdk.table.xml"
  fi
fi

if [ "$IDEA_ACCEPT_EUA" = "true" ]; then
  cat > /tmp/SeedIdeaFirstRun.java <<'EOF'
import java.util.prefs.Preferences;

public class SeedIdeaFirstRun {
  public static void main(String[] args) throws Exception {
    String version = args.length == 0 ? "1.0" : args[0];
    Preferences privacyPolicy = Preferences.userRoot()
        .node("jetbrains")
        .node("privacy_policy");
    privacyPolicy.put("euacommunity_accepted_version", version);
    privacyPolicy.put("eua_accepted_version", version);
    privacyPolicy.put("accepted_version", version);
    privacyPolicy.flush();
  }
}
EOF
  "$IDEA_HOME/jbr/bin/javac" /tmp/SeedIdeaFirstRun.java
  "$IDEA_HOME/jbr/bin/java" -cp /tmp SeedIdeaFirstRun "$IDEA_EUA_VERSION"
fi

cat > /tmp/idea.properties <<EOF
idea.config.path=$IDEA_CONFIG
idea.system.path=$IDEA_SYSTEM
idea.log.path=$IDEA_LOG
idea.plugins.path=$IDEA_PLUGINS
EOF

if [ ! -f "$IDEA_PROJECT/README.md" ]; then
  cat > "$IDEA_PROJECT/README.md" <<EOF
# X Server Demo

Running IntelliJ inside jonnyzzz/x X server.
EOF
fi

if [ -z "$IDEA_OPEN_FILE" ]; then
  IDEA_OPEN_FILE="$IDEA_PROJECT/README.md"
fi

xml_escape() {
  printf '%s' "$1" \
    | sed \
      -e 's/&/\&amp;/g' \
      -e 's/</\&lt;/g' \
      -e 's/>/\&gt;/g' \
      -e 's/"/\&quot;/g'
}

if [ "$IDEA_TRUST_PROJECT" = "true" ]; then
  mkdir -p "$IDEA_CONFIG/options"
  trusted_project=$(xml_escape "$IDEA_PROJECT")
  cat > "$IDEA_CONFIG/options/trusted-paths.xml" <<EOF
<application>
  <component name="Trusted.Paths">
    <option name="trustedPaths">
      <map>
        <entry key="$trusted_project" value="true" />
      </map>
    </option>
  </component>
  <component name="Trusted.Paths.Settings">
    <option name="trustedPaths">
      <list>
        <option value="$trusted_project" />
      </list>
    </option>
  </component>
</application>
EOF
fi

if [ "$IDEA_DISABLE_ONBOARDING" = "true" ]; then
  mkdir -p "$IDEA_CONFIG/options"
  if [ ! -f "$IDEA_CONFIG/options/other.xml" ]; then
    cat > "$IDEA_CONFIG/options/other.xml" <<'EOF'
<application>
  <component name="PropertyService"><![CDATA[{
  "keyToString": {
    "WelcomeFeature.SHORT_WELCOME_GUIDE_SHOWN": "true",
    "defaultJdkConfigured": "true",
    "experimental.ui.on.first.startup": "true",
    "experimental.ui.onboarding.proposed.version": "999999"
  }
}]]></component>
</application>
EOF
  fi
  if [ ! -f "$IDEA_CONFIG/options/ide.general.xml" ]; then
    cat > "$IDEA_CONFIG/options/ide.general.xml" <<'EOF'
<application>
  <component name="GeneralSettings">
    <option name="confirmOpenNewProject2" value="1" />
  </component>
  <component name="Registry">
    <entry key="ide.experimental.ui.onboarding" value="false" source="SYSTEM" />
    <entry key="ide.newUsersOnboarding" value="false" source="SYSTEM" />
  </component>
</application>
EOF
  fi
  cat > "$IDEA_CONFIG/options/ui.lnf.xml" <<'EOF'
<application>
  <component name="UISettings">
    <option name="differentiateProjects" value="false" />
    <option name="mainMenuDisplayMode" value="SEPARATE_TOOLBAR" />
    <option name="showMainMenu" value="true" />
    <option name="useProjectColorsInMainToolbar" value="false" />
    <option name="useSolutionColorsInMainToolbar" value="false" />
  </component>
</application>
EOF
fi

export DISPLAY
export IDEA_PROPERTIES=/tmp/idea.properties

idea_extra_vmoptions=/tmp/idea-extra.vmoptions
idea_extra_initialized=false
append_idea_vm_option() {
  if [ "$idea_extra_initialized" = "false" ]; then
    : > "$idea_extra_vmoptions"
    if [ -n "${IDEA_VM_OPTIONS:-}" ] && [ -f "$IDEA_VM_OPTIONS" ]; then
      cat "$IDEA_VM_OPTIONS" >> "$idea_extra_vmoptions"
      printf '\n' >> "$idea_extra_vmoptions"
    fi
    idea_extra_initialized=true
  fi
  printf '%s\n' "$1" >> "$idea_extra_vmoptions"
  export IDEA_VM_OPTIONS="$idea_extra_vmoptions"
}

if [ "$IDEA_CONFIRM_CONSENTS" = "false" ]; then
  append_idea_vm_option "-Djb.consents.confirmation.enabled=false"
  if [ "$IDEA_TRUST_ALL_PROJECTS" = "true" ]; then
    append_idea_vm_option "-Didea.trust.all.projects=true"
  fi
fi

if [ -n "$IDEA_REMOTE_X11_WORKAROUND" ]; then
  append_idea_vm_option "-Dremote.x11.workaround=$IDEA_REMOTE_X11_WORKAROUND"
fi

if [ "$IDEA_X11_DEBUG" = "true" ]; then
  mkdir -p "$IDEA_LOG"
  : "${LIBGL_DEBUG:=verbose}"
  : "${MESA_DEBUG:=1}"
  : "${EGL_LOG_LEVEL:=debug}"
  export LIBGL_DEBUG MESA_DEBUG EGL_LOG_LEVEL
  echo "IDEA_X11_DEBUG=true LIBGL_DEBUG=$LIBGL_DEBUG MESA_DEBUG=$MESA_DEBUG EGL_LOG_LEVEL=$EGL_LOG_LEVEL" >&2
  append_idea_vm_option "-Dsun.java2d.xrender=True"
  append_idea_vm_option "-Dsun.java2d.opengl=false"
  append_idea_vm_option "-Dsun.awt.x11.trace=log,timestamp,stats,out:$IDEA_LOG/xawt-trace.log,td=1"
  append_idea_vm_option "-Didea.log.debug.categories=$IDEA_X11_DEBUG_CATEGORIES"
  append_idea_vm_option "-Didea.log.trace.categories=$IDEA_X11_TRACE_CATEGORIES"
  append_idea_vm_option "-Didea.log.separate.file.categories=$IDEA_X11_SEPARATE_LOG_CATEGORIES"
  append_idea_vm_option "-Dide.browser.jcef.log.level=verbose"
  append_idea_vm_option "-Dide.browser.jcef.log.path=$IDEA_LOG/jcef.log"
  append_idea_vm_option "-Dide.browser.jcef.log_chromium.path=$IDEA_LOG/jcef_chromium.log"
  append_idea_vm_option "-Dide.browser.jcef.log.extended=true"
fi

if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
  export XDG_RUNTIME_DIR=/tmp/runtime-root
  mkdir -p "$XDG_RUNTIME_DIR"
  chmod 700 "$XDG_RUNTIME_DIR"
fi
export _JAVA_AWT_WM_NONREPARENTING=1

run_intellij_env_log=/tmp/run-intellij-env.log
{
  echo "[run-intellij] environment snapshot"
  for name in \
    DISPLAY \
    XDG_CURRENT_DESKTOP \
    XDG_SESSION_TYPE \
    XDG_RUNTIME_DIR \
    DESKTOP_SESSION \
    GDMSESSION \
    WAYLAND_DISPLAY \
    AWT_TOOLKIT \
    _JAVA_AWT_WM_NONREPARENTING \
    JAVA_HOME \
    JDK_HOME \
    IDEA_HOME \
    IDEA_CONFIG \
    IDEA_SYSTEM \
    IDEA_LOG \
    IDEA_LAUNCHER \
    IDEA_REMOTE_X11_WORKAROUND \
    IDEA_X11_DEBUG \
    LIBGL_ALWAYS_SOFTWARE \
    LIBGL_DEBUG \
    MESA_DEBUG \
    EGL_LOG_LEVEL; do
    eval "value=\${$name-}"
    if [ -n "$value" ]; then
      printf '%s=%s\n' "$name" "$value"
    else
      printf '%s=<unset>\n' "$name"
    fi
  done
  if [ -f "$idea_extra_vmoptions" ]; then
    echo "[run-intellij] IDEA_VM_OPTIONS=$idea_extra_vmoptions"
    sed 's/^/VM_OPTION=/' "$idea_extra_vmoptions"
  fi
} > "$run_intellij_env_log" 2>&1 || true

case "$IDEA_LAUNCHER" in
  native)
    if [ -x "$IDEA_HOME/bin/idea" ]; then
      idea_launcher="$IDEA_HOME/bin/idea"
    else
      echo "Native IDEA launcher is missing: $IDEA_HOME/bin/idea" >&2
      exit 1
    fi
    ;;
  script)
    idea_launcher="$IDEA_HOME/bin/idea.sh"
    ;;
  *)
    echo "Unsupported IDEA_LAUNCHER=$IDEA_LAUNCHER; expected native or script" >&2
    exit 2
    ;;
esac

echo "[run-intellij] launcher=$idea_launcher" >&2
if [ -n "$IDEA_OPEN_FILE" ]; then
  exec "$idea_launcher" nosplash "$IDEA_PROJECT" "$IDEA_OPEN_FILE"
else
  exec "$idea_launcher" nosplash "$IDEA_PROJECT"
fi
