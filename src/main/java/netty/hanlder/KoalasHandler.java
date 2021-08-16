package netty.hanlder;

import com.dianping.cat.Cat;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.spi.MessageTree;
import exceptions.RSAException;
import heartbeat.impl.HeartbeatServiceImpl;
import heartbeat.service.HeartbeatService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.commons.lang3.StringUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TIOStreamTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import protocol.KoalasBinaryProtocol;
import protocol.KoalasTrace;
import server.config.AbstractKoalsServerPublisher;
import server.domain.ErrorType;
import transport.TKoalasFramedTransport;
import utils.IPUtil;
import utils.KoalasExceptionUtil;
import utils.KoalasRsaUtil;
import utils.TraceThreadContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.MessageFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Copyright (C) 2018
 * All rights reserved
 * User: yulong.zhang
 * Date:2018年11月23日11:13:33
 */
public class KoalasHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final static Logger logger = LoggerFactory.getLogger(netty.hanlder.KoalasHandler.class);

    private TProcessor tprocessor;
    private TProcessor genericTprocessor;

    private ExecutorService executorService;

    private String privateKey;

    private String publicKey;

    private String className;

    private int maxLength;

    private boolean cat;

    public KoalasHandler(AbstractKoalsServerPublisher serverPublisher, ExecutorService executorService) {
        this.executorService = executorService;
        this.privateKey = serverPublisher.getPrivateKey();
        this.publicKey = serverPublisher.getPublicKey();
        this.className = serverPublisher.getServiceInterface().getName();
        this.cat = serverPublisher.isCat();
        this.tprocessor = serverPublisher.getTProcessor();
        this.genericTprocessor = serverPublisher.getGenericTProcessor();
        this.maxLength = serverPublisher.getMaxLength();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {

        int i = msg.readableBytes();
        byte[] b = new byte[i];
        msg.readBytes(b);

        TMessage tMessage;
        KoalasTrace koalasTrace;
        boolean ifUserProtocol;
        boolean generic;
        String genericMethodName;
        boolean thriftNative;
        if (b[4] == TKoalasFramedTransport.first && b[5] == TKoalasFramedTransport.second) {
            ifUserProtocol = true;
            KoalasMessage koalasMessage = getKoalasTMessage(b);
            tMessage = koalasMessage.gettMessage();
            koalasTrace = koalasMessage.getKoalasTrace();
            generic = koalasMessage.isGeneric();
            genericMethodName = koalasMessage.getGenericMethodName();
            thriftNative = koalasMessage.isThriftNative();
        } else {
            ifUserProtocol = false;
            KoalasMessage koalasMessage = getTMessage(b);
            tMessage = koalasMessage.gettMessage();
            koalasTrace = koalasMessage.getKoalasTrace();
            generic = koalasMessage.isGeneric();
            genericMethodName = koalasMessage.getGenericMethodName();
            thriftNative = koalasMessage.isThriftNative();
        }
        TProcessor localTprocessor;
        if (!generic) {
            localTprocessor = tprocessor;
        } else {
            localTprocessor = genericTprocessor;
        }

        ByteArrayInputStream inputStream = new ByteArrayInputStream(b);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        TIOStreamTransport tioStreamTransportInput = new TIOStreamTransport(inputStream);
        TIOStreamTransport tioStreamTransportOutput = new TIOStreamTransport(outputStream);

        TKoalasFramedTransport inTransport = new TKoalasFramedTransport(tioStreamTransportInput, 2048000);
        inTransport.setReadMaxLength_(maxLength);
        TKoalasFramedTransport outTransport = new TKoalasFramedTransport(tioStreamTransportOutput, 2048000, ifUserProtocol);

        if (this.privateKey != null && this.publicKey != null) {
            if (b[8] != (byte) 1 || !(b[4] == TKoalasFramedTransport.first && b[5] == TKoalasFramedTransport.second)) {
                logger.error("rsa error the client is not ras support!  className={}", className);
                handlerException(b, ctx, new RSAException("rsa error"), ErrorType.APPLICATION, privateKey, publicKey, thriftNative);
                return;
            }
        }

        if (b[4] == TKoalasFramedTransport.first && b[5] == TKoalasFramedTransport.second) {

            //heartbeat
            if (b[7] == (byte) 2) {
                TProcessor tprocessorheartbeat = new HeartbeatService.Processor<>(new HeartbeatServiceImpl());
                outTransport.setHeartbeat((byte) 2);
                TProtocolFactory tProtocolFactory = new KoalasBinaryProtocol.Factory();
                ((KoalasBinaryProtocol.Factory) tProtocolFactory).setThriftNative(thriftNative);
                TProtocol in = tProtocolFactory.getProtocol(inTransport);
                TProtocol out = tProtocolFactory.getProtocol(outTransport);
                try {
                    tprocessorheartbeat.process(in, out);
                    ctx.writeAndFlush(outputStream);
                    return;
                } catch (Exception e) {
                    logger.error("heartbeat error e,className:{}", className);
                    handlerException(b, ctx, e, ErrorType.APPLICATION, privateKey, publicKey, thriftNative);
                    return;
                }
            }

            //rsa support
            if (b[8] == (byte) 1) {
                //in
                inTransport.setPrivateKey(this.privateKey);
                inTransport.setPublicKey(this.publicKey);

                //out
                outTransport.setRsa((byte) 1);
                outTransport.setPrivateKey(this.privateKey);
                outTransport.setPublicKey(this.publicKey);
            }
        }
        TProtocolFactory tProtocolFactory = new KoalasBinaryProtocol.Factory();
        ((KoalasBinaryProtocol.Factory) tProtocolFactory).setThriftNative(thriftNative);
        TProtocol in = tProtocolFactory.getProtocol(inTransport);
        TProtocol out = tProtocolFactory.getProtocol(outTransport);

        String methodName = generic ? genericMethodName : tMessage.name;

        try {
            executorService.execute(new NettyRunable(ctx, in, out, outputStream, localTprocessor, b, privateKey, publicKey, className, methodName, koalasTrace, cat));
        } catch (RejectedExecutionException e) {
            logger.error(e.getMessage() + ErrorType.THREAD + ",className:" + className, e);
            handlerException(b, ctx, e, ErrorType.THREAD, privateKey, publicKey, thriftNative);
        }
    }


    public static class NettyRunable implements Runnable {

        private ChannelHandlerContext ctx;
        private TProtocol in;
        private TProtocol out;
        private ByteArrayOutputStream outputStream;
        private TProcessor tprocessor;
        private byte[] b;
        private String privateKey;
        private String publicKey;
        private String className;
        private String methodName;
        private KoalasTrace koalasTrace;
        private boolean cat;
        private boolean thriftNative;

        public NettyRunable(ChannelHandlerContext ctx, TProtocol in, TProtocol out, ByteArrayOutputStream outputStream, TProcessor tprocessor, byte[] b, String privateKey, String publicKey, String className, String methodName, KoalasTrace koalasTrace, boolean cat) {
            this.ctx = ctx;
            this.in = in;
            this.out = out;
            this.outputStream = outputStream;
            this.tprocessor = tprocessor;
            this.b = b;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.className = className;
            this.methodName = methodName;
            this.koalasTrace = koalasTrace;
            this.cat = cat;
        }

        @Override
        public void run() {
            Transaction transaction = null;
            if (StringUtils.isNotEmpty(methodName) && cat) {
                transaction = Cat.newTransaction("Service", className.concat(".").concat(methodName));
                if (koalasTrace.getRootId() != null) {
                    String rootId = koalasTrace.getRootId();
                    String childId = koalasTrace.getChildId();
                    String parentId = koalasTrace.getParentId();
                    MessageTree tree = Cat.getManager().getThreadLocalMessageTree();
                    tree.setParentMessageId(parentId);
                    tree.setRootMessageId(rootId);
                    tree.setMessageId(childId);
                    //String child_Id = Cat.getProducer().createRpcServerId("default");
                    //Cat.logEvent(CatConstants.TYPE_REMOTE_CALL, "", Event.SUCCESS, child_Id);
                    KoalasTrace currentKoalasTrace = new KoalasTrace();
                    currentKoalasTrace.setParentId(childId);
                    currentKoalasTrace.setRootId(rootId);
                    //CurrentKoalasTrace.setChildId ( child_Id );
                    TraceThreadContext.set(currentKoalasTrace);
                } else {
                    MessageTree tree = Cat.getManager().getThreadLocalMessageTree();
                    String messageId = tree.getMessageId();
                    if (messageId == null) {
                        messageId = Cat.createMessageId();
                        tree.setMessageId(messageId);
                    }
                    String root = tree.getRootMessageId();

                    if (root == null) {
                        root = messageId;
                    }
                    KoalasTrace koalasTrace = new KoalasTrace();
                    koalasTrace.setParentId(messageId);
                    koalasTrace.setRootId(root);
                    TraceThreadContext.set(koalasTrace);
                }
            }
            try {
                tprocessor.process(in, out);
                ctx.writeAndFlush(outputStream);
                if (transaction != null && cat)
                    transaction.setStatus(Transaction.SUCCESS);
            } catch (Exception e) {
                if (transaction != null && cat)
                    transaction.setStatus(e);
                logger.error(e.getMessage() + ErrorType.APPLICATION + ",className:" + className, e);
                handlerException(this.b, ctx, e, ErrorType.APPLICATION, privateKey, publicKey, thriftNative);
            } finally {
                if (transaction != null && cat) {
                    transaction.complete();
                    if (koalasTrace.getRootId() != null) {
                        TraceThreadContext.remove();
                    }
                }
            }
        }

    }

    public static void handlerException(byte[] b, ChannelHandlerContext ctx, Exception e, ErrorType type, String privateKey, String publicKey, boolean thriftNative) {

        String serverIp = IPUtil.getIpV4();
        String exceptionMessage = KoalasExceptionUtil.getExceptionInfo(e);
        String value = MessageFormat.format("error from server: {0}  invoke error : {1}", serverIp, exceptionMessage);

        boolean ifUserProtocol;
        if (b[4] == TKoalasFramedTransport.first && b[5] == TKoalasFramedTransport.second) {
            ifUserProtocol = true;
        } else {
            ifUserProtocol = false;
        }

        ByteArrayInputStream inputStream = new ByteArrayInputStream(b);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        TIOStreamTransport tioStreamTransportInput = new TIOStreamTransport(inputStream);
        TIOStreamTransport tioStreamTransportOutput = new TIOStreamTransport(outputStream);

        TKoalasFramedTransport inTransport = new TKoalasFramedTransport(tioStreamTransportInput);
        TKoalasFramedTransport outTransport = new TKoalasFramedTransport(tioStreamTransportOutput, 16384000, ifUserProtocol);

        TProtocolFactory tProtocolFactory = new KoalasBinaryProtocol.Factory();
        ((KoalasBinaryProtocol.Factory) tProtocolFactory).setThriftNative(thriftNative);
        TProtocol in = tProtocolFactory.getProtocol(inTransport);
        TProtocol out = tProtocolFactory.getProtocol(outTransport);

        if (ifUserProtocol) {
            //rsa support
            if (b[8] == (byte) 1) {
                if (!(e instanceof RSAException)) {
                    //in
                    inTransport.setPrivateKey(privateKey);
                    inTransport.setPublicKey(publicKey);

                    //out
                    outTransport.setRsa((byte) 1);
                    outTransport.setPrivateKey(privateKey);
                    outTransport.setPublicKey(publicKey);
                } else {
                    try {
                        TMessage tMessage = new TMessage("", TMessageType.EXCEPTION, -1);
                        TApplicationException exception = new TApplicationException(9999, value);
                        out.writeMessageBegin(tMessage);
                        exception.write(out);
                        out.writeMessageEnd();
                        out.getTransport().flush();
                        ctx.writeAndFlush(outputStream);
                        return;
                    } catch (Exception e2) {
                        logger.error(e2.getMessage(), e2);
                    }
                }
            } else {
                if (e instanceof RSAException) {
                    try {
                        TMessage tMessage = new TMessage("", TMessageType.EXCEPTION, -1);
                        TApplicationException exception = new TApplicationException(6699, value);
                        out.writeMessageBegin(tMessage);
                        exception.write(out);
                        out.writeMessageEnd();
                        out.getTransport().flush();
                        ctx.writeAndFlush(outputStream);
                        return;
                    } catch (Exception e2) {
                        logger.error(e2.getMessage(), e2);
                    }
                }
            }
        }

        try {
            TMessage message = in.readMessageBegin();
            TProtocolUtil.skip(in, TType.STRUCT);
            in.readMessageEnd();

            TApplicationException tApplicationException = null;
            switch (type) {
                case THREAD:
                    tApplicationException = new TApplicationException(6666, value);
                    break;
                case APPLICATION:
                    tApplicationException = new TApplicationException(TApplicationException.INTERNAL_ERROR, value);
                    break;
            }

            out.writeMessageBegin(new TMessage(message.name, TMessageType.EXCEPTION, message.seqid));
            tApplicationException.write(out);
            out.writeMessageEnd();
            out.getTransport().flush();
            ctx.writeAndFlush(outputStream);
            logger.info("handlerException:" + tApplicationException.getType() + ":" + value);
        } catch (TException e1) {
            logger.error("unknown Exception:" + type + ":" + value, e1);
            ctx.close();
        }
    }


    private KoalasMessage getTMessage(byte[] b) {
        byte[] buff = new byte[b.length - 4];
        System.arraycopy(b, 4, buff, 0, buff.length);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(buff);
        TIOStreamTransport tioStreamTransportInput = new TIOStreamTransport(inputStream);
        TProtocol tBinaryProtocol = new KoalasBinaryProtocol(tioStreamTransportInput, true);
        TMessage tMessage = null;
        KoalasTrace koalasTrace;
        String genericMethodName;
        boolean generic;
        boolean thriftNative;
        try {
            tMessage = tBinaryProtocol.readMessageBegin();
            koalasTrace = ((KoalasBinaryProtocol) tBinaryProtocol).getKoalasTrace();
            generic = ((KoalasBinaryProtocol) tBinaryProtocol).isGeneric();
            genericMethodName = ((KoalasBinaryProtocol) tBinaryProtocol).getGenericMethodName();
            thriftNative = ((KoalasBinaryProtocol) tBinaryProtocol).isThriftNative();
        } catch (Exception e) {
            return new KoalasMessage(new TMessage(), new KoalasTrace(), false, StringUtils.EMPTY, false);
        }
        return new KoalasMessage(tMessage, koalasTrace, generic, genericMethodName, thriftNative);
    }

    private KoalasMessage getKoalasTMessage(byte[] b) {

        byte[] buff = new byte[b.length - 4];
        System.arraycopy(b, 4, buff, 0, buff.length);

        int size = buff.length;
        byte[] request = new byte[size - 6];
        byte[] header = new byte[6];
        System.arraycopy(buff, 6, request, 0, size - 6);
        System.arraycopy(buff, 0, header, 0, 6);

        //RSA
        if (header[4] == (byte) 1) {

            byte[] signLenByte = new byte[4];
            System.arraycopy(buff, 6, signLenByte, 0, 4);

            int signLen = TKoalasFramedTransport.decodeFrameSize(signLenByte);
            byte[] signByte = new byte[signLen];
            System.arraycopy(buff, 10, signByte, 0, signLen);

            String sign = "";
            try {
                sign = new String(signByte, "UTF-8");
            } catch (Exception e) {
                return new KoalasMessage(new TMessage(), new KoalasTrace(), false, StringUtils.EMPTY, false);
            }

            byte[] rsaBody = new byte[size - 10 - signLen];
            System.arraycopy(buff, 10 + signLen, rsaBody, 0, size - 10 - signLen);

            try {
                if (!KoalasRsaUtil.verify(rsaBody, publicKey, sign)) {
                    return new KoalasMessage(new TMessage(), new KoalasTrace(), false, StringUtils.EMPTY, false);
                }
                request = KoalasRsaUtil.decryptByPrivateKey(rsaBody, privateKey);
            } catch (Exception e) {
                return new KoalasMessage(new TMessage(), new KoalasTrace(), false, StringUtils.EMPTY, false);
            }
        }
        TMessage tMessage;
        KoalasTrace koalasTrace;
        boolean generic;
        String genericMethodName;
        boolean thriftNative;
        ByteArrayInputStream inputStream = new ByteArrayInputStream(request);
        TIOStreamTransport tioStreamTransportInput = new TIOStreamTransport(inputStream);
        try {
            TProtocol tBinaryProtocol = new KoalasBinaryProtocol(tioStreamTransportInput, true);
            tMessage = tBinaryProtocol.readMessageBegin();
            koalasTrace = ((KoalasBinaryProtocol) tBinaryProtocol).getKoalasTrace();
            generic = ((KoalasBinaryProtocol) tBinaryProtocol).isGeneric();
            genericMethodName = ((KoalasBinaryProtocol) tBinaryProtocol).getGenericMethodName();
            thriftNative = ((KoalasBinaryProtocol) tBinaryProtocol).isThriftNative();
        } catch (Exception e) {
            return new KoalasMessage(new TMessage(), new KoalasTrace(), false, StringUtils.EMPTY, false);
        }
        return new KoalasMessage(tMessage, koalasTrace, generic, genericMethodName, thriftNative);
    }

    private static class KoalasMessage {
        private TMessage tMessage;
        private KoalasTrace koalasTrace;
        private boolean generic;
        private String genericMethodName;
        private boolean thriftNative;

        public KoalasMessage(TMessage tMessage, KoalasTrace koalasTrace, boolean generic, String genericMethodName, boolean thriftNative) {
            this.tMessage = tMessage;
            this.koalasTrace = koalasTrace;
            this.generic = generic;
            this.genericMethodName = genericMethodName;
            this.thriftNative = thriftNative;
        }

        public boolean isThriftNative() {
            return thriftNative;
        }

        public void setThriftNative(boolean thriftNative) {
            this.thriftNative = thriftNative;
        }

        public String getGenericMethodName() {
            return genericMethodName;
        }

        public void setGenericMethodName(String genericMethodName) {
            this.genericMethodName = genericMethodName;
        }

        public TMessage gettMessage() {
            return tMessage;
        }

        public void settMessage(TMessage tMessage) {
            this.tMessage = tMessage;
        }

        public KoalasTrace getKoalasTrace() {
            return koalasTrace;
        }

        public void setKoalasTrace(KoalasTrace koalasTrace) {
            this.koalasTrace = koalasTrace;
        }

        public boolean isGeneric() {
            return generic;
        }

        public void setGeneric(boolean generic) {
            this.generic = generic;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        logger.error("exceptionCaught", cause);
        ctx.fireExceptionCaught(cause);
    }
}
