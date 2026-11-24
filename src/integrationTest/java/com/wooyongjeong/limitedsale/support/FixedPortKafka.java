package com.wooyongjeong.limitedsale.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A KRaft broker of the image compose.yaml runs, published on a host port chosen before it starts. Testcontainers
 * advertises the mapped port to clients when the broker first starts; a random mapping can change when the container
 * is stopped and started again, which would leave clients dialling an address the broker no longer advertises.
 * A fixed binding keeps the advertised address valid across restarts, as the Redis restart test does.
 */
public final class FixedPortKafka {
    /** Keep in step with compose.yaml. */
    public static final String IMAGE = "apache/kafka:4.4.0";

    private FixedPortKafka() {
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static KafkaContainer create(int hostPort) {
        return new KafkaContainer(IMAGE)
                // Same as compose.yaml: topics exist only because the application creates them.
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withCreateContainerCmdModifier(command -> command.withHostConfig(command.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(9092)))));
    }
}
