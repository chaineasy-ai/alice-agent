#!/usr/bin/env bash
#
# alice-agent 引导 / 操作脚本（幂等，可重复执行）
#
# 目标（#173 M3-1）：单命令起服务 + 打包 + 环境自检。
# 依赖：JDK >= 25（Gradle Wrapper 自带，无需本机装 Gradle）。
#
# 用法:
#   ./bootstrap.sh                 # 默认 setup（环境自检）
#   ./bootstrap.sh setup           # 校验 JDK / Gradle Wrapper
#   ./bootstrap.sh build           # 全量构建（含测试）
#   ./bootstrap.sh run [args]      # 运行 alice（默认 CLI facade，参数透传）
#   ./bootstrap.sh run-tui [args]  # 运行 TUI facade
#   ./bootstrap.sh run-rpc [args]  # 运行 RPC facade（HTTP，默认端口 8080）
#   ./bootstrap.sh test [args]     # 运行单元测试
#   ./bootstrap.sh check [args]    # 运行 check（含覆盖率门禁）
#   ./bootstrap.sh package         # 打包（installDist + distTar/distZip）
#   ./bootstrap.sh shell           # 进入项目 shell（JAVA_HOME 已导出）
#   ./bootstrap.sh clean           # 清理构建产物
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

JAVA_MIN=25

log() { printf '\033[36m[bootstrap]\033[0m %s\n' "$*"; }
err() { printf '\033[31m[bootstrap][ERROR]\033[0m %s\n' "$*" >&2; }

# 解析 JAVA_HOME（供 shell / 子进程复用）
# 顺序：显式 JAVA_HOME → PATH → SDKMAN current
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  : # 已显式设置
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  export JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
elif [ -x "${HOME}/.sdkman/candidates/java/current/bin/java" ]; then
  export JAVA_HOME="${HOME}/.sdkman/candidates/java/current"
fi
export PATH="${JAVA_HOME:-}/bin:${PATH}"

ensure_java() {
  if ! command -v java >/dev/null 2>&1; then
    err "未找到 java —— 需 JDK >= ${JAVA_MIN}（Temurin/OpenJDK）"
    err "提示：设置 JAVA_HOME，或安装 JDK 25（如 sdkman: sdk install java 25-tem）"
    exit 2
  fi
  local v
  v="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [ "${v:-0}" -lt "$JAVA_MIN" ]; then
    err "JDK 版本 ${v:-?} < ${JAVA_MIN}（当前 JAVA_HOME=${JAVA_HOME:-未设置}）"
    exit 2
  fi
  log "JDK ${v} ✅（JAVA_HOME=${JAVA_HOME:-未设置}）"
}

gw() { ./gradlew "$@"; }

cmd_setup() {
  ensure_java
  [ -x ./gradlew ] || { err "缺少可执行的 ./gradlew"; exit 2; }
  log "校验 Gradle Wrapper…"
  gw --version >/dev/null
  log "环境就绪。常用命令："
  log "  ./bootstrap.sh run        # 运行 alice（CLI）"
  log "  ./bootstrap.sh run-rpc    # 运行 RPC 服务（HTTP :8080）"
  log "  ./bootstrap.sh test       # 单元测试"
  log "  ./bootstrap.sh package    # 打包（installDist + distTar/distZip）"
}

cmd_build() { ensure_java; log "全量构建（含测试）…"; gw build "$@"; }

cmd_run() {
  ensure_java
  log "运行 alice（默认 CLI facade）…"
  gw :alice-bootstrap:run --args="$*"
}

cmd_run_tui() {
  ensure_java
  log "运行 alice TUI facade…"
  gw :alice-bootstrap:run --args="--facade tui $*"
}

cmd_run_rpc() {
  ensure_java
  local port="${ALICE_RPC_PORT:-8080}"
  log "运行 alice RPC facade（HTTP，端口 ${port}）…"
  log "健康检查: curl http://127.0.0.1:${port}/api/v1/health"
  gw :alice-bootstrap:run --args="--facade rpc --port ${port} $*"
}

cmd_test() { ensure_java; log "运行单元测试…"; gw test "$@"; }
cmd_check() { ensure_java; log "运行 check（含 JaCoCo 覆盖率门禁）…"; gw check "$@"; }

cmd_package() {
  ensure_java
  log "打包（installDist + distTar + distZip）…"
  gw :alice-bootstrap:installDist :alice-bootstrap:distTar :alice-bootstrap:distZip
  log "解包即用产物：alice-bootstrap/build/install/alice/bin/alice"
  log "分发包："
  ls -lh alice-bootstrap/build/distributions/ 2>/dev/null || true
}

cmd_shell() {
  ensure_java
  log "进入项目 shell（exit 退出；JAVA_HOME=${JAVA_HOME:-未设置}）"
  exec "${SHELL:-bash}"
}

cmd_clean() { gw clean "$@"; }

usage() {
  sed -n '3,22p' "$0" | sed -E 's/^# ?//'
}

case "${1:-setup}" in
  setup) shift || true; cmd_setup ;;
  build) shift || true; cmd_build "$@" ;;
  run) shift || true; cmd_run "$@" ;;
  run-tui) shift || true; cmd_run_tui "$@" ;;
  run-rpc) shift || true; cmd_run_rpc "$@" ;;
  test) shift || true; cmd_test "$@" ;;
  check) shift || true; cmd_check "$@" ;;
  package | dist) cmd_package ;;
  shell) cmd_shell ;;
  clean) shift || true; cmd_clean "$@" ;;
  -h | --help | help) usage ;;
  *)
    err "未知子命令: $1"
    usage
    exit 2
    ;;
esac
