package client.invoker;

import client.async.ReleaseResourcesKoalasAsyncCallBack;
import client.cluster.Icluster;
import client.cluster.ServerObject;
import client.proxyfactory.KoalasClientProxy;
import com.dianping.cat.Cat;
import com.dianping.cat.CatConstants;
import com.dianping.cat.message.Event;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.spi.MessageTree;
import exceptions.OutMaxLengthException;
import exceptions.RSAException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.async.TAsyncClient;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import protocol.KoalasTrace;
import utils.TraceThreadContext;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Copyright (C) 2018
 * All rights reserved
 * User: yulong.zhang
 * Date:2018年11月23日11:13:33
 * <p>
 * <p>
 * 方法调用实现
 */
public class KoalasMethodInterceptor implements MethodInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(KoalasMethodInterceptor.class);

    private Icluster icluster;
    private int retryTimes;
    private boolean retryRequest;
    private KoalasClientProxy koalasClientProxy;
    private int asyncTimeOut;
    private boolean cat;

    public KoalasMethodInterceptor(Icluster icluster, int retryTimes, boolean retryRequest, KoalasClientProxy koalasClientProxy, int asyncTimeOut) {
        this.icluster = icluster;
        this.retryTimes = retryTimes;
        this.retryRequest = retryRequest;
        this.koalasClientProxy = koalasClientProxy;
        this.asyncTimeOut = asyncTimeOut;
        this.cat = koalasClientProxy.isCat();
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {

        // 要调用的方法
        Method method = invocation.getMethod();
        String methodName = method.getName();
        // 要调用的方法参数
        Object[] args = invocation.getArguments();

        // 参数类型
        Class<?>[] parameterTypes = method.getParameterTypes();
        // 方法所属于的类
        if (method.getDeclaringClass() == Object.class) {
            try {
                // 如果方法所属于的类是 Object， 不要代理，直接调用
                return method.invoke(this, args);
            } catch (IllegalAccessException e) {
                LOG.error(e.getMessage() + " className:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName, e);
                return null;
            }
        }
        if ("toString".equals(methodName) && parameterTypes.length == 0) {
            return this.toString();
        }
        if ("hashCode".equals(methodName) && parameterTypes.length == 0) {
            return this.hashCode();
        }
        if ("equals".equals(methodName) && parameterTypes.length == 1) {
            return this.equals(args[0]);
        }

        boolean serviceTop = false;

        Transaction transaction = null;

        // cat 监控实现
        if (cat) {
            if (TraceThreadContext.get() == null) {
                serviceTop = true;
                transaction = Cat.newTransaction("Service", method.getDeclaringClass().getName().concat(".").concat(methodName).concat(".top"));

                MessageTree tree = Cat.getManager().getThreadLocalMessageTree();
                String messageId = tree.getMessageId();

                if (messageId == null) {
                    messageId = Cat.createMessageId();
                    tree.setMessageId(messageId);
                }

                String childId = Cat.getProducer().createRpcServerId("default");

                String root = tree.getRootMessageId();

                if (root == null) {
                    root = messageId;
                }
                Cat.logEvent(CatConstants.TYPE_REMOTE_CALL, "", Event.SUCCESS, childId);

                KoalasTrace koalasTrace = new KoalasTrace();
                koalasTrace.setChildId(childId);
                koalasTrace.setParentId(messageId);
                koalasTrace.setRootId(root);
                TraceThreadContext.set(koalasTrace);
            } else {
                KoalasTrace currentKoalasTrace = TraceThreadContext.get();
                String child_Id = Cat.getProducer().createRpcServerId("default");
                Cat.logEvent(CatConstants.TYPE_REMOTE_CALL, "", Event.SUCCESS, child_Id);
                currentKoalasTrace.setChildId(child_Id);
            }
        }


        try {
            TTransport socket = null;
            int currTryTimes = 0;
            //  重试循环
            while (currTryTimes++ < retryTimes) {
                // 获取服务端对象
                ServerObject serverObject = icluster.getObjectForRemote();
                if (serverObject == null)
                    throw new TException("no server list to use :" + koalasClientProxy.getServiceInterface());
                // 获取池对象
                GenericObjectPool<TTransport> genericObjectPool = serverObject.getGenericObjectPool();
                try {
                    long before = System.currentTimeMillis();
                    // 从池中获取对象
                    socket = genericObjectPool.borrowObject();
                    long after = System.currentTimeMillis();
                    LOG.debug("get Object from pool with {} ms" + " className:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName, after - before);
                } catch (Exception e) {
                    if (socket != null)
                        // 返还对象
                        genericObjectPool.returnObject(socket);
                    LOG.error(e.getMessage() + " className:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName, e);
                    if (transaction != null && cat)
                        transaction.setStatus(e);
                    throw new TException("borrowObject error :" + koalasClientProxy.getServiceInterface());
                }

                Object obj = koalasClientProxy.getInterfaceClientInstance(socket, serverObject.getRemoteServer().getServer());

                if (obj instanceof TAsyncClient) {
                    ((TAsyncClient) obj).setTimeout(asyncTimeOut);
                    if (args.length < 1) {
                        genericObjectPool.returnObject(socket);
                        throw new TException("args number error,className:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName);
                    }

                    Object argslast = args[args.length - 1];
                    if (!(argslast instanceof AsyncMethodCallback)) {
                        genericObjectPool.returnObject(socket);
                        throw new TException("args type error,className:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName);
                    }

                    AsyncMethodCallback callback = (AsyncMethodCallback) argslast;
                    ReleaseResourcesKoalasAsyncCallBack releaseResourcesKoalasAsyncCallBack = new ReleaseResourcesKoalasAsyncCallBack(callback, serverObject, socket);
                    args[args.length - 1] = releaseResourcesKoalasAsyncCallBack;

                }
                try {
                    Object o = method.invoke(obj, args);
                    if (socket instanceof TSocket) {
                        genericObjectPool.returnObject(socket);

                    }
                    if (transaction != null && cat)
                        transaction.setStatus(Transaction.SUCCESS);
                    return o;
                } catch (Exception e) {
                    Throwable cause = (e.getCause() == null) ? e : e.getCause();

                    if (cause instanceof TApplicationException) {
                        if (((TApplicationException) cause).getType() == 6666) {
                            LOG.info("serverName【{}】,method:【{}】 thread pool is busy ,retry it!,error message from server 【{}】", koalasClientProxy.getServiceInterface(), methodName, ((TApplicationException) cause).getMessage());
                            if (socket != null) {
                                genericObjectPool.returnObject(socket);
                            }
                            Thread.yield();
                            if (retryRequest)
                                continue;
                            if (transaction != null && cat)
                                transaction.setStatus(cause);
                            throw new TApplicationException("serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName + "error,thread pool is busy,error message:" + ((TApplicationException) cause).getMessage());
                        }

                        if (((TApplicationException) cause).getType() == 9999) {
                            LOG.error("serverName【{}】,method:【{}】 ,error message from server 【{}】", koalasClientProxy.getServiceInterface(), methodName, ((TApplicationException) cause).getMessage());
                            if (socket != null) {
                                genericObjectPool.returnObject(socket);
                            }
                            if (transaction != null && cat)
                                transaction.setStatus(cause);
                            throw new RSAException("server ras error,serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName, cause);
                        }

                        if (((TApplicationException) cause).getType() == 6699) {
                            LOG.error("serverName【{}】,method:【{}】 ,this client is not rsa support,please get the privateKey and publickey ,error message from server【{}】", koalasClientProxy.getServiceInterface(), methodName, ((TApplicationException) cause).getMessage());
                            if (socket != null) {
                                genericObjectPool.returnObject(socket);
                            }
                            if (transaction != null && cat)
                                transaction.setStatus(cause);
                            throw new RSAException("this client is not rsa support,please get the privateKey and publickey with server,serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName, cause);
                        }

                        if (((TApplicationException) cause).getType() == TApplicationException.INTERNAL_ERROR) {
                            LOG.error("serverName【{}】,method:【{}】,the remote server process error:【{}】", koalasClientProxy.getServiceInterface(), methodName, ((TApplicationException) cause).getMessage());
                            if (socket != null) {
                                genericObjectPool.returnObject(socket);
                            }
                            if (transaction != null && cat)
                                transaction.setStatus(cause);
                            throw new TException("server process error, serviceName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName + ",error message:" + ((TApplicationException) cause).getMessage(), cause);
                        }

                        if (((TApplicationException) cause).getType() == TApplicationException.MISSING_RESULT) {
                            if (socket != null) {
                                genericObjectPool.returnObject(socket);
                            }
                            return null;
                        }
                    }

                    if (cause instanceof RSAException) {
                        LOG.error("this client privateKey or publicKey is error,please check it! ,serverName【{}】,method 【{}】", koalasClientProxy.getServiceInterface(), methodName);
                        if (socket != null) {
                            genericObjectPool.returnObject(socket);
                        }
                        if (transaction != null && cat)
                            transaction.setStatus(cause);
                        throw new RSAException("this client privateKey or publicKey is error,please check it!" + "serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName);
                    }

                    if (cause instanceof OutMaxLengthException) {
                        LOG.error((cause).getMessage(), cause);
                        if (socket != null) {
                            genericObjectPool.returnObject(socket);
                        }
                        if (transaction != null && cat)
                            transaction.setStatus(cause);
                        throw new OutMaxLengthException("to big content!" + "serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName, cause);
                    }

                    if (cause.getCause() != null && cause.getCause() instanceof ConnectException) {
                        LOG.error("the server【{}】 maybe is shutdown ,retry it! serverName【{}】,method 【{}】", serverObject.getRemoteServer(), koalasClientProxy.getServiceInterface(), methodName);
                        if (socket != null) {
                            genericObjectPool.returnObject(socket);
                        }
                        if (retryRequest)
                            continue;
                        if (transaction != null && cat)
                            transaction.setStatus(cause.getCause());
                        throw new TException("serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName + "error,maybe is shutdown,error message:" + ((TApplicationException) cause).getMessage());
                    }

                    if (cause.getCause() != null && cause.getCause() instanceof SocketTimeoutException) {
                        LOG.error("read timeout SocketTimeoutException --serverName【{}】,method：【{}】", koalasClientProxy.getServiceInterface(), methodName);
                        if (socket != null) {
                            try {
                                genericObjectPool.invalidateObject(socket);
                            } catch (Exception e1) {
                                LOG.error("invalidateObject error", e);
                                if (transaction != null && cat)
                                    transaction.setStatus(e1);
                                throw new TException(new IllegalStateException("SocketTimeout and invalidateObject error,className:" + koalasClientProxy.getServiceInterface()) + ",method:" + methodName);

                            }
                        }
                        if (transaction != null && cat)
                            transaction.setStatus(cause.getCause());
                        throw new TException(new SocketTimeoutException("SocketTimeout by --serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName));
                    }

                    if (cause instanceof TTransportException) {
                        if (((TTransportException) cause).getType() == TTransportException.END_OF_FILE) {
                            LOG.error("TTransportException,END_OF_FILE! {}--serverName【{}】,method：【{}】", serverObject.getRemoteServer(), koalasClientProxy.getServiceInterface(), methodName);
                            if (socket != null) {
                                try {
                                    genericObjectPool.invalidateObject(socket);
                                } catch (Exception e1) {
                                    LOG.error("invalidateObject error", e);
                                    if (transaction != null && cat)
                                        transaction.setStatus(e1);
                                    throw new TException(new IllegalStateException("TTransportException.END_OF_FILE and invalidateObject error,className:" + koalasClientProxy.getServiceInterface()) + ",method:" + methodName);
                                }
                            }
                            if (transaction != null && cat)
                                transaction.setStatus(cause);
                            throw new TException(new TTransportException("the remote server is shutdown!" + serverObject.getRemoteServer() + koalasClientProxy.getServiceInterface()));
                        }
                        if (cause.getCause() != null && cause.getCause() instanceof SocketException) {
                            if (socket != null) {
                                try {
                                    genericObjectPool.invalidateObject(socket);
                                } catch (Exception e1) {
                                    LOG.error("invalidateObject error", e);
                                    if (transaction != null && cat)
                                        transaction.setStatus(e1);
                                    throw new TException(new IllegalStateException("TTransportException cause by SocketException and invalidateObject error,className:" + koalasClientProxy.getServiceInterface()) + ",method:" + methodName);
                                }
                            }
                            if (retryRequest)
                                continue;
                            if (transaction != null && cat)
                                transaction.setStatus(cause);
                            throw new TException(new TTransportException("the remote server is shutdown!" + serverObject.getRemoteServer() + koalasClientProxy.getServiceInterface()));
                        }
                    }

                    if (cause instanceof TBase) {
                        LOG.warn("thrift exception--{}, {}--serverName【{}】,method：【{}】", cause.getClass().getName(), serverObject.getRemoteServer(), koalasClientProxy.getServiceInterface(), methodName);
                        if (socket != null) {
                            genericObjectPool.returnObject(socket);
                        }
                        if (transaction != null && cat)
                            transaction.setStatus(cause);
                        throw cause;
                    }

                    if (socket != null)
                        genericObjectPool.invalidateObject(socket);
                    LOG.error("invoke server error,server ip -【{}】,port -【{}】--serverName【{}】,method：【{}】", serverObject.getRemoteServer().getIp(), serverObject.getRemoteServer().getPort(), koalasClientProxy.getServiceInterface(), methodName);
                    if (transaction != null && cat)
                        transaction.setStatus(cause);
                    throw cause;
                }
            }
            TException finallyException = new TException("error!retry time-out of:" + retryTimes + "!!! " + "serverName:" + koalasClientProxy.getServiceInterface() + ",method:" + methodName);
            if (transaction != null && cat)
                transaction.setStatus(finallyException);
            throw finallyException;
        } finally {
            if (transaction != null && cat) {
                transaction.complete();
            }
            if (serviceTop && cat) {
                TraceThreadContext.remove();
            }
        }
    }

}
