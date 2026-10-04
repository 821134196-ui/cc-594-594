#!/usr/bin/env bash
# 一条命令启动：公交卡挂失补卡与余额转移系统
#   ./run.sh          生产模式单端口（构建前后端 → http://localhost:8080）
#   ./run.sh dev      开发模式（Quarkus 8080 + Vite 5173 热更新）
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-prod}"
TOOLS_DIR="$HOME/.buscard-tools"

log() { printf '\033[36m[run]\033[0m %s\n' "$*"; }

# ---------- 1. JDK / Maven 引导（无 root 也可） ----------
if ! command -v java >/dev/null 2>&1; then
  if [ -d "$HOME/tools/jdk-17.0.20.1+1" ]; then
    export JAVA_HOME="$HOME/tools/jdk-17.0.20.1+1"
  elif [ -d "$TOOLS_DIR/jdk" ]; then
    export JAVA_HOME="$TOOLS_DIR/jdk"
  else
    log "未找到 Java，自动下载 Temurin JDK 17 到 $TOOLS_DIR …"
    mkdir -p "$TOOLS_DIR"
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$TOOLS_DIR/jdk.tar.gz"
    tar xzf "$TOOLS_DIR/jdk.tar.gz" -C "$TOOLS_DIR"
    export JAVA_HOME="$(find "$TOOLS_DIR" -maxdepth 1 -type d -name 'jdk-*' | head -1)"
  fi
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v mvn >/dev/null 2>&1; then
  if [ -d "$HOME/tools/apache-maven-3.9.9" ]; then
    export PATH="$HOME/tools/apache-maven-3.9.9/bin:$PATH"
  elif [ -d "$TOOLS_DIR/maven" ]; then
    export PATH="$TOOLS_DIR/maven/bin:$PATH"
  else
    log "未找到 Maven，自动下载 Maven 3.9.9 …"
    mkdir -p "$TOOLS_DIR"
    curl -fsSL "https://repo1.maven.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz" -o "$TOOLS_DIR/maven.tar.gz"
    tar xzf "$TOOLS_DIR/maven.tar.gz" -C "$TOOLS_DIR"
    export PATH="$(find "$TOOLS_DIR" -maxdepth 1 -type d -name 'apache-maven-*' | head -1)/bin:$PATH"
  fi
fi
log "Java: $(java -version 2>&1 | head -1)"
log "Maven: $(mvn -version 2>&1 | head -1)"
command -v node >/dev/null 2>&1 && log "Node: $(node -v)"

mkdir -p data

# ---------- 2. 开发模式 ----------
if [ "$MODE" = "dev" ]; then
  log "开发模式：启动 Vite(5173) 与 Quarkus(8080)"
  ( cd web && npm install && npm run dev ) &
  WEB_PID=$!
  trap 'kill $WEB_PID 2>/dev/null || true' EXIT
  exec mvn quarkus:dev
fi

# ---------- 3. 生产模式：构建前端 → 构建后端 → 单端口运行 ----------
log "构建 Svelte 前端（产物进入 Quarkus 静态资源目录）…"
( cd web && npm install --no-fund --no-audit && npm run build )

log "构建 Quarkus 后端…"
mvn -q -B -DskipTests package

JAR="target/quarkus-app/quarkus-run.jar"
log "启动服务：http://localhost:8080 （Ctrl-C 停止）"
log "首次打开即进入系统页面；演示命令另见 ./demo.sh"
exec java -jar "$JAR"
