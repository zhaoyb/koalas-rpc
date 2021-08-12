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
    // 端口
    int port();
    // zk路径
    String zkpath() default "";
    boolean cat() default false;
    //netty boss线程
    int bossThreadCount() default 0;
    // netty work线程
    int workThreadCount() default  0;
    int koalasThreadCount() default 0;
    int maxLength() default Integer.MAX_VALUE;
    String env() default "dev";
    int weight() default  10;
    // 服务类型，netty/thrift
    String serverType() default  "NETTY";
    int workQueue() default 0;
    // 私钥
    String privateKey() default "";
    // 公钥
    String publicKey() default "";
}
