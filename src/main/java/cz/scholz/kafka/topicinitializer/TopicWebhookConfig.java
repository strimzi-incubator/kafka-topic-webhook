package cz.scholz.kafka.topicinitializer;

public class TopicWebhookConfig {
    private final String zookeeper;

    public TopicWebhookConfig(String zookeeper) {
        this.zookeeper = zookeeper;
    }

    public static TopicWebhookConfig fromEnv() {
        String zookeeper = System.getenv("ZOOKEEPER_URL");

        return new TopicWebhookConfig(zookeeper);
    }

    public String getZookeeper() {
        return zookeeper;
    }
}
