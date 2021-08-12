package annotation;

import client.proxyfactory.KoalasClientProxy;

import java.lang.annotation.*;

/**
 * Copyright (C) 2019
 * All rights reserved
 * User: yulong.zhang
 * Date:2019年04月17日13:46:40
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface KoalasClient {
    //zk的服务地址，集群中间逗号分隔
    String zkPath() default "";
    //不实用zk发现直接连接服务器server，格式ip:端口#权重。多个逗号分隔
    String serverIpPorts() default "";

    String genericService() default "";
    // 是否开启CAT数据大盘，需要配置CAT服务，即可查看详细调用情况）
    boolean cat() default false;
    // 连接时间
    int connTimeout() default KoalasClientProxy.DEFUAL_CONNTIMEOUT;
    // 读取时间
    int readTimeout() default KoalasClientProxy.DEFUAL_READTIMEOUT;
    //本地测试的实现
    String localMockServiceImpl() default "";
    // 是否错误重试
    boolean retryRequest() default true;
    //重试次数
    int retryTimes() default 3;
    //TCP长连接池，参照Apache Pool参数
    int maxTotal() default  100;

    int maxIdle() default 50;

    int minIdle() default  10;

    boolean lifo() default true;

    boolean fairness() default  false;

    long maxWaitMillis() default 30 *1000;

    long timeBetweenEvictionRunsMillis() default 3 * 60 * 1000;

    long minEvictableIdleTimeMillis() default  5 * 60 * 1000;

    long softMinEvictableIdleTimeMillis() default 10 * 60 * 1000;

    int numTestsPerEvictionRun() default 20;

    boolean testOnCreate() default  false;

    boolean testOnBorrow() default  false;

    boolean testOnReturn() default  false;

    boolean testWhileIdle() default  true;
    //负载略侧，默认随机
    String iLoadBalancer() default "";
    // 环境
    String env() default  "dev";

    boolean removeAbandonedOnBorrow() default  true;

    boolean removeAbandonedOnMaintenance() default  true;

    int removeAbandonedTimeout() default  30;
    // 允许发送最大字节数
    int maxLength_() default KoalasClientProxy.DEFUAL_MAXLENGTH;
    // 私钥
    String privateKey() default "";
    // 公钥
    String publicKey() default "";
}
