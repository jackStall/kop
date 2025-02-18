/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.kafka.client.api;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import lombok.Builder;
import lombok.Getter;

/**
 * Context for producing messages.
 */
@Builder
@Getter
public class ProduceContext<K, V> {

    private Producer<K, V> producer;
    private String topic;
    private Integer partition;
    private Long timestamp;
    private K key;
    private V value;
    private List<Header> headers;
    private final CompletableFuture<RecordMetadata> future = new CompletableFuture<>();

    /**
     * Create an instance of Kafka's ProducerRecord.
     *
     * @param clazz the class type of Kafka's ProducerRecord
     * @param headerConstructor the constructor of Kafka's Header implementation
     * @param <T> it should be org.apache.kafka.clients.producer.ProducerRecord
     * @param <HeaderT> it should be an implementation of org.apache.kafka.common.header.Header, e.g. RecordHeader
     * @return an instance of org.apache.kafka.clients.producer.ProducerRecord
     */
    public <T, HeaderT> T createProducerRecord(final Class<T> clazz,
                                               final BiFunction<String, byte[], HeaderT> headerConstructor) {
        try {
            return clazz.getConstructor(
                    String.class, Integer.class, Long.class, Object.class, Object.class, Iterable.class
            ).newInstance(topic, partition, timestamp, key, value, Header.toHeaders(headers, headerConstructor));
        } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Complete the internal `future` field.
     *
     * @param metadata the instance of Kafka's RecordMetadata
     * @param e the exception to complete exceptionally if it's not null
     * @param <T> it should be org.apache.kafka.clients.producer.RecordMetadata
     */
    public <T> void complete(final T metadata, final Exception e) {
        if (e == null) {
            future.complete(RecordMetadata.create(metadata));
        } else {
            future.completeExceptionally(e);
        }
    }

    /**
     * Send the message using ProduceContext instead of using Producer directly.
     *
     * @see Producer#sendAsync(ProduceContext)
     */
    public Future<RecordMetadata> sendAsync() {
        if (producer == null) {
            throw new IllegalArgumentException("producer is null");
        }
        return producer.sendAsync(this);
    }
}
