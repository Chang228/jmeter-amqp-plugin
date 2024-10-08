package com.zeroclue.jmeter.protocol.amqp;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConsumerCancelledException;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.ShutdownSignalException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.groovy.util.Maps;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AMQPConsumer extends AMQPSampler implements Interruptible, TestStateListener {

    private static final long serialVersionUID = 7480863561320459091L;

    private static final Logger log = LoggerFactory.getLogger(AMQPConsumer.class);

    private static final Map<Class<? extends Exception>, String> EXCEPTION_TO_RESPONSE_CODE = Maps.of(
        IOException.class, "100",
        InterruptedException.class, "200",
        ConsumerCancelledException.class, "300",
        ShutdownSignalException.class, "400"
    );

    //++ These are JMX names, and must not be changed
    private static final String PREFETCH_COUNT          = "AMQPConsumer.PrefetchCount";
    private static final String READ_RESPONSE           = "AMQPConsumer.ReadResponse";
    private static final String PURGE_QUEUE             = "AMQPConsumer.PurgeQueue";
    private static final String AUTO_ACK                = "AMQPConsumer.AutoAck";
    private static final String RECEIVE_TIMEOUT         = "AMQPConsumer.ReceiveTimeout";
    private static final String USE_TX                  = "AMQPConsumer.UseTx";
    private static final String CORRELATION_ID          = "AMQPConsumer.CorrelationId";
    private static final String MESSAGE_ID              = "AMQPConsumer.MessageId";

    public static final String TIMESTAMP_PARAMETER      = "Timestamp";
    public static final String EXCHANGE_PARAMETER       = "Exchange";
    public static final String ROUTING_KEY_PARAMETER    = "Routing Key";
    public static final String DELIVERY_TAG_PARAMETER   = "Delivery Tag";
    public static final String APP_ID_PARAMETER         = "Application ID";
    public static final int DEFAULT_ITERATIONS = -1;

    public static final boolean DEFAULT_PURGE_QUEUE = false;
    public static final boolean DEFAULT_AUTO_ACK = true;
    public static final boolean DEFAULT_READ_RESPONSE = true;
    public static final boolean DEFAULT_USE_TX = false;
    private static final int DEFAULT_PREFETCH_COUNT = 0;    // unlimited
    public static final String DEFAULT_PREFETCH_COUNT_STRING = Integer.toString(DEFAULT_PREFETCH_COUNT);
    public static final String DEFAULT_RESPONSE_CODE = "500";
    public static final String DEFAULT_RECEIVE_TIMEOUT = "";

    private transient DeliverCallback consumer;
    private transient BlockingQueue<Delivery> response;
    private transient String consumerTag;

    public AMQPConsumer() {
        super();
    }

    @Override
    public int getIterationsAsInt() {
        String intVale = getPropertyAsString(AMQPSampler.ITERATIONS);
        if(StringUtils.isEmpty(intVale)) return DEFAULT_ITERATIONS;
        return getPropertyAsInt(AMQPSampler.ITERATIONS, DEFAULT_ITERATIONS);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setSuccessful(false);
        result.setResponseCode(DEFAULT_RESPONSE_CODE);

        trace("AMQPConsumer.sample()");


        try {

            initChannel();

            if (purgeQueue()) {
                doPurgeQueue();
            }

            // only do this once per thread, otherwise it slows down the consumption by appx 50%
            if (consumer == null) {
                log.info("Creating consumer");
                response = new LinkedBlockingQueue<>(1);
                consumer = (consumerTag, delivery) -> {
                    log.info("receive "+delivery.getProperties().getCorrelationId());
                    response.offer(delivery);
                };
            }
            if (consumerTag == null) {
                log.info("Starting basic consumer");
                consumerTag = channel.basicConsume(getQueue(), autoAck(), consumer, consumerTag  -> { });
            }
        } catch (Exception ex) {
            log.error("Failed to initialize channel", ex);
            result.setResponseMessage(ex.toString());
            return result;
        }

        /*
         * Perform the sampling
         */

        // aggregate samples
        int loop = getIterationsAsInt();
        result.sampleStart();                      // start timing
        Delivery delivery = null;

        try {
            StopWatch stopWatch = StopWatch.createStarted();
            String correlationId = getCorrelationId();
            String messageId = getMessageId();
            for (int idx = 0; loop < 0 || idx < loop; idx++) {
                delivery = response.poll(getReceiveTimeoutAsInt(), TimeUnit.MILLISECONDS);

                if (delivery == null || stopWatch.getTime(TimeUnit.MILLISECONDS) > getReceiveTimeoutAsInt()) {
                    String message = "Time out";
                    if(StringUtils.isNoneEmpty(correlationId)){
                        message+=", correlationId: "+correlationId;
                    }
                    if(StringUtils.isNoneEmpty(messageId)){
                        message+=", messageId: "+messageId;
                    }
                    result.setResponseMessage(message);
                    return result;
                }
                if(StringUtils.isNoneEmpty(correlationId)){
                    if( !correlationId.equals(delivery.getProperties().getCorrelationId())){
                        log.warn("Unexcepted correlationId {}",delivery.getProperties().getCorrelationId());
                        if (!autoAck()) {
                            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                        }
                        continue;
                    }
                }
                if(StringUtils.isNoneEmpty(messageId)){
                    if(!messageId.equals(delivery.getProperties().getMessageId())) {
                        log.warn("Unexcepted messageId {}",delivery.getProperties().getMessageId());
                        if (!autoAck()) {
                            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                        }
                        continue;
                    }
                }

                /*
                 * Set up the sample result details
                 */
                if (getReadResponseAsBoolean()) {
                    String responseStr = new String(delivery.getBody());
                    log.debug("read response {}",delivery.getBody().length);
                    result.setResponseData(responseStr, StandardCharsets.UTF_8.name());
                } else {
                    result.setResponseData("Read response failed", StandardCharsets.UTF_8.name());
                }

                if (!autoAck()) {
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                }
                break;
            }

            // commit the sample
            if (getUseTx()) {
                channel.txCommit();
            }

            /*
             * Set up the sample result details
             */
            result.setDataType(SampleResult.TEXT);
            result.setResponseHeaders(delivery != null ? formatHeaders(delivery) : null);

            result.setResponseMessage("OK");
            result.setResponseCodeOK();
            result.setSuccessful(true);
        } catch(InterruptedException ie) {
            Thread.currentThread().interrupt();     // re-interrupt the current thread
            response = null;
            consumer = null;
            consumerTag = null;
            log.warn("Interrupted while attempting to consume", ie);
            result.setResponseCode(EXCEPTION_TO_RESPONSE_CODE.get(ie.getClass()));
            result.setResponseMessage(ie.getMessage());
        } catch (ShutdownSignalException | ConsumerCancelledException | IOException e) {
            response = null;
            consumer = null;
            consumerTag = null;
            log.warn("AMQP consumer failed to consume", e);
            result.setResponseCode(EXCEPTION_TO_RESPONSE_CODE.get(e.getClass()));
            result.setResponseMessage(e.getMessage());
            interrupt();
        } finally {
            result.sampleEnd();         // end timing
        }

        trace("AMQPConsumer.sample ended");

        return result;
    }



    /**
     * @return the whether to purge the queue
     */
    public String getPurgeQueue() {
        return getPropertyAsString(PURGE_QUEUE);
    }

    public void setPurgeQueue(String content) {
        setProperty(PURGE_QUEUE, content);
    }

    public void setPurgeQueue(Boolean purgeQueue) {
        setProperty(PURGE_QUEUE, purgeQueue.toString());
    }

    public boolean purgeQueue() {
        return Boolean.parseBoolean(getPurgeQueue());
    }

    private void doPurgeQueue() {
        log.info("Purging queue {}", getQueue());
        try {
            channel.queuePurge(getQueue());
        } catch (IOException e) {
            log.error("Failed to purge queue {}", getQueue(), e);
        }
    }

    /**
     * @return the whether to auto ack
     */
    public String getAutoAck() {
        return getPropertyAsString(AUTO_ACK);
    }

    public void setAutoAck(String content) {
        setProperty(AUTO_ACK, content);
    }

    public void setAutoAck(Boolean autoAck) {
        setProperty(AUTO_ACK, autoAck.toString());
    }

    public boolean autoAck() {
        return getPropertyAsBoolean(AUTO_ACK);
    }

    protected int getReceiveTimeoutAsInt() {
        if (getPropertyAsInt(RECEIVE_TIMEOUT) < 1) {
            return DEFAULT_TIMEOUT;
        }

        return getPropertyAsInt(RECEIVE_TIMEOUT);
    }

    public String getReceiveTimeout() {
        return getPropertyAsString(RECEIVE_TIMEOUT, DEFAULT_TIMEOUT_STRING);
    }

    public void setReceiveTimeout(String s) {
        setProperty(RECEIVE_TIMEOUT, s);
    }

    public String getPrefetchCount() {
        return getPropertyAsString(PREFETCH_COUNT, DEFAULT_PREFETCH_COUNT_STRING);
    }

    public void setPrefetchCount(String prefetchCount) {
        setProperty(PREFETCH_COUNT, prefetchCount);
    }

    public int getPrefetchCountAsInt() {
        return getPropertyAsInt(PREFETCH_COUNT);
    }

    public boolean getUseTx() {
        return getPropertyAsBoolean(USE_TX, DEFAULT_USE_TX);
    }

    public void setUseTx(Boolean tx) {
        setProperty(USE_TX, tx);
    }


    /**
     * @return the correlation identifier for the sample
     */
    public String getCorrelationId() {
        return getPropertyAsString(CORRELATION_ID);
    }

    public void setCorrelationId(String content) {
        setProperty(CORRELATION_ID, content);
    }

    /**
     * @return the message id for the sample
     */
    public String getMessageId() {
        return getPropertyAsString(MESSAGE_ID);
    }

    public void setMessageId(String content) {
        setProperty(MESSAGE_ID, content);
    }



    /**
     * Option if the sampler should read the response.
     *
     * @return whether the sampler should read the response
     */
    public String getReadResponse() {
        return getPropertyAsString(READ_RESPONSE);
    }

    /**
     * Set option if the sampler should read the response.
     *
     * @param read whether the sampler should read the response or not
     */
    public void setReadResponse(Boolean read) {
        setProperty(READ_RESPONSE, read);
    }

    /**
     * Option if the sampler should read the response as a boolean value.
     *
     * @return whether the sampler should read the response as a boolean value
     */
    public boolean getReadResponseAsBoolean() {
        return getPropertyAsBoolean(READ_RESPONSE);
    }

    @Override
    public boolean interrupt() {
        testEnded();
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void testEnded() {
        // Do nothing
    }

    @Override
    public void testEnded(String arg0) {
        // Do nothing
    }

    @Override
    public void testStarted() {
        // Do nothing
    }

    @Override
    public void testStarted(String arg0) {
        // Do nothing
    }

    @Override
    public void cleanup() {
        try {
            if (consumerTag != null) {
                channel.basicCancel(consumerTag);
            }
        } catch (IOException e) {
            log.error("Couldn't safely cancel the sample {}", consumerTag, e);
        }

        super.cleanup();
    }

    /**
     * Helper method.
     */
    private void trace(String s) {
        String tl = getTitle();
        String tn = Thread.currentThread().getName();
        String th = this.toString();
        log.debug("{} {} {} {}", tn, tl, s, th);
    }

    private Channel channel;
    @Override
    protected boolean initChannel() throws Exception {
        boolean ret = super.initChannel();

        if(channel == null){
            channel = createChannel().getChannel();
        }
        channel.basicQos(getPrefetchCountAsInt());

        if (getUseTx()) {
            channel.txSelect();
        }

        return ret;
    }

    private String formatHeaders(Delivery delivery) {
        Map<String, Object> headers = delivery.getProperties().getHeaders();
        StringBuilder sb = new StringBuilder();

        if (delivery.getProperties().getTimestamp() != null) {
            sb.append(TIMESTAMP_PARAMETER)
                .append(": ")
                .append((delivery.getProperties().getTimestamp().getTime())/1000)
                .append("\n");
        }

        sb.append(EXCHANGE_PARAMETER)
            .append(": ")
            .append(delivery.getEnvelope().getExchange())
            .append("\n");
        sb.append(ROUTING_KEY_PARAMETER)
            .append(": ")
            .append(delivery.getEnvelope().getRoutingKey())
            .append("\n");
        sb.append(DELIVERY_TAG_PARAMETER)
            .append(": ")
            .append(delivery.getEnvelope().getDeliveryTag())
            .append("\n");

        if (delivery.getProperties().getAppId() != null) {
            sb.append(APP_ID_PARAMETER)
                .append(": ")
                .append(delivery.getProperties().getAppId())
                .append("\n");
        }

        if (headers != null) {
            for (Map.Entry<String,Object> entry : headers.entrySet()) {
                sb.append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append("\n");
            }
        }

        return sb.toString();
    }
}
