package com.transit.bootstrap;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.File;

/** 启动入口：确保 data 目录存在并在空库时播种演示数据 */
@ApplicationScoped
public class DataInitializer {

    @Inject SeedRunner seedRunner;

    void onStart(@Observes StartupEvent ev) {
        new File("data").mkdirs();
        seedRunner.seedIfEmpty();
    }
}
