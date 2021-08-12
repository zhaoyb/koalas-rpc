package client.proxyfactory;

import client.cluster.ILoadBalancer;
import client.cluster.Icluster;
import client.cluster.impl.DirectClisterImpl;
import client.cluster.impl.RandomLoadBalancer;
import client.cluster.impl.ZookeeperClusterImpl;
import client.invoker.KoalasMethodInterceptor;
import client.invoker.LocalMockInterceptor;
import generic.GenericService;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import protocol.KoalasBinaryProtocol;
import transport.TKoalasFramedTransport;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Copyright (C) 2018
 * All rights reserved
 * User: yulong.zhang
 * Date:2018年09月18日17:44:58
 */
public class KoalasClientProxy implements FactoryBean<Object>, ApplicationContextAware, InitializingBean {
    private final static Logger logger = LoggerFactory.getLogger(KoalasClientProxy.class);
    public static final String ASYNC_IFACE = "AsyncIface";
    public static final String IFACE = "Iface";
    public static final String CLIENT = "Client";
    public static final String ASYNC_CLIENT = "AsyncClient";
    //请求体最大长度
    public static final int DEFUAL_MAXLENGTH = 10 * 1024 * 1024;
    //连接超时
    public static final int DEFUAL_CONNTIMEOUT = 5 * 1000;
    //读取超时
    public static final int DEFUAL_READTIMEOUT = 30 * 1000;

    //异步请求并发问题
    private volatile CountDownLatch countDownLatch = new CountDownLatch(1);

    //client端service
    private String serviceInterface;
    // 方式1：zk管理的动态集群,格式192.168.3.253:6666
    private String zkPath;
    // 方式2：指定的server列表，逗号分隔，#分隔权重,格式192.168.3.253:6666#10,192.168.3.253:6667#10
    private String serverIpPorts;

    //代理对象,所有client-server类型统一代理
    private Object koalasServiceProxy;
    //spring上下文对象
    private ApplicationContext applicationContext;
    // 同步还是异步,默认同步。
    private boolean async = false;
    // 是否泛化调用
    private boolean generic = false;
    //连接超时时间
    private int connTimeout = DEFUAL_CONNTIMEOUT;
    //读取超时时间
    private int readTimeout = DEFUAL_READTIMEOUT;
    //本地client测试用实现
    private String localMockServiceImpl;
    //重试
    private boolean retryRequest = true;
    private int retryTimes = 3;
    private GenericObjectPoolConfig genericObjectPoolConfig;
    //最大连接数
    private int maxTotal = 50;
    //最大闲置数
    private int maxIdle = 20;
    //最小闲置数量
    private int minIdle = 10;
    private boolean lifo = true;
    private boolean fairness = false;
    private long maxWaitMillis = 30 * 1000;
    //多长时间运行一次
    private long timeBetweenEvictionRunsMillis = 3 * 60 * 1000;
    private long minEvictableIdleTimeMillis = 5 * 60 * 1000;

    //对象空闲多久后逐出, 当空闲时间>该值 且 空闲连接>最大空闲数 时直接逐出,
    //不再根据MinEvictableIdleTimeMillis判断  (默认逐出策略)
    private long softMinEvictableIdleTimeMillis = 10 * 60 * 1000;
    private int numTestsPerEvictionRun = 20;
    private boolean testOnCreate = false;
    private boolean testOnBorrow = false;
    private boolean testOnReturn = false;
    private boolean testWhileIdle = true;
    private Icluster icluster;
    private ILoadBalancer iLoadBalancer;
    private String env = "dev";
    AbandonedConfig abandonedConfig;
    private boolean removeAbandonedOnBorrow = true;
    private boolean removeAbandonedOnMaintenance = true;
    private int removeAbandonedTimeout = 30;
    private int maxLength_ = DEFUAL_MAXLENGTH;
    private static int cores = Runtime.getRuntime().availableProcessors();
    private int asyncSelectorThreadCount = cores * 2;
    private static volatile List<TAsyncClientManager> asyncClientManagerList = null;
    private String privateKey;
    private String publicKey;
    private boolean cat = false;

