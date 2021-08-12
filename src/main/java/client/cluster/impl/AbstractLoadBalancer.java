package client.cluster.impl;

import client.cluster.ILoadBalancer;
import client.cluster.RemoteServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Copyright (C) 2018
 * All rights reserved
 * User: yulong.zhang
 * Date:2018年09月18日17:44:08
 * <p>
 * <p>
 * 抽象负载均衡实现
 */
public abstract class AbstractLoadBalancer implements ILoadBalancer {

    protected int getWeight(RemoteServer remoteServer) {
        if (remoteServer != null && remoteServer.isEnable()) {
            return remoteServer.getWeight();
        }
        return -1;
    }


    public RemoteServer select(List<RemoteServer> list) {

        // 没有可以负载的机器，直接返回空
        if (list == null) return null;
        // 只有一台，就直接返回
        if (list.size() == 1) {
            return list.get(0);
        }

        List<RemoteServer> l = new ArrayList<>();

        for (int i = list.size() - 1; i >= 0; i--) {
            RemoteServer r = list.get(i);
            if (r.isEnable()) {
                l.add(r);
            }
        }

        // 调用具体的实现
        return doSelect(l);
    }

    /**
     * 具体实现，就放在这里面了
     *
     * @param list
     * @return
     */
    public abstract RemoteServer doSelect(List<RemoteServer> list);

}
