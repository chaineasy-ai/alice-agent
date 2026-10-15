#!/usr/bin/env bash
# =============================================================================
# scan-exposure.sh — 接口暴露面静态巡检（通用代理端点 / 未鉴权端点 / 调试残留）
#
# 用途：例行安全巡检的「源码扫描」环节，只读、不联网、不改文件。
# 规则集：SEC-EXP-01..13（见 cland-sec/docs/checklists/接口暴露面检查清单-v1.md）
# 来源：cland-sec 仓库 docs/tools/scan-exposure.sh（vendored；只读扫描、不改仓库）
# 依据：base/cland-crawler#39 复盘（ChainPay 6 服务 Redis 通用代理端点，P0）
#
# 用法：
#   ./scan-exposure.sh [选项] [扫描根目录...]
#     --fail-on-high   发现 HIGH 时退出码 1（供 CI/门禁使用）
#     --json           以 JSON 行输出（每行一个 finding）
#     --no-info        不输出 INFO 级
#   默认扫描当前目录；自动跳过 target/node_modules/.git/dist/build/.venv
#
# 退出码：0 无 HIGH（或未启用 --fail-on-high）；1 有 HIGH 且启用 --fail-on-high；2 参数/环境错误
# =============================================================================
set -uo pipefail

FAIL_ON_HIGH=0
JSON=0
SHOW_INFO=1
ROOTS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --fail-on-high) FAIL_ON_HIGH=1 ;;
    --json) JSON=1 ;;
    --no-info) SHOW_INFO=0 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) ROOTS+=("$1") ;;
  esac
  shift
done
[ ${#ROOTS[@]} -eq 0 ] && ROOTS=(".")

EXCLUDES=(target node_modules .git dist build .venv venv __pycache__ .idea .gradle)
PRUNE=( \( )
for e in "${EXCLUDES[@]}"; do PRUNE+=( -name "$e" -o ); done
PRUNE=( "${PRUNE[@]:0:${#PRUNE[@]}-1}" )   # 去掉最后一个 -o
PRUNE+=( \) -prune -o )

HIGH_COUNT=0
MED_COUNT=0
INFO_COUNT=0

emit() { # level file line rule message
  local level="$1" file="$2" line="$3" rule="$4" msg="$5"
  case "$level" in
    HIGH) HIGH_COUNT=$((HIGH_COUNT+1)) ;;
    MED)  MED_COUNT=$((MED_COUNT+1)) ;;
    INFO) INFO_COUNT=$((INFO_COUNT+1)); [ "$SHOW_INFO" -eq 0 ] && return ;;
  esac
  if [ "$JSON" -eq 1 ]; then
    printf '{"level":"%s","rule":"%s","file":"%s","line":%s,"message":"%s"}\n' \
      "$level" "$rule" "$file" "${line:-0}" "$msg"
  else
    printf '[%-4s] %-12s %s:%s  %s\n' "$level" "$rule" "$file" "${line:-0}" "$msg"
  fi
}

# 按模式扫描一个文件：scan_grep <level> <rule> <file> <prefix> <pattern> [exclude_pattern]
# 用进程替换避免子 shell 丢计数
scan_grep() {
  local level="$1" rule="$2" file="$3" prefix="$4" pattern="$5" exclude="${6:-}"
  local ln rest
  while IFS= read -r line; do
    if [ -n "$exclude" ] && printf '%s' "$line" | grep -qE "$exclude"; then
      continue
    fi
    ln="${line%%:*}"
    rest="${line#*:}"
    rest="$(printf '%s' "$rest" | sed 's/^[[:space:]]*//')"
    emit "$level" "$file" "$ln" "$rule" "${prefix}${rest:0:160}"
  done < <(grep -nE "$pattern" "$file" 2>/dev/null)
}