    public boolean isCat() {
        return cat;
    }

    public void setCat(boolean cat) {
        this.cat = cat;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public int getMaxLength_() {
        return maxLength_;
    }

    public void setMaxLength_(int maxLength_) {
        this.maxLength_ = maxLength_;
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    public int getMaxIdle() {
        return maxIdle;
    }

    public void setMaxIdle(int maxIdle) {
        this.maxIdle = maxIdle;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    public boolean isLifo() {
        return lifo;
    }

    public void setLifo(boolean lifo) {
        this.lifo = lifo;
    }

    public boolean isFairness() {
        return fairness;
    }

    public void setFairness(boolean fairness) {
        this.fairness = fairness;
    }

    public long getMaxWaitMillis() {
        return maxWaitMillis;
    }

    public void setMaxWaitMillis(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
    }

    public long getTimeBetweenEvictionRunsMillis() {
        return timeBetweenEvictionRunsMillis;
    }

    public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
        this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
    }

    public long getMinEvictableIdleTimeMillis() {
        return minEvictableIdleTimeMillis;
    }

    public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
        this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
    }

    public long getSoftMinEvictableIdleTimeMillis() {
        return softMinEvictableIdleTimeMillis;
    }

    public void setSoftMinEvictableIdleTimeMillis(long softMinEvictableIdleTimeMillis) {
        this.softMinEvictableIdleTimeMillis = softMinEvictableIdleTimeMillis;
    }

    public int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    public boolean isTestOnCreate() {
        return testOnCreate;
    }

    public void setTestOnCreate(boolean testOnCreate) {
        this.testOnCreate = testOnCreate;
    }

    public boolean isTestOnBorrow() {
        return testOnBorrow;
    }

    public void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    public boolean isTestOnReturn() {
        return testOnReturn;
    }

    public void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    public boolean isTestWhileIdle() {
        return testWhileIdle;
    }

    public void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
    }

    public String getLocalMockServiceImpl() {
        return localMockServiceImpl;
    }

    public void setLocalMockServiceImpl(String localMockServiceImpl) {
        this.localMockServiceImpl = localMockServiceImpl;
    }

    public boolean isGeneric() {
        return generic;
    }

