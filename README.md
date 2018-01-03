[![CircleCI](https://circleci.com/gh/strimzi/kafka-topic-webhook.svg?style=shield)](https://circleci.com/gh/strimzi/kafka-topic-webhook)

# Kafka Topic Admission Webhook for Kubernetes / OpenShift

Kafka Topic Admission Webhook allows managing Kafka topics through annotations set on Pods which are using these topics. Pods 
can be annotated with annotation `topic-webhook.kafka.strimzi.io/topics` containing a description of topics used by 
given Pod. When creating the Pod, the webhook will create the required topics or fail the creation of the Pod.

The webhook is using [*Dynamic Admission Control* feature](https://v1-7.docs.kubernetes.io/docs/admin/extensible-admission-controllers/#external-admission-webhooks) of Kubernetes / OpenShift. 
It will deploy a simple HTTP server which implements the Admission Review webhook. This webhook is called every time when 
a new Pod is being created. It checks the `topic-webhook.kafka.strimzi.io/topics` annotation and if it is present it 
will evaluate it and either allow or reject the creation of the Pod. All Pods without the annotation will be 
automatically allowed. 

<!-- TOC depthFrom:2 -->

- [Annotation format](#annotation-format)
- [Installation](#installation)
    - [OpenShift](#openshift)
    - [Kubernetes](#kubernetes)
    - [Installing into different namespace / project](#installing-into-different-namespace--project)
    - [Supported Kubernetes and OpenShift versions](#supported-kubernetes-and-openshift-versions)
- [Examples](#examples)
- [Comparing with other approaches](#comparing-with-other-approaches)
- [TODO](#todo)
- [FAQ](#faq)

<!-- /TOC -->

## Annotation format

The `topic-webhook.kafka.strimzi.io/topics` annotation should always contain an JSON array with one or more Kafka 
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
| `config` | Additional configuration options for the Kafka topic | No | n/a | `{ "cleanup.policy": "compact" }` |


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
        topic-webhook.kafka.strimzi.io/topics: "[ {\"name\": \"topicX\", \"create\": true, \"assert\": false, \"partitions\": 3, \"replicas\": 3, \"config\": { \"cleanup.policy\": \"compact\" } }, {\"name\": \"topicY\", \"create\": true, \"assert\": false } ]"
    spec:
      ...
      ...
```

*Full example can be found in [`example-consumer.yaml`](example-consumer.yaml) file.*

## Installation

### OpenShift

On OpenShift the webhook is by default installed into `myproject` project and requires *admin* privileges (the 
`ExternalAdmissionHookConfiguration` installation requires the admin user). To install it, run:
```
oc apply -f openshift.yaml
```  

### Kubernetes

On Kubernetes the webhook is by default installed into `default` namespace and requires *admin* privileges (the 
`ExternalAdmissionHookConfiguration` installation requires them). To install it, run:
```
kubectl apply -f kubernetes.yaml
```  

### Installing into different namespace / project

If you want to install it into different namespace / project, you have to change `tls/webhook.json` and `openshift.yaml` to change 
the namespace and the URL for the SSL certificates, regenerate the certificates using `make gen_certs` and rebuild the 
Docker image using `make all`. This applies to both Kubernetes as well as OpenShift.

### Supported Kubernetes and OpenShift versions

Dynamic Admission Control is supported since Kubernetes 1.7 and OpenShift 3.7. Depending on your Kubernets / OpenShift 
cluster installation, you might need to enable it manually.

## Examples

To test the webhook, you can deploy the [`example-consumer.yaml`](example-consumer.yaml) and [`example-producer.yaml`](example-producer.yaml) 
which will trigger the topic creation and send / receive messages to this topic. The messages can be seen in their logs.

## Comparing with other approaches

The other approach how to manage topics *the Kubernetes way* could be using Custom Resource Definitions (CRD) or using ConfigMaps (CM) and 
the operator concept. User deploys CRD or CM describing the Apache Kafka topic. A controller will monitor the CRDs / CMs 
and create or update topics when they are created or modified. Both approaches allow to define the messaging infrastructure 
using Kubernetes resources. The webhopok approach has some advantages as well as some disadvantages.

**Advantages:**
* The pod will be started only once the topics are created by the webhook. The process is synchronous.
* The topics are defined directly in the Pod annotations. There are no conflicts when the producer and consumer are 
using the same CRD / CM and either overwrite each others definitions or create two conflicting definitions.
* The annotation is stored directly with the consumer / producer. No need to search for separate resources. 
* The annotation address gives a lot of flexibility in topic configuration. It can insist on specific topic 
configuration or ignore the configuration details and be happy when the topic with given name simply exists. 


**Disadvantages:**
* The webhook has to analyze all pods running in your cluster. When the webhook is unavailable - depending on the 
`failurePolicy` (see FAQ for more details) - it will either block all Pods from being started (including the webhook it 
self) or simply ignore the requested topics. Even when the webhook is available, it has to analyze all Pods and thus 
increases the latency when starting new Pods.
* The annotations are checked only when some Pod is starting. They are not rechecked periodically, so changes done to 
the topics only after the Pod was started will notbe detected.
* Kafka topics are not deleted when the Pods are deleted.
* TLS certificates need to be configured to establish trust between Kubernetes and webhook 

## TODO

* Assertion of topic configuration for existing topics
* Tests

## FAQ

**Does the webhook constantly monitor the Kafka topics?**

No. The webhook is triggered only when a Pod with the right annotation is triggered. When the topic is deleted while 
the Pod is running, webhook will not know or care about it.

**What happens when the webhook controller is not running?** 

The webhook is registered as Admission Control webhook. The webhook configuration in Kubernetes / OpenShift has a 
configuration field `failurePolicy` which can have either value `Fail` or `Ignore`. 

When set to `Fail`, Pod admission will fail when the webhook controller doesn't run. This will affect all pods which 
will be newly started even when they do not have the annotation. This will not affect Pods which are already running.

When set to `Ignore`, all Pods will be started when the webhook controller is not reachable. This means that Pods will 
be created even when Kafka topics from their annotation do not exist.

**Can the annotation be set on other resources such as Deployments, Jobs or StatefulSets?**

No, currently only Pods are supported.

**Why do you use webhook and not Initializer?**

The webhook is a lot easier to implement and it also seems to be the way which future Kubernetes versions will use. 
Since we don't modify the actuall Pod, we do not need the Initializer and can do it with Webhook only. Also the webhooks 
()and mutating webhooks) seem to be currently the prefered way forward in Kubenrtees world.

**What happens with the Kafka topics once the Pods are deleted?**

The webhook is triggered only when Pod is created. It is not informed about the Pods being deleted. All Kafka topics 
created by the webhook will continue to exist and have to be removed manually.