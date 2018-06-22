package com.mengcraft.simpleorm;

import lombok.Cleanup;
import lombok.SneakyThrows;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static com.mengcraft.simpleorm.ORM.nil;
import static java.util.concurrent.CompletableFuture.runAsync;

public class RedisWrapper extends BinaryJedisPubSub {

    private final JedisPool pool;
    private MessageFilter messageFilter;

    public RedisWrapper(String url, GenericObjectPoolConfig config) {
        pool = new JedisPool(config, URI.create(url));
    }

    public void open(Consumer<Jedis> consumer) {
        @Cleanup Jedis jedis = pool.getResource();
        consumer.accept(jedis);
    }

    @SneakyThrows
    public void publish(String channel, String message) {
        publish(channel, message.getBytes("utf8"));
    }

    @SneakyThrows
    public void publish(String channel, byte[] message) {
        open(client -> client.publish(channel.getBytes("utf8"), message));
    }

    @SneakyThrows
    public void subscribe(String channel, Consumer<byte[]> consumer) {
        if (nil(messageFilter)) {
            messageFilter = new MessageFilter();
            runAsync(() -> open(client -> client.subscribe(messageFilter, channel.getBytes("utf8"))));
        } else {
            messageFilter.subscribe(channel.getBytes("utf8"));
        }
        messageFilter.processor.put(channel, consumer);
    }

    public void unsubscribeAll() {
        if (nil(messageFilter)) {
            return;
        }
        messageFilter.unsubscribe();
        messageFilter = null;
    }

    @SneakyThrows
    public void unsubscribe(String channel) {
        if (nil(messageFilter)) {
            return;
        }

        Consumer<byte[]> consumer = messageFilter.processor.remove(channel);
        if (nil(consumer)) {
            return;
        }

        messageFilter.unsubscribe(channel.getBytes("utf8"));
        if (!messageFilter.processor.isEmpty()) {
            return;
        }

        messageFilter = null;
    }

    private static class MessageFilter extends BinaryJedisPubSub {

        private final Map<String, Consumer<byte[]>> processor = new HashMap<>();

        @SneakyThrows
        public void onMessage(byte[] channel, byte[] message) {
            Consumer<byte[]> consumer = processor.get(new String(channel, "utf8"));
            if (nil(consumer)) {
                return;
            }
            consumer.accept(message);
        }
    }
}
