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
