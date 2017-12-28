# Kafka Topic Initializer for Kubernetes / OpenShift

Kafka Topic Initializer allows managing Kafka topics through annotations set on Pods which are using these topics. Pods 
can be annotated with annotation `topic-initializer.kafka.scholz.cz/topics` containing a description of topics used by 
given Pod. When creating the Pod, the initializer will create the required topics or fail the creation of the Pod.

The initializer is using [*Dynamic Admission Control* feature](https://v1-7.docs.kubernetes.io/docs/admin/extensible-admission-controllers/#external-admission-webhooks) of Kubernetes / OpenShift. 
It deployes a simple HTTP server which implements the Admission Review webhook. This webhook is called every time when 
a new Pod is being created. It checks the `topic-initializer.kafka.scholz.cz/topics` annotation and if it is present it 
will evaluate it and either allow or reject the creation of the Pod. All Pods without the annotation will be 
automatically allowed. 

## Annotation format

The `topic-initializer.kafka.scholz.cz/topics` annotation should always contain an JSON array with one or more Kafka 
topics. The topic specification has a following format:
```
{"name": <string>, "create": <bool>, "assert": <bool>, "partitions": <int>, "replicas": <int>, "config": <JSON object> }
```

The fields have following meaning and default values:

| Field | Explanation | Required | Default | Example |
|-------|-------------|----------|---------|---------|
| `name` | Name of the Kafka topic | Yes | n/a | `myTopic` |
| `create` | Create the topic if it doesnt exist | No | `true` | `true` |
| `assert` | If the topic exists, assert theat it matches the required configuration | No | `false` | `false` |
| `partitions` | Number of partitions | No | `1` | `3` |
| `replicas` | Number of replicas / replication factor | No | `1` | `3` |
| `zookeeper` | Zookeeper address | No | Configured as part of Webhook deployment | `my-zookeeper:2181/my-kafka-cluster` |
| `config` | Additional configuration options for the KAfka topic | No | n/a | `{ "cleanup.policy": "compact" }` |


For example:
```json
{"name": "topicX", "create": true, "assert": false, "partitions": 3, "replicas": 3, "config": { "cleanup.policy": "compact" } }
```

Following example shows a Kubernetes Deployment using the annotation:_
```yaml
apiVersion: extensions/v1beta1
kind: Deployment
metadata:
  name: my-kafka-consumer
spec:
  replicas: 1
  template:
    metadata:
      annotations:
        topic-initializer.kafka.scholz.cz/topics: "[ {\"name\": \"topicX\", \"create\": true, \"assert\": false, \"partitions\": 3, \"replicas\": 3, \"config\": { \"cleanup.policy\": \"compact\" } }, {\"name\": \"topicY\", \"create\": true, \"assert\": false } ]"
    spec:
      containers:
      - name: consumer
        image: my-repository/kafka-consumer:latest
```

## Supported versions

Dynamic Admission Control is supported since Kubernetes 1.7 and OpenShift 3.7. Depending on your Kubernets / OpenShift 
cluster installation, you might need to enable it manually.

## TODO

* Assertion of topic configuration for existing topics

## FAQ

**Does the initializer constantly monitor the Kafka topics?**

No. The initializer is triggered only when a Pod with the right annotation is triggered. When the topic is deleted while 
the Pod is running, initializer will not know or care about it.

**What happens when the webhook controller is not running?** 

The initializer is registered as Admission Control webhook. The webhook configuration in Kubernetes / OpenShift has a 
configuration field `failurePolicy` which can have either value `Fail` or `Ignore`. 

When set to `Fail`, Pod admission will fail when the webhook controller doesn't run. This will affect all pods which 
will be newly started even when they do not have the annotation. This will not affect Pods which are already running.

When set to `Ignore`, all Pods will be started when the webhook controller is not reachable. This means that Pods will 
be created even when Kafka topics from their annotation do not exist.

**Can the annotation be set on other resources such as Deployments, Jobs or StatefulSets?**

No, currently on Pods are supported.

**Why do you use Webhook and not Initializer?**

The webhook is a lot easier to implement and it also seems to be the way which future Kubernetes versions will use. 
Since we don't modify the actuall Pod, we do not need the Initializer and can do it with Webhook only.