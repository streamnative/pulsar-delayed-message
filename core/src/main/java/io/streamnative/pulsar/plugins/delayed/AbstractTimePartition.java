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
package io.streamnative.pulsar.plugins.delayed;

import io.netty.buffer.ByteBuf;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.Position;
import org.apache.pulsar.broker.service.Topic;

import java.util.concurrent.CompletableFuture;

public abstract class AbstractTimePartition implements TimePartition {

    protected final Topic originalTopic;
    protected final DelayedMessageStorage storage;
    protected final DelayedMessageIndexer indexer;
    protected final DelayedMessageScheduler scheduler;
    protected final long startTimestamp;
    protected boolean isActive;

    protected AbstractTimePartition(Topic originalTopic, DelayedMessageStorage storage,
            DelayedMessageIndexer indexer, DelayedMessageScheduler scheduler, long startTimestamp) {
        this.originalTopic = originalTopic;
        this.storage = storage;
        this.indexer = indexer;
        this.scheduler = scheduler;
        this.startTimestamp = startTimestamp;
    }

    @Override
    public CompletableFuture<Position> writeAsync(ByteBuf data, long deliverTimestamp) {
        return this.storage.writeAsync(data).thenCompose(position -> {
                    if (!isActive) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return indexer.addAsync(position, deliverTimestamp).thenCompose(v ->
                            CompletableFuture.completedFuture(position));
                });
    }

    @Override
    public CompletableFuture<Entry> readAsync(Position position) {
        return storage.readAsync(position);
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    @Override
    public boolean isActive() {
        return isActive;
    }
}
