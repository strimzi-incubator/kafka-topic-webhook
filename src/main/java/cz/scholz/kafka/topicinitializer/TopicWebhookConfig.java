package cz.scholz.kafka.topicinitializer;

public class TopicWebhookConfig {
    public TopicWebhookConfig() {
    }

    public static TopicWebhookConfig fromEnv() {
        //String namespace = System.getenv("BARNABAS_CONTROLLER_NAMESPACE");

        return new TopicWebhookConfig();
    }
}
