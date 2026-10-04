#!/usr/bin/env bash
# =============================================================================
# 公交卡挂失补卡与余额转移系统 —— 一条命令启动
#   ./start.sh            构建（首次）并启动，前端 Svelte 构建后由 Quarkus 同源提供
#   ./start.sh --dev      开发模式：后端 8080 + 前端 Vite 热更新 5173（/api 代理）
#   ./start.sh --reset    清空 SQLite 演示库后启动（恢复三张种子卡）
# 访问： http://localhost:8080
# 依赖： 无（无 Java/Node 时自动下载 JDK21、Maven、Node 到 .tools）
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$ROOT/.tools"
mkdir -p "$TOOLS"

# ---------------- 基础工具引导 ----------------
need_boot=""
command -v curl >/dev/null || need_boot="curl"
command -v tar >/dev/null || need_boot="tar"
if [ -n "$need_boot" ]; then echo "缺少基础命令: $need_boot，请先安装"; exit 1; fi

detect_java() {
  if command -v java >/dev/null && [ "$(java -version 2>&1 | head -1 | grep -cE '21\.|22\.|23\.|24\.')" = "1" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"; return 0
  fi
  for d in "$TOOLS"/jdk-* "$HOME"/tools/jdk-*; do
    [ -x "$d/bin/java" ] && { JAVA_HOME="$d"; return 0; }
  done
  return 1
}

detect_mvn() {
  command -v mvn >/dev/null && { MVN="mvn"; return 0; }
  for d in "$TOOLS"/apache-maven-* "$HOME"/tools/apache-maven-*; do
    [ -x "$d/bin/mvn" ] && { MVN="$d/bin/mvn"; return 0; }
  done
  return 1
}

detect_node() {
  command -v node >/dev/null && { NODE_BIN="$(dirname "$(command -v node)")"; return 0; }
  for d in "$TOOLS"/node-* "$HOME"/tools/node-*; do
    [ -x "$d/bin/node" ] && { NODE_BIN="$d/bin"; return 0; }
  done
  return 1
}

install_jdk() {
  echo ">> 下载 Temurin JDK 21 …"
  local f="$TOOLS/jdk21.tar.gz"
  curl -fsSL -o "$f" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" || \
  wget -q -O "$f" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf "$f" -C "$TOOLS"; rm -f "$f"
  JAVA_HOME="$(ls -d "$TOOLS"/jdk-*)"
}

install_mvn() {
  echo ">> 下载 Maven 3.9.9 …"
  local f="$TOOLS/maven.tar.gz"
  curl -fsSL -o "$f" "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" || \
  wget -q -O "$f" "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz"
  tar xzf "$f" -C "$TOOLS"; rm -f "$f"
  MVN="$(ls -d "$TOOLS"/apache-maven-*/bin/mvn)"
}

detect_java || install_jdk
detect_mvn || install_mvn
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

MODE="${1:-run}"
[ "$MODE" = "--reset" ] && { rm -rf "$ROOT/backend/data"; MODE="run"; }

# ---------------- 前端构建 ----------------
FE_MARKER="$ROOT/backend/src/main/resources/META-INF/resources/index.html"
if [ ! -f "$FE_MARKER" ] || [ "$MODE" = "--dev" ]; then
  if ! detect_node; then
    echo ">> 下载 Node.js 22 …"
    f="$TOOLS/node.tar.xz"
    (curl -fsSL -o "$f" "https://nodejs.org/dist/v22.23.2/node-v22.23.2-linux-x64.tar.xz" || \
     wget -q -O "$f" "https://nodejs.org/dist/v22.23.2/node-v22.23.2-linux-x64.tar.xz")
    tar xJf "$f" -C "$TOOLS"; rm -f "$f"
    NODE_BIN="$(ls -d "$TOOLS"/node-*/bin)"
  fi
  export PATH="$NODE_BIN:$PATH"
  echo ">> 安装前端依赖 …"
  (cd "$ROOT/frontend" && npm install --no-audit --no-fund)
  if [ "$MODE" != "--dev" ]; then
    echo ">> 构建 Svelte 前端（输出到后端静态资源目录）…"
    (cd "$ROOT/frontend" && npm run build)
  fi
fi

# ---------------- 后端构建 ----------------
JAR="$ROOT/backend/target/quarkus-app/quarkus-run.jar"
if [ ! -f "$JAR" ]; then
  echo ">> 构建 Quarkus 后端（首次较慢，需下载 Maven 依赖）…"
  (cd "$ROOT/backend" && "$MVN" -q -B package -DskipTests)
fi

# 若源码比 jar 新则重新打包（日常开发便利）
if find "$ROOT/backend/src" -newer "$JAR" -type f 2>/dev/null | grep -q .; then
  echo ">> 检测到源码更新，重新构建后端 …"
  (cd "$ROOT/backend" && "$MVN" -q -B package -DskipTests)
fi

echo ">> 启动后端 http://localhost:8080 …"
if [ "$MODE" = "--dev" ]; then
  export PATH="$NODE_BIN:$PATH"
  (cd "$ROOT/frontend" && npm run dev) &
  FRONT_PID=$!
  trap "kill $FRONT_PID 2>/dev/null || true" EXIT
  echo ">> 前端开发服务器 http://localhost:5173 （/api 代理到 8080）"
fi
cd "$ROOT/backend"
exec java -jar target/quarkus-app/quarkus-run.jar
