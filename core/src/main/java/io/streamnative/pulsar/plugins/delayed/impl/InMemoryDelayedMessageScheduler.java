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

import io.streamnative.pulsar.plugins.delayed.DelayedMessageIndexer;
import io.streamnative.pulsar.plugins.delayed.DelayedMessageScheduler;
import org.apache.pulsar.broker.service.Topic;

import java.util.concurrent.CompletableFuture;

public class InMemoryDelayedMessageScheduler implements DelayedMessageScheduler {

    private final Topic originalTopic;
    private final DelayedMessageIndexer indexer;

    public InMemoryDelayedMessageScheduler(Topic originalTopic, DelayedMessageIndexer indexer) {
        this.originalTopic = originalTopic;
        this.indexer = indexer;
    }

    @Override
    public void start() {
        loop();
    }

    private void loop() {
        indexer.pollScheduled().thenAccept(position -> {
            // TODO write the delayed marker to the original topic
//            originalTopic.publishMessage();
            loop();
        }).exceptionally(e -> {
            loop();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        return CompletableFuture.completedFuture(null);
    }
}
