package com.bitselect.catalog;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.bitselect.catalog", "com.bitselect.contracts"})
@EnableDubbo
public class CatalogApplication {
  public static void main(String[] args) {
    configureNacosLogging();
    com.bitselect.contracts.LocalRpc.configure();
    SpringApplication.run(CatalogApplication.class, args);
  }

  static void configureNacosLogging() {
    // Nacos rolling files must belong to one JVM; Windows cannot rename a shared open file.
    System.getProperties()
        .putIfAbsent(
            "JM.LOG.PATH",
            java.nio.file.Path.of(
                    System.getProperty("user.home"),
                    "logs",
                    "bit-select",
                    "catalog-service",
                    Long.toString(ProcessHandle.current().pid()))
                .toString());
  }
}
