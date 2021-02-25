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
package io.streamnative.pulsar.plugins.delayed.impl;

import io.streamnative.pulsar.plugins.delayed.AbstractTimePartition;
import io.streamnative.pulsar.plugins.delayed.DelayedMessageIndexer;
import io.streamnative.pulsar.plugins.delayed.DelayedMessageScheduler;
import io.streamnative.pulsar.plugins.delayed.DelayedMessageStorage;
import org.apache.pulsar.broker.service.Topic;

import java.util.concurrent.CompletableFuture;

public class InMemoryTimePartition extends AbstractTimePartition {

    protected InMemoryTimePartition(Topic originalTopic, DelayedMessageStorage storage, DelayedMessageIndexer indexer,
            DelayedMessageScheduler scheduler, long startTimestamp) {
        super(originalTopic, storage, indexer, scheduler, startTimestamp);
    }

    @Override
    public CompletableFuture<Void> initializeAsync() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        return storage.closeAsync().thenCompose(v -> indexer.closeAsync());
    }

    @Override
    public CompletableFuture<Void> activate() {
        this.isActive = true;
        return this.indexer.initializeAsync().thenCompose(v -> {
            this.scheduler.start();
            return CompletableFuture.completedFuture(null);
        });
    }
}