DANGER_PATHS='redis|sql|jdbc|exec|shell|cmd|eval|debug|proxy|mongo|mysql|pg|database|actuator|admin'
KEYOPS='get|set|del|delete|keys|scan|hget|hset|lpush|publish|query|raw|flush'
STORE_CLIENTS='RedisAPI|RedisTemplate|ReactiveRedis|resty\.redis|redis::|Jedis|Lettuce|JdbcTemplate|DataSource|MongoTemplate|MongoClient|sqlx::|mysql::'

find_sources() { # root
  find "$1" "${PRUNE[@]}" -type f \
    \( -name "*.java" -o -name "*.kt" -o -name "*.py" -o -name "*.rs" \
       -o -name "*.ts" -o -name "*.tsx" -o -name "*.js" -o -name "*.go" \
       -o -name "*.properties" -o -name "*.yml" -o -name "*.yaml" \
       -o -name "Dockerfile*" -o -name "Jenkinsfile*" \) -print 2>/dev/null
}

scan_file() { # f
  local f="$1" ln

  # ---------- SEC-EXP-01 通用代理类端点路径 ----------
  scan_grep HIGH SEC-EXP-01 "$f" "通用代理类端点路径：" "@Path\(\"/(${DANGER_PATHS})([/\"]|$)"
  scan_grep HIGH SEC-EXP-01 "$f" "通用代理类路径（Python）：" "@(app|router|bp)\.(route|get|post|put|delete)\(\"/(${DANGER_PATHS})"
  scan_grep HIGH SEC-EXP-01 "$f" "通用代理类路径（Rust/TS）：" "\.(route|get|post|put|delete)\(\"/(${DANGER_PATHS})"

  # ---------- SEC-EXP-02 任意 key/命令读写语义 ----------
  if grep -qE "@Path\(\"/(${KEYOPS})(/|\")" "$f" 2>/dev/null \
     && grep -qE "${STORE_CLIENTS}" "$f" 2>/dev/null; then
    ln=$(grep -nE "@Path\(\"/(${KEYOPS})(/|\")" "$f" | head -1 | cut -d: -f1)
    emit HIGH "$f" "$ln" "SEC-EXP-02" "通用键/命令读写语义 + 存储客户端同文件（疑似 KV/DB 代理）"
  fi

  # ---------- SEC-EXP-03 入站层直持存储客户端 ----------
  case "$f" in
    */adapter/in/web/*|*/adapter/api/*|*/adapter/inbound/*|*/api/*|*/routes/*|*/handlers/*)
      scan_grep HIGH SEC-EXP-03 "$f" "入站层直接依赖存储客户端（应经 usecase/端口）：" \
        "import .*(${STORE_CLIENTS})" ;;
  esac

  # ---------- SEC-EXP-04 主源码树中的测试/调试类 ----------
  case "$f" in
    */src/main/*Resource.java|*/src/main/*Resource.kt)
      case "$(basename "$f")" in
        *Test*|*Debug*|*Dev*|*Demo*|*Sample*|*Playground*|*Mock*)
          emit HIGH "$f" 1 "SEC-EXP-04" "主源码树中的测试/调试 Resource：$(basename "$f")" ;;
      esac ;;
  esac
  case "$f" in
    */src/main/*)
      case "$f" in
        */test/*|*/debug/*|*/dev/*|*/demo/*|*/example/*|*/sample/*|*/mock/*)
          emit MED "$f" 1 "SEC-EXP-04" "主源码树中的调试/示例包：$(basename "$f")" ;;
      esac ;;
  esac

  # ---------- SEC-EXP-05 通配路径参数 + 存储客户端 ----------
  if grep -qE "@Path\(\"[^\"]*/\{" "$f" 2>/dev/null \
     && grep -qE "${STORE_CLIENTS}" "$f" 2>/dev/null \
     && ! grep -qE "@Path\(\"/(api|ledger|recon|public|health)" "$f" 2>/dev/null; then
    ln=$(grep -nE "@Path\(\"[^\"]*/\{" "$f" | head -1 | cut -d: -f1)
    emit MED "$f" "$ln" "SEC-EXP-05" "路径参数直通存储调用（任意 key 风险，且非业务前缀）：$(basename "$f")"
  fi

  # ---------- SEC-EXP-07 显式放行 ----------
  scan_grep MED SEC-EXP-07 "$f" "显式放行注解/配置：" "@PermitAll|permitAll\(\)|auth:[[:space:]]*none"

  # ---------- SEC-EXP-09 调试/管理组件 ----------
  case "$f" in
    *.properties|*.yml|*.yaml)
      scan_grep MED SEC-EXP-09 "$f" "调试/管理端点开启：" \
        'quarkus\.(swagger-ui|dev-ui|smallrye-openapi)[^=]*=[[:space:]]*(true|/)|management\.endpoints|endpoints\.web\.exposure|^debug[[:space:]]*[:=][[:space:]]*true' ;;
    *pom.xml)
      scan_grep INFO SEC-EXP-09 "$f" "管理/文档组件依赖（确认暴露面）：" \
        'spring-boot-starter-actuator|quarkus-smallrye-openapi|quarkus-swagger-ui' ;;
  esac

  # ---------- SEC-EXP-10 监听全网卡 ----------
  case "$f" in
    *.properties|*.yml|*.yaml|Dockerfile*)
      scan_grep INFO SEC-EXP-10 "$f" "监听全网卡（内网可达）：" \
        'http\.host=0\.0\.0\.0|--address=0\.0\.0\.0|host:[[:space:]]*0\.0\.0\.0|--host=0\.0\.0\.0' ;;
  esac

  # ---------- SEC-EXP-11 明文凭证默认值 ----------
  case "$f" in
    *.properties|*.yml|*.yaml|Dockerfile*|Jenkinsfile*)
      scan_grep MED SEC-EXP-11 "$f" "疑似明文凭证默认值：" \
        '(password|passwd|secret|apikey|api_key)[^=]*=[[:space:]]*[^$[:space:]]{3,}' \
        'EXAMPLE|example|CHANGE_ME|placeholder|\*\*\*|<redacted>|:password=|=\$' ;;
  esac

  # ---------- SEC-EXP-12 容器端口映射 ----------
  case "$f" in
    Jenkinsfile*|docker-compose*.yml|docker-compose*.yaml)
      scan_grep INFO SEC-EXP-12 "$f" "端口映射（确认是否被网关/防火墙收敛）：" \
        '(-p[[:space:]]+[0-9]+:|--publish|ports:)' ;;
  esac
}

