package com.bitselect.ai;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.bitselect.ai", "com.bitselect.contracts"})
@EnableScheduling
@EnableDubbo
public class AiApplication {
  public static void main(String[] args) {
    configureNacosLogging();
    com.bitselect.contracts.LocalRpc.configure();
    SpringApplication.run(AiApplication.class, args);
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
                    "ai-service",
                    Long.toString(ProcessHandle.current().pid()))
                .toString());
  }
}
