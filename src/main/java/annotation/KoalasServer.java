package annotation;

import java.lang.annotation.*;

/**
 * Copyright (C) 2019
 * All rights reserved
 * User: yulong.zhang
 * Date:2019年04月17日13:46:40
 *
 * 服务按 注解
 *
 *
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface KoalasServer {
    // 暴露的段空间地址
    int port();
    // zk地址，集群中间逗号分隔
    String zkpath() default "";
    // 是否开启 cat 监控
    boolean cat() default false;
    //netty boss线程，处理连接的线程
    int bossThreadCount() default 0;
    // netty work线程，读取线程
    int workThreadCount() default  0;
    // 业务线程池
    int koalasThreadCount() default 0;
    // 最大接收字节数
    int maxLength() default Integer.MAX_VALUE;
    // 环境
    String env() default "dev";
    // 权重
    int weight() default  10;
    // 服务类型，netty/thrift
    String serverType() default  "NETTY";
    // 当 server超载时，可以容纳等待的队列长度
    int workQueue() default 0;
    // 私钥
    String privateKey() default "";
    // 公钥
    String publicKey() default "";
}
