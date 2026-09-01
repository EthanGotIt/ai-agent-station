package cn.ethan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent 应用启动入口：负责启动 Spring Boot 容器。
 *
 * @author ethan
 * @date 2026-08-05
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
