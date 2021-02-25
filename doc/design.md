- Status: Discuss
- Authors: Penghui Li
- Release:
- Proposal time: 2020/12/26

# Motivation

Scheduled and delayed message delivery is a very common feature to support in a message system. Basically, the individual messages can have a header that will be set by the publisher, and based on the header value the broker should hold on delivering messages until the configured delay or the scheduled time is met.

Pulsar 2.4.0 introduced the delayed message delivery feature which based on an in-memory priority queue. For each subscription, a priority queue maintains the delay time index for the delay messages that are written to this topic. While the message can not be delivered to consumers immediately, we add the timestamp that should deliver the message and the message ID into the priority queue. The dispatcher of the subscription will check if there are messages that can be delivered to consumers. This is a very simple mechanism to achieve the delayed message delivery but there are some drawbacks with the current approach.

## The memory limitation of the priority queue

Broker&#39;s memory is not infinite, for scenarios where users need to store a large number of delayed messages, the in-memory priority queue might be a bottleneck for maintaining a large delayed index.

If you want to scale the delayed message capacity, you can add more partitions so that the delayed index can be distributed to multiple brokers,  but this does not change the fact that a lot of broker memory is wasted.

A topic might have several subscriptions, the in-memory delayed indexes can&#39;t be used across the subscriptions, this also a big factor of wasting the broker memory.

## Expensive delayed index rebuilding

To rebuild the delayed index, the broker needs to read all delayed messages of a topic. If there are too many delayed messages of a topic, the index rebuilding might take a long time, a few minutes to a few hours. As long as the subscription under the delay index rebuilding situation, the consumers can&#39;t consume messages from the topic, this will bring more consumer unavailable time.

The index rebuilding usually happens while the broker crashes or the topic relocates to another broker because the Pulsar cluster wants to balance the load as much as possible across brokers.

## A large amount of data retention

The data of a Pulsar topic is cut into multiple segments and the data cleanup is based on the segment. In other words, the segment only can be deleted from the topic until the segment no longer used(All the messages of the segment are acknowledged by the subscriptions under the topic and exceeded the scope of data retention).

If a topic contains both delayed messages and normal messages, most likely a segment also contains delayed messages and normal messages. Due to the delayed messages are not acknowledged by the subscriptions, so the segment can&#39;t be removed from the topic until the delayed messages available for consumers and be acknowledged by consumers.

We have seen many users encounter this situation, disk usage continues to grow, but most of the messages can be deleted. Therefore, the segment cleanup coupled with the maximum delay time. If users want to improve this situation, they must be careful to assign topics for delay messages, keep messages with similar delay granularity in one topic.

## Acknowledgment holes

Pulsar tracked the acknowledgment state for each message for the Shared subscription and Key_Shared subscription. For the messages that have been continuously acknowledged, we use a mark delete position to represent. For the non-continuously acknowledged messages, we use the acknowledged range to represent, and the mark delete position and the acknowledged ranges will be persistent into a **_Ledger_**.

Hence, more non-continuously acknowledged messages need more memory and disks to achieve. However, the delayed messages make this situation worse. We have seen some users encountered this problem. In order to avoid too many acknowledged ranges are written to the **_Ledger_**, the broker only persistent 10000 acknowledged ranges into the **_Ledger_**. The problem is while the broker crashes or the topic been relocated to another broker, the broker dispatches the acknowledged messages to the consumer again.

So, this proposal aims to solve the above problems, to allow users to use messages of any delay granularity in a topic without worrying about the above issues.

# Approach

The approach is based on [https://github.com/apache/pulsar/wiki/PIP-26%3A-Delayed-Message-Delivery](https://github.com/apache/pulsar/wiki/PIP-26%3A-Delayed-Message-Delivery) but more processing details need to be explained. The core idea is using some separate logs to maintain the messages in different time partitions.

All the delayed messages of a topic can be split into multiple parts by the available time. So, if we specify the granularity of the time partition to 1 day, this means the message all messages are available today will be written into the current time partition and the messages that are available tomorrow will be written into the next time partition. So that we only need to build the delayed index for the current partition, the time partition granularity will be very flexible to be configured by users.

Since the delayed messages did not write to the original data log, so this will not introduce the acknowledgment holes and the data cleanup of the original data log will not be affected. Of course, the disadvantage is needing more data logs to store the delayed messages and the producer will get a discontinuous message ID when publishing delayed messages.

## Data flow

![](https://static.slab.com/prod/uploads/cam7h8fn/posts/images/pumFclHkudTLrFm8ImBxyyad.png)

The illustration from above introduced how the delayed messages are handled by the broker.

1. The producer writes the delayed messages to the time partition directly according to the available time of the delayed message. After the time partition persistent the message to the data log(**_ManagedLedger_**), the broker returns a message ID to the producer.
1. The broker loaded the current time partition and build the delayed index. A background task checks if there are messages available for consumers of the current time partition. When the delayed message available for consumers, a delayed message marker will be added to the original data log. The delayed message marker contains the message ID that can refer to the time partition.
1. The dispatcher of the subscription read data from the original log, when reading a delayed message tracker, the dispatcher should get the real message by the message ID in the time partition and dispatch the message to a consumer.

## Time partition cleanup

To clean up the data that the time partition maintains, we can leverage the original data log delete mechanism. This means we can check if the data of the time partition can be deleted while the ledger deleted from the original data log.

While the delayed message available for consumers, we added a delayed message marker in the original data log. The time partitions are closed based on time, and the next time partition becomes the current time partition. When the current time partition closes, we know the last available message of the time partition and we can record the marker position in the original data log of the last available message. This means while the marker position is deleted from the original data log, the delete of the time partition can be deleted safely.

![](https://static.slab.com/prod/uploads/cam7h8fn/posts/images/o8FLrMzP1YYsPNiaB6Oa2Lyi.png)

As shown in the above illustration, there are many ledgers of the topic and the last delayed marker position of a time partition is **_10:126_**, as time goes by, the earlier ledger will be constantly cleaned up. After deleting the ledger with ledger ID **_10_**, the data of the time partition can be deleted safely since the last delayed marker position is **_10:126_**.

Therefore, the time partitions of a topic look like the illustration below.

![](https://static.slab.com/prod/uploads/cam7h8fn/posts/images/i4gmH70vo4fUSqKNai-cnGJg.png)

## Delayed index snapshot

The delayed index snapshot is created when the broker loads the current time partition. Without the index snapshot, the broker crashes or the topic ownership changed, the new owner broker of the topic needs to build the delayed index by replay the data log of the time partition. With the index snapshot, we can build the delayed index by the last index snapshot and data after the snapshot.

The delayed index snapshots can be persistent to a log. The complete index snapshot can be split into multiple parts so that we can store the part as an entry, this can make the index snapshot work well when the index snapshot is large(more than 5MB).

![](https://static.slab.com/prod/uploads/cam7h8fn/posts/images/GeIACMj_r31yBdg-xpUtLQKK.png)

As shown in the above illustration, a delayed index snapshot contains several snapshot parts and snapshot metadata. The snapshot metadata always write to the end of the snapshot as a separate entry.  So restoring the whole snapshot, the first step is to read the delayed index snapshot metadata and then read the parts by the metadata.

Generate a delayed index snapshot also need to write the index snapshot parts and the snapshot metadata.

The delayed index snapshot can be cleanup while all the delayed messages are available for consumers and the delayed markers of the time partition are written to the original data log.

# Changes

## API Changes

## Protocol Changes

## Configuration Changes



# Compatibility

# Test Plan
