package com.meridian.scaffold;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Properties;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;

class ApplicationTests {

  @Test
  void topologyBuilds() {
    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "meridian-streams-test");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
    try (TopologyTestDriver driver = new TopologyTestDriver(Application.buildTopology(), props)) {
      assertNotNull(driver);
    }
  }
}
