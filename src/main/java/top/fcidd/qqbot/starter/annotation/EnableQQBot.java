package top.fcidd.qqbot.starter.annotation;

import org.springframework.context.annotation.Import;
import top.fcidd.qqbot.starter.autoconfigure.QQBotAutoConfiguration;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(QQBotAutoConfiguration.class)
public @interface EnableQQBot {
}