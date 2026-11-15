package top.fcidd.qqbot.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import top.fcidd.qqbot.starter.annotation.EnableQQBot;

@SpringBootApplication
@EnableQQBot
public class QQBotSpringBootStarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(QQBotSpringBootStarterApplication.class, args);
    }

}
