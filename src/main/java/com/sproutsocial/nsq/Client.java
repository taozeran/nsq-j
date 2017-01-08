package com.sproutsocial.nsq;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.google.common.util.concurrent.MoreExecutors;
import net.jcip.annotations.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocketFactory;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

import static com.google.common.base.Preconditions.*;

@ThreadSafe
public class Client {

    private final ObjectMapper mapper;

    private final Set<Publisher> publishers;
    private final Set<Subscriber> subscribers;
    private final Set<SubConnection> subConnections;
    private final Object subConMonitor;

    private ExecutorService executor;
    private final ScheduledExecutorService schedExecutor;
    private SSLSocketFactory sslSocketFactory;
    private byte[] authSecret;

    private static final Logger logger = LoggerFactory.getLogger(Client.class);
    private static final Client defaultClient = new Client();

    public Client() {
        this.publishers = Collections.synchronizedSet(new HashSet<Publisher>());
        this.subscribers = Collections.synchronizedSet(new HashSet<Subscriber>());
        this.subConnections = Collections.synchronizedSet(new HashSet<SubConnection>());
        this.subConMonitor = new Object();
        this.schedExecutor = Executors.newScheduledThreadPool(2, Util.threadFactory("nsq-sched"));
        this.mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);
    }

    //--------------------------

    public static Client getDefaultClient() {
        return defaultClient;
    }

    /**
     * Stops all subscribers, waits for in-flight messages to be finished or requeued, stops the executor that handles messages,
     * then stops all publishers. All connections will be closed and no threads started by this client should be running when this returns.
     * @param waitMillis Time to wait for everything to stop, in milliseconds. Soft limit that may be exceeded by about 200 ms.
     */
    public synchronized boolean stop(int waitMillis) {
        checkArgument(waitMillis > 0, "waitMillis must be greater than zero");
        logger.info("stopping nsq client");
        boolean isClean = true;
        long start = Util.clock();
        isClean &= stopSubscribers(waitMillis);

        if (executor != null && !executor.isTerminated()) {
            int timeout = Math.max((int) (waitMillis - (Util.clock() - start)), 100);
            isClean &= MoreExecutors.shutdownAndAwaitTermination(executor, timeout, TimeUnit.MILLISECONDS);
        }

        for (Publisher publisher : publishers) {
            publisher.stop();
        }

        int timeout = Math.max((int) (waitMillis - (Util.clock() - start)), 100);
        isClean &= MoreExecutors.shutdownAndAwaitTermination(schedExecutor, timeout, TimeUnit.MILLISECONDS);

        logger.debug("executor.isTerminated:{} schedExecutor.isTerminated:{} isClean:{}", executor != null ? executor.isTerminated() : "null", schedExecutor.isTerminated(), isClean);
        logger.info("nsq client stopped");
        return isClean;
    }

    /**
     * Stops all subscribers, waits for in-flight messages to be finished or requeued, then closes subscriber connections.
     * Useful if you need to perform some action before publishers are stopped,
     * you should call stop() after this to shutdown all threads.
     * @param waitMillis Time to wait for in-flight messages to be finished, in milliseconds.
     */
    public synchronized boolean stopSubscribers(int waitMillis) {
        checkArgument(waitMillis > 0, "waitMillis must be greater than zero");
        for (Subscriber subscriber : subscribers) {
            subscriber.stop();
        }
        synchronized (subConMonitor) {
            if (!subConnections.isEmpty()) { //don't loop until empty, try once and if we get interrupted stop right away
                logger.info("waiting for subscribers to finish in-flight messages");
                try {
                    subConMonitor.wait(waitMillis);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        boolean isClean = subConnections.isEmpty();
        for (SubConnection subCon : subConnections) {
            subCon.close();
        }
        return isClean;
    }

    public synchronized void setExecutor(ExecutorService exec) {
        checkNotNull(exec);
        checkState(this.executor == null, "executor can only be set once, must be set before subscribing");
        this.executor = exec;
    }

    public synchronized ExecutorService getExecutor() {
        if (executor == null) {
            executor = Executors.newFixedThreadPool(6, Util.threadFactory("nsq-sub"));
        }
        return executor;
    }

    public synchronized SSLSocketFactory getSSLSocketFactory() {
        return sslSocketFactory;
    }

    public synchronized void setSSLSocketFactory(SSLSocketFactory sslSocketFactory) {
        this.sslSocketFactory = sslSocketFactory;
    }

    public synchronized byte[] getAuthSecret() {
        return authSecret;
    }

    public synchronized void setAuthSecret(byte[] authSecret) {
        this.authSecret = authSecret;
    }

    public synchronized void setAuthSecret(String authSecret) {
        this.authSecret = authSecret.getBytes();
    }

    public final ObjectMapper getObjectMapper() {
        return mapper;
    }

    //--------------------------
    // package private

    void addPublisher(Publisher publisher) {
        publishers.add(publisher);
    }

    void addSubscriber(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    void addSubConnection(SubConnection subCon) {
        subConnections.add(subCon);
    }

    ScheduledExecutorService getSchedExecutor() {
        return schedExecutor;
    }

    ScheduledFuture scheduleAtFixedRate(final Runnable runnable, int initialDelay, int period, boolean jitter) {
        if (jitter) {
            initialDelay = (int) (initialDelay * 0.1 + Math.random() * initialDelay * 0.9);
        }
        return schedExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                try {
                    runnable.run();
                }
                catch (Throwable t) {
                    logger.error("task error", t);
                }
            }
        }, initialDelay, period, TimeUnit.MILLISECONDS);
    }

    void schedule(final Runnable runnable, int delay) {
        schedExecutor.schedule(new Runnable() {
            public void run() {
                try {
                    runnable.run();
                }
                catch (Throwable t) {
                    logger.error("task error", t);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    void connectionClosed(SubConnection closedCon) {
        synchronized (subConMonitor) {
            subConnections.remove(closedCon);
            if (subConnections.isEmpty()) {
                subConMonitor.notifyAll();
            }
        }
    }

}