for root in "${ROOTS[@]}"; do
  [ -e "$root" ] || { echo "root not found: $root" >&2; exit 2; }

  while IFS= read -r f; do
    scan_file "$f"
  done < <(find_sources "$root")

  # ---------- SEC-EXP-13 网关兜底路由 ----------
  while IFS= read -r f; do
    scan_grep MED SEC-EXP-13 "$f" "疑似兜底/全量代理路由：" \
      'uri"?[[:space:]]*:[[:space:]]*"?/|proxy_pass[[:space:]]+/|location[[:space:]]+/[[:space:]]*\{'
  done < <(find "$root" "${PRUNE[@]}" -type f \( -name "*.conf" -o -name "*.yaml" -o -name "*.yml" \) -print 2>/dev/null)
done

echo "------------------------------------------------------------------"
printf '扫描完成：HIGH=%d MED=%d INFO=%d（根：%s）\n' "$HIGH_COUNT" "$MED_COUNT" "$INFO_COUNT" "${ROOTS[*]}"
if [ "$FAIL_ON_HIGH" -eq 1 ] && [ "$HIGH_COUNT" -gt 0 ]; then
  echo "结论：存在 HIGH 级暴露面问题，门禁不通过。" >&2
  exit 1
fi
echo "结论：未发现 HIGH（MED/INFO 需人工复核）。"
exit 0
