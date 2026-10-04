package com.transit.bootstrap;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * 应用入口：在 Quarkus（含 Hibernate/SQLite）初始化前确保 data 目录存在，
 * 因为 SQLite 驱动不会自动创建相对路径数据库的父目录。
 */
@QuarkusMain
public class Main {
    public static void main(String[] args) {
        new java.io.File("data").mkdirs();
        Quarkus.run(args);
    }
}
