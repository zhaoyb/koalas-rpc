package client.cluster;

import java.util.Objects;

/**
 * Copyright (C) 2018
 * All rights reserved
 * User: yulong.zhang
 * Date:2018年09月18日17:44:46
 *
 * 远程服务对象
 *
 */
public class RemoteServer {

    public RemoteServer(String ip, String port, int weight, boolean isEnable,String server) {
        this.ip = ip;
        this.port = port;
        this.weight = weight;
        this.isEnable = isEnable;
        this.server =server;
    }

    private String ip;
    private String port;
    private int weight;
    private boolean isEnable=true;
    private String server;

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public boolean isEnable() {
        return isEnable;
    }

    public void setEnable(boolean enable) {
        isEnable = enable;
    }

    @Override
    public String toString() {
        return "RemoteServer{" +
                "ip='" + ip + '\'' +
                ", port='" + port + '\'' +
                ", weight='" + weight + '\'' +
                ", isEnable=" + isEnable +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass () != o.getClass ()) return false;
        RemoteServer that = (RemoteServer) o;
        return weight == that.weight &&
                isEnable == that.isEnable &&
                Objects.equals ( ip, that.ip ) &&
                Objects.equals ( port, that.port ) &&
                Objects.equals ( server, that.server );
    }

    @Override
    public int hashCode() {

        return Objects.hash ( ip, port, weight, isEnable, server );
    }
}
