package server.config;

import io.netty.util.internal.SystemPropertyUtil;
import org.apache.thrift.TProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import server.IkoalasServer;
import server.KoalasServerPublisher;

import java.lang.reflect.InvocationTargetException;

public class AbstractKoalsServerPublisher {
    private final static Logger logger = LoggerFactory.getLogger ( AbstractKoalsServerPublisher.class );
    public static final int DEFAULT_EVENT_LOOP_THREADS;
    public static final int DEFAULT_KOALAS_THREADS;
    public static final int DEFAULT_THRIFT_ACCETT_THREAD;

    static {
        DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", Runtime.getRuntime().availableProcessors() * 2));

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.eventLoopThreads: {}", DEFAULT_EVENT_LOOP_THREADS);
        }
        DEFAULT_KOALAS_THREADS=256;
        DEFAULT_THRIFT_ACCETT_THREAD=5;
    }

    public static final String IFACE = "Iface";
    public static final String PROCESSOR = "Processor";
    public int bossThreadCount;
    public int workThreadCount;
    public int koalasThreadCount;

    public Class<?> serviceInterface;
    public String zkpath;
    public int port;
    public String env;
    public Object serviceImpl;
    public String serverType;

    public ApplicationContext applicationContext;
    public IkoalasServer ikoalasServer;


    public TProcessor getTProcessor(){
        Class iface = getSynIfaceInterface(serviceInterface);
        try {
           return getProcessorClass(serviceInterface).getDeclaredConstructor (  iface).newInstance ( serviceImpl );
        } catch (NoSuchMethodException e) {
            logger.error ( "can't find the TProcessor Constructor with Iface",e );
        } catch (IllegalAccessException e) {
            logger.error ( "IllegalAccessException with Iface" );
        } catch (InstantiationException e) {
            logger.error ( "IllegalInstantiationExceptionAccessException with Iface",e );
        } catch (InvocationTargetException e) {
            logger.error ( "InvocationTargetException with Iface",e );
        }

        return null;
    }

    private Class<?> getSynIfaceInterface(Class<?> serviceInterface) {
        Class<?>[] classes = serviceInterface.getClasses();
        for (Class c : classes)
            if (c.isMemberClass() && c.isInterface() && c.getSimpleName().equals( IFACE )) {
                return c;
            }
        throw new IllegalArgumentException("serviceInterface must contain Sub Interface of Iface");
    }

    private Class<TProcessor> getProcessorClass(Class<?> serviceInterface) {
        Class<?>[] classes = serviceInterface.getClasses();
        for (Class c : classes)
            if (c.isMemberClass() && !c.isInterface() && c.getSimpleName().equals( PROCESSOR )) {
                return c;
            }
        throw new IllegalArgumentException("serviceInterface must contain Sub Interface of Processor");
    }
}
