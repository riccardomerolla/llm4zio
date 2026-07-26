package com.meridian.scaffold;

import java.util.Properties;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;

/**
 * The topology lives behind a pure factory so tests and the equivalence replay harness drive it
 * with TopologyTestDriver — no broker in the test path. The modernization tasks replace the empty
 * builder with the flow's real stages.
 */
public class Application {

  public static Topology buildTopology() {
    return new StreamsBuilder().build();
  }

  public static void main(String[] args) {
    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "meridian-streams");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,
        System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092"));
    KafkaStreams streams = new KafkaStreams(buildTopology(), props);
    Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    streams.start();
  }
}
