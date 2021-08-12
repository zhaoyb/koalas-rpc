package client.cluster.impl;

import client.cluster.ILoadBalancer;
import client.cluster.RemoteServer;
import client.cluster.ServerObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.KoalasRegexUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Copyright (C) 2018
 * All rights reserved
 * User: yulong.zhang
 * Date: 2018年09月18日17:44:17
 *
 * 直连实现
 *
 */
public class DirectClisterImpl extends AbstractBaseIcluster {
    private static final Logger LOG = LoggerFactory.getLogger(DirectClisterImpl.class);
    public static final String REGEX = "[^0-9a-zA-Z_\\-\\.:#]+";
    public static final String REGEX_IPS = "[[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}:[0-9]{1,5}#[0-9]{1,}[,]{0,}]+";

    //ip:port
    private String hostAndPorts;
    //负载策略，默认权重
    private ILoadBalancer iLoadBalancer;
    private String serviceName;
    //当前服务列表
    private List<RemoteServer> serverList = new ArrayList<>();


    public DirectClisterImpl(String hostAndPorts, ILoadBalancer iLoadBalancer, String serviceName, boolean async, int conTimeOut, int soTimeOut, GenericObjectPoolConfig genericObjectPoolConfig, AbandonedConfig abandonedConfig) {

        super(iLoadBalancer, serviceName, async, conTimeOut, soTimeOut, genericObjectPoolConfig, abandonedConfig);
        this.hostAndPorts = hostAndPorts;
        this.iLoadBalancer = iLoadBalancer;
        this.serviceName = serviceName;

        // 判断直连地址格式
        if (!KoalasRegexUtil.match(REGEX_IPS, hostAndPorts)) {
            throw new RuntimeException("error hostAndPorts:" + hostAndPorts + ",serviceName:" + serviceName);
        }
    }

    @Override
    public RemoteServer getUseRemote() {
        if (serverList.size() == 0) {
            if (this.hostAndPorts == null) return null;
            String[] array = hostAndPorts.split(REGEX);
            List<RemoteServer> list = new ArrayList<>();
            for (String temp : array) {
                String hostAndIp = temp.split("#")[0].trim();
                // 权重
                Integer weight = Integer.valueOf(temp.split("#")[1].trim());
                // host
                String host = hostAndIp.split(":")[0].trim();
                // port
                String port = hostAndIp.split(":")[1].trim();

                String server = StringUtils.EMPTY;
                list.add(new RemoteServer(host, port, weight, true, server));
            }
            serverList = list;
        }
        // 使用负载均衡， 选择一台
        return iLoadBalancer.select(serverList);
    }

    @Override
    public void destroy() {
        LOG.info("【{}】shut down", serviceName);
        serverList = null;//help gc
        if (serverPollMap != null && serverPollMap.size() > 0) {

            for (String string : serverPollMap.keySet()) {
                GenericObjectPool p = serverPollMap.get(string);
                if (p != null) p.close();
                serverPollMap.remove(string);
            }
        }
    }

    @Override
    public ServerObject getObjectForRemote() {
        RemoteServer remoteServer = this.getUseRemote();
        if (remoteServer == null) return null;
        // 如果包含
        if (serverPollMap.containsKey(createMapKey(remoteServer))) {
            // 从serverPollMap中获取
            GenericObjectPool<TTransport> pool = serverPollMap.get(createMapKey(remoteServer));
            try {
                return createServerObject(pool, remoteServer);
            } catch (Exception e) {
                LOG.error("borrowObject is fail,the poll message is:", e);
                return null;
            }
        }

        GenericObjectPool<TTransport> pool = createGenericObjectPool(remoteServer);
        serverPollMap.put(createMapKey(remoteServer), pool);
        try {
            return createServerObject(pool, remoteServer);
        } catch (Exception e) {
            LOG.error("borrowObject is wrong,the poll message is:", e);
            return null;
        }
    }

    /**
     *  创建服务端对象
     *
     * @param pool
     * @param remoteServer
     * @return
     */
    private ServerObject createServerObject(GenericObjectPool<TTransport> pool, RemoteServer remoteServer) {
        ServerObject serverObject = new ServerObject();
        serverObject.setGenericObjectPool(pool);
        serverObject.setRemoteServer(remoteServer);
        return serverObject;
    }

    private String createMapKey(RemoteServer remoteServer) {
        return remoteServer.getIp().concat("-").concat(remoteServer.getPort());
    }

}
