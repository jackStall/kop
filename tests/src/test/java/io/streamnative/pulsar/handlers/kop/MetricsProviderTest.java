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
package io.streamnative.pulsar.handlers.kop;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * test for kop prometheus metrics.
 */
@Slf4j
public class MetricsProviderTest extends KopProtocolHandlerTestBase{

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        super.internalSetup();
        log.info("success internal setup");
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }


    @Test(timeOut = 30000)
    public void testMetricsProvider() throws Exception {
        int partitionNumber = 1;
        String kafkaTopicName = "kopKafkaProducePulsarMetrics" + partitionNumber;

        // create partitioned topic.
        admin.topics().createPartitionedTopic(kafkaTopicName, partitionNumber);

        // 1. produce message with Kafka producer.
        @Cleanup
        KProducer kProducer = new KProducer(kafkaTopicName, false, getKafkaBrokerPort());

        int totalMsgs = 10;

        String messageStrPrefix = "Message_Kop_KafkaProducePulsarConsume_"  + partitionNumber + "_";

        for (int i = 0; i < totalMsgs; i++) {
            String messageStr = messageStrPrefix + i;
            ProducerRecord record = new ProducerRecord<>(
                    kafkaTopicName,
                    i,
                    messageStr);

            kProducer.getProducer().send(record).get();

            if (log.isDebugEnabled()) {
                log.debug("Kafka Producer Sent message: ({}, {})", i, messageStr);
            }
        }

        // 2. consume messages with Kafka consumer
        @Cleanup
        KConsumer kConsumer = new KConsumer(kafkaTopicName, getKafkaBrokerPort());
        List<TopicPartition> topicPartitions = IntStream.range(0, partitionNumber)
                .mapToObj(i -> new TopicPartition(kafkaTopicName, i)).collect(Collectors.toList());
        kConsumer.getConsumer().assign(topicPartitions);

        int msgs = 0;
        while (msgs < totalMsgs) {
            if (log.isDebugEnabled()) {
                log.debug("start poll message: {}", msgs);
            }
            ConsumerRecords<Integer, String> records = kConsumer.getConsumer().poll(Duration.ofSeconds(1));
            for (ConsumerRecord<Integer, String> record : records) {
                if (log.isDebugEnabled()) {
                    log.debug("Kafka consumer get message: {}, key: {} at offset {}",
                            record.key(), record.value(), record.offset());
                }
                msgs++;
            }
        }
        Assert.assertEquals(msgs, totalMsgs);

        // commit offsets
        kConsumer.getConsumer().commitSync(Duration.ofSeconds(5));

        try {
            Thread.sleep(1000);
        } catch (Exception e) {

        }

        HttpClient httpClient = HttpClientBuilder.create().build();
        final String metricsEndPoint = pulsar.getWebServiceAddress() + "/metrics";
        HttpResponse response = httpClient.execute(new HttpGet(metricsEndPoint));
        InputStream inputStream = response.getEntity().getContent();
        InputStreamReader isReader = new InputStreamReader(inputStream);
        BufferedReader reader = new BufferedReader(isReader);
        StringBuffer sb = new StringBuffer();
        String line;
        Pattern formatPattern = Pattern.compile("^(\\w+)(\\{(\\w+=[\\\"\\.\\w]+(,\\s?\\w+=[\\\"\\.\\w]+)*)\\})?"
                + "\\s(-?[\\d\\w\\.]+)(\\s(\\d+))?$");

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()
                    || line.startsWith("#")
                    || line.contains("NaN")
                    || line.contains("Infinity")) {
                continue;
            }

            // check kop metric format
            if (line.startsWith("kop")) {
                Matcher formatMatcher = formatPattern.matcher(line);
                Assert.assertTrue(formatMatcher.matches());
            }

            sb.append(line);
        }

        // channel stats
        Assert.assertTrue(sb.toString().contains("kop_server_ALIVE_CHANNEL_COUNT"));
        Assert.assertTrue(sb.toString().contains("kop_server_ACTIVE_CHANNEL_COUNT"));

        // request stats
        Assert.assertTrue(sb.toString().contains("kop_server_REQUEST_QUEUE_SIZE"));
        Assert.assertTrue(sb.toString().contains("kop_server_REQUEST_QUEUED_LATENCY"));
        Assert.assertTrue(sb.toString().contains("kop_server_REQUEST_PARSE_LATENCY"));
        Assert.assertTrue(sb.toString().contains("kop_server_REQUEST_LATENCY"));
        Assert.assertTrue(sb.toString().contains("request=\"ApiVersions\""));
        Assert.assertTrue(sb.toString().contains("request=\"ListOffsets\""));
        Assert.assertTrue(sb.toString().contains("request=\"Fetch\""));
        Assert.assertTrue(sb.toString().contains("kop_server_REQUEST_LATENCY{success=\"true\",quantile=\"0.99\", "
                + "request=\"Produce\"}"));
        Assert.assertTrue(sb.toString().contains("kop_server_REQUEST_QUEUED_LATENCY_count{success=\"true\", "
                + "request=\"Produce\"}"));

        // response stats
        Assert.assertTrue(sb.toString().contains("kop_server_RESPONSE_BLOCKED_TIMES"));
        Assert.assertTrue(sb.toString().contains("kop_server_RESPONSE_BLOCKED_LATENCY"));

        // produce stats
        Assert.assertTrue(sb.toString().contains("kop_server_PRODUCE_ENCODE"));
        Assert.assertTrue(sb.toString().contains("kop_server_MESSAGE_PUBLISH"));
        Assert.assertTrue(sb.toString().contains("kop_server_MESSAGE_QUEUED_LATENCY"));

        // fetch stats
        Assert.assertTrue(sb.toString().contains("kop_server_PREPARE_METADATA"));
        Assert.assertTrue(sb.toString().contains("kop_server_MESSAGE_READ"));
        Assert.assertTrue(sb.toString().contains("kop_server_FETCH_DECODE"));

        // consumer stats
        Assert.assertTrue(sb.toString().contains("kop_server_MESSAGE_OUT{group=\"DemoKafkaOnPulsarConsumer\","
                + "partition=\"0\",topic=\"kopKafkaProducePulsarMetrics1\"} 10"));
        Assert.assertTrue(sb.toString().contains("kop_server_BYTES_OUT{group=\"DemoKafkaOnPulsarConsumer\","
                + "partition=\"0\",topic=\"kopKafkaProducePulsarMetrics1\"} 1130"));
        Assert.assertTrue(sb.toString().contains("kop_server_BYTES_OUT"));

        // producer stats
        Assert.assertTrue(sb.toString().contains("kop_server_BATCH_COUNT_PER_MEMORYRECORDS"));
        Assert.assertTrue(sb.toString().contains("kop_server_MESSAGE_IN{partition=\"0\","
                + "topic=\"kopKafkaProducePulsarMetrics1\"} 10"));
        Assert.assertTrue(sb.toString().contains("kop_server_BYTES_IN{partition=\"0\","
                + "topic=\"kopKafkaProducePulsarMetrics1\"} 1170"));
    }

    @Test(timeOut = 20000)
    public void testUpdateGroupId() throws Exception {
        final String topic = "testUpdateGroupId";
        final String clientId = "my-client";
        final String group1 = "my-group-1";
        final String group2 = "my-group-2";

        tryConsume(topic, clientId, group1);
        List<String> children = mockZooKeeper.getChildren(conf.getGroupIdZooKeeperPath(), false);
        Assert.assertEquals(children.size(), 1);
        Assert.assertEquals(children.get(0), "127.0.0.1-" + clientId);
        byte[] data = mockZooKeeper.getData(conf.getGroupIdZooKeeperPath() + "/" + children.get(0), false, null);
        Assert.assertEquals(new String(data, StandardCharsets.UTF_8), group1);

        // Create a consumer with the same hostname and client id, the existed z-node will be updated
        tryConsume(topic, clientId, group2);
        children = mockZooKeeper.getChildren(conf.getGroupIdZooKeeperPath(), false);
        Assert.assertEquals(children.size(), 1);
        Assert.assertEquals(children.get(0), "127.0.0.1-" + clientId);
        data = mockZooKeeper.getData(conf.getGroupIdZooKeeperPath() + "/" + children.get(0), false, null);
        Assert.assertEquals(new String(data, StandardCharsets.UTF_8), group2);
    }

    private void tryConsume(final String topic, final String clientId, final String groupId) {
        final Properties props = newKafkaConsumerProperties();
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        final KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singleton(topic));
        consumer.poll(Duration.ofSeconds(3));
        consumer.close();
    }
}
