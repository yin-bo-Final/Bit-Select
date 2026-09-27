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
    com.bitselect.contracts.LocalRpc.configure();
    SpringApplication.run(AiApplication.class, args);
  }
}
