package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class RedisConfiguration {

    /*
     * redisConnectionFactory：这个连接工厂对象并不需要自己去创建，因为在
     *      server子模块下的pom.xml文件已经引入了starter依赖,这个starter依赖
     *      会自动的把这个连接工厂对象给我们创建好，并且放到Spring容器中，所以这个地方只需要声明一下
     *      就可以把它注入进来了。
     *   <dependency>
     *        <groupId>org.springframework.boot</groupId>
     *        <artifactId>spring-boot-starter-data-redis</artifactId>
     *   </dependency>
     * */
    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory){
        log.info("开始创建redis模板对象...");
        RedisTemplate redisTemplate = new RedisTemplate();
        //设置redis的连接工厂对象
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //设置redis key的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        return redisTemplate;
    }


}