    public void setGeneric(boolean generic) {
        this.generic = generic;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getConnTimeout() {
        return connTimeout;
    }

    public void setConnTimeout(int connTimeout) {
        this.connTimeout = connTimeout;
    }

    public String getZkPath() {
        return zkPath;
    }

    public void setZkPath(String zkPath) {
        this.zkPath = zkPath;
    }

    public String getServerIpPorts() {
        return serverIpPorts;
    }

    public void setServerIpPorts(String serverIpPorts) {
        this.serverIpPorts = serverIpPorts;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public Object getKoalasServiceProxy() {
        return koalasServiceProxy;
    }

    public void setKoalasServiceProxy(Object koalasServiceProxy) {
        this.koalasServiceProxy = koalasServiceProxy;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public String getServiceInterface() {
        return serviceInterface;
    }

    public void setServiceInterface(String serviceInterface) {
        this.serviceInterface = serviceInterface;
    }

    public ILoadBalancer getiLoadBalancer() {
        return iLoadBalancer;
    }

    public void setiLoadBalancer(ILoadBalancer iLoadBalancer) {
        this.iLoadBalancer = iLoadBalancer;
    }

    public boolean isRemoveAbandonedOnBorrow() {
        return removeAbandonedOnBorrow;
    }

    public void setRemoveAbandonedOnBorrow(boolean removeAbandonedOnBorrow) {
        this.removeAbandonedOnBorrow = removeAbandonedOnBorrow;
    }

    public boolean isRemoveAbandonedOnMaintenance() {
        return removeAbandonedOnMaintenance;
    }

    public void setRemoveAbandonedOnMaintenance(boolean removeAbandonedOnMaintenance) {
        this.removeAbandonedOnMaintenance = removeAbandonedOnMaintenance;
    }

    public int getRemoveAbandonedTimeout() {
        return removeAbandonedTimeout;
    }

    public void setRemoveAbandonedTimeout(int removeAbandonedTimeout) {
        this.removeAbandonedTimeout = removeAbandonedTimeout;
    }

    public boolean isRetryRequest() {
        return retryRequest;
    }

    public void setRetryRequest(boolean retryRequest) {
        this.retryRequest = retryRequest;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        this.retryTimes = retryTimes;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object getObject() {
        if (getKoalasServiceProxy() == null) throw new RuntimeException("the Proxy can't be null");
        return getKoalasServiceProxy();
    }

    @Override
    public Class<?> getObjectType() {
        if (serviceInterface == null)
            return null;
        return getIfaceInterface();
    }

    private Class<?> getIfaceInterface() {
        if (async)
            return getAsyncIfaceInterface();
        else
            return getSynIfaceInterface();
    }


    private Constructor<?> synConstructor;
    private Constructor<?> asyncConstructor;

    public Object getInterfaceClientInstance(TTransport socket, String server) {

        if (!async) {
            Class<?> clazz = getSynClientClass();
            try {
                if (synConstructor == null) {
                    synConstructor = clazz.getDeclaredConstructor(TProtocol.class);
                }
                TTransport transport = new TKoalasFramedTransport(socket, maxLength_);
                if (this.getPrivateKey() != null && this.getPublicKey() != null) {
                    ((TKoalasFramedTransport) transport).setRsa((byte) 1);
                    ((TKoalasFramedTransport) transport).setPrivateKey(this.privateKey);
                    ((TKoalasFramedTransport) transport).setPublicKey(this.publicKey);
                }

                TProtocol protocol = new KoalasBinaryProtocol(transport);
                ((KoalasBinaryProtocol) protocol).setGeneric(generic);
                return synConstructor.newInstance(protocol);

            } catch (NoSuchMethodException e) {
                logger.error("the clazz can't find the Constructor with TProtocol.class");
            } catch (InstantiationException e) {
                logger.error("get InstantiationException", e);
            } catch (IllegalAccessException e) {
                logger.error("get IllegalAccessException", e);
            } catch (InvocationTargetException e) {
                logger.error("get InvocationTargetException", e);
            }
        } else {
            if (null == asyncClientManagerList) {
                synchronized (this) {
                    if (null == asyncClientManagerList) {
                        asyncClientManagerList = new ArrayList<>();
                        for (int i = 0; i < asyncSelectorThreadCount; i++) {
                            try {
                                asyncClientManagerList.add(new TAsyncClientManager());
                                countDownLatch.countDown();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }

            if (countDownLatch.getCount() > 0) {
                try {
                    //wait for other add TAsyncClientManager
                    countDownLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    logger.warn("InterruptedException at wait  for other add TAsyncClientManager class:" + serviceInterface, e);
                }
            }

            Class<?> clazz = getAsyncClientClass();

            if (asyncConstructor == null) {
                try {
                    asyncConstructor = clazz.getDeclaredConstructor(TProtocolFactory.class, TAsyncClientManager.class, TNonblockingTransport.class);
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }
            }

            try {
                return asyncConstructor.newInstance(new KoalasBinaryProtocol.Factory(generic), asyncClientManagerList.get(socket.hashCode() % asyncSelectorThreadCount), socket);
            } catch (InstantiationException e) {
                logger.error("get InstantiationException", e);
            } catch (IllegalAccessException e) {
                logger.error("get IllegalAccessException", e);
            } catch (InvocationTargetException e) {
                logger.error("get InvocationTargetException", e);
            }

        }
        return null;
    }

    private Class<?> genericAsyncIface;
    private Class<?> asyncIface;

    private Class<?> getAsyncIfaceInterface() {
        Class<?>[] classes = null;

        if (!generic) {
            if (asyncIface != null) return asyncIface;
            try {
                classes = this.getClass().getClassLoader().loadClass(serviceInterface).getClasses();
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("can't find the class :" + serviceInterface);
            }
        } else {
            if (genericAsyncIface != null) return genericAsyncIface;
            classes = GenericService.class.getClasses();
        }
        for (Class c : classes)
            if (c.isMemberClass() && c.isInterface() && c.getSimpleName().equals(ASYNC_IFACE)) {
                if (!generic) {
                    asyncIface = c;
                } else {
                    genericAsyncIface = c;
                }
                return c;
            }
        throw new IllegalArgumentException("can't find the interface AsyncIface,please make the service with thrift tools!");
    }


    private Class<?> genericSynIface;
    private Class<?> synIface;

    private Class<?> getSynIfaceInterface() {

        Class<?>[] classes = null;
        if (!generic) {
            if (synIface != null) return synIface;
            try {
                classes = this.getClass().getClassLoader().loadClass(serviceInterface).getClasses();
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("can't find the class :" + serviceInterface);
            }
        } else {
            if (genericSynIface != null) return genericSynIface;
            classes = GenericService.class.getClasses();
        }

        for (Class c : classes)
            if (c.isMemberClass() && c.isInterface() && c.getSimpleName().equals(IFACE)) {
                if (!generic) {
                    synIface = c;
                } else {
                    genericSynIface = c;
                }
                return c;
            }
        throw new IllegalArgumentException("can't find the interface Iface,please make the service with thrift tools");
    }

    private Class<?> genericSynClient;
    private Class<?> synClient;

    private Class<?> getSynClientClass() {
        Class<?>[] classes = null;
        if (!generic) {
            if (synClient != null) return synClient;
            try {
                classes = this.getClass().getClassLoader().loadClass(serviceInterface).getClasses();
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("can't find the class :" + serviceInterface);
            }
        } else {
            if (genericSynClient != null) return genericSynClient;
            classes = GenericService.class.getClasses();
        }
        for (Class c : classes)
            if (c.isMemberClass() && !c.isInterface() && c.getSimpleName().equals(CLIENT)) {
                if (!generic) {
                    synClient = c;
                } else {
                    genericSynClient = c;
                }
                return c;
            }
        throw new IllegalArgumentException("serviceInterface must contain Sub Class of Client");
    }

    private Class<?> genericAsyncClient;
    private Class<?> asyncClient;

    private Class<?> getAsyncClientClass() {
        Class<?>[] classes = null;
        if (!generic) {
            try {
                if (asyncClient != null) return asyncClient;
                classes = this.getClass().getClassLoader().loadClass(serviceInterface).getClasses();
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("can't find the class :" + serviceInterface);
            }
        } else {
            if (genericAsyncClient != null) return genericAsyncClient;
            classes = GenericService.class.getClasses();
        }
        for (Class c : classes)
            if (c.isMemberClass() && !c.isInterface() && c.getSimpleName().equals(ASYNC_CLIENT)) {
                if (!generic) {
                    genericAsyncClient = c;
                } else {
                    asyncClient = c;
                }
                return c;
            }
        throw new IllegalArgumentException("serviceInterface must contain Sub Class of AsyncClient");
    }


    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void afterPropertiesSet() {


        if (serviceInterface == null) {
            throw new IllegalArgumentException("serviceInterface can't be null");
        }

        if (zkPath == null && serverIpPorts == null) {
            throw new IllegalArgumentException("zkPath or serverIpPorts at least ones can't be null");
        }

        Class<?> _interface = null;
        if (localMockServiceImpl != null && !StringUtils.isEmpty(localMockServiceImpl.trim())) {
            LocalMockInterceptor localMockInterceptor = new LocalMockInterceptor(localMockServiceImpl);
            _interface = getIfaceInterface();
            ProxyFactory pf = new ProxyFactory(_interface, localMockInterceptor);
            setKoalasServiceProxy(pf.getProxy());
            return;
        }

        genericObjectPoolConfig = getGenericObjectPoolConfig();
        abandonedConfig = getAbandonedConfig();


        if (!StringUtils.isEmpty(serverIpPorts)) {
            // 如果配置的有直连地址，则使用直连实现
            icluster = new DirectClisterImpl(serverIpPorts, iLoadBalancer == null ? new RandomLoadBalancer() : iLoadBalancer, serviceInterface, async, connTimeout, readTimeout, genericObjectPoolConfig, abandonedConfig);
        } else {
            // 使用 zk 集群
            icluster = new ZookeeperClusterImpl(zkPath, iLoadBalancer == null ? new RandomLoadBalancer() : iLoadBalancer, serviceInterface, env, async, connTimeout, readTimeout, genericObjectPoolConfig, abandonedConfig);
        }

        KoalasMethodInterceptor koalasMethodInterceptor = new KoalasMethodInterceptor(icluster, retryTimes, retryRequest, this, readTimeout);
        _interface = getIfaceInterface();

        koalasServiceProxy = new ProxyFactory(_interface, koalasMethodInterceptor).getProxy();

        logger.info("the service【{}】is start !", serviceInterface);
    }

    private AbandonedConfig getAbandonedConfig() {
        AbandonedConfig abandonedConfig = new AbandonedConfig();
        abandonedConfig.setRemoveAbandonedOnBorrow(isRemoveAbandonedOnBorrow());
        abandonedConfig.setRemoveAbandonedOnMaintenance(isRemoveAbandonedOnMaintenance());
        abandonedConfig.setRemoveAbandonedTimeout(getRemoveAbandonedTimeout());
        return abandonedConfig;
    }

    private GenericObjectPoolConfig getGenericObjectPoolConfig() {
        GenericObjectPoolConfig genericObjectPoolConfig = new GenericObjectPoolConfig();
        genericObjectPoolConfig.setMaxTotal(getMaxTotal());
        genericObjectPoolConfig.setMinIdle(getMinIdle());
        genericObjectPoolConfig.setMaxIdle(maxIdle);
        genericObjectPoolConfig.setMaxWaitMillis(getMaxWaitMillis());
        genericObjectPoolConfig.setLifo(isLifo());
        genericObjectPoolConfig.setFairness(isFairness());
        genericObjectPoolConfig.setMinEvictableIdleTimeMillis(getMinEvictableIdleTimeMillis());
        genericObjectPoolConfig.setSoftMinEvictableIdleTimeMillis(getSoftMinEvictableIdleTimeMillis());
        genericObjectPoolConfig.setNumTestsPerEvictionRun(getNumTestsPerEvictionRun());
        genericObjectPoolConfig.setTimeBetweenEvictionRunsMillis(getTimeBetweenEvictionRunsMillis());
        genericObjectPoolConfig.setTestOnCreate(isTestOnCreate());
        genericObjectPoolConfig.setTestOnBorrow(isTestOnBorrow());
        genericObjectPoolConfig.setTestOnReturn(isTestOnReturn());
        genericObjectPoolConfig.setTestWhileIdle(isTestWhileIdle());
        return genericObjectPoolConfig;
    }

    public void destroy() {
        if (icluster != null) icluster.destroy();
    }

    public static void main(String[] args) {
        String a = "192.168.3.253:6666#10#thrift,192.168.3.253:6667#10#netty";
        System.out.println(Arrays.toString(a.split("[^0-9a-zA-Z_\\-\\.:#]+")));
    }
}
