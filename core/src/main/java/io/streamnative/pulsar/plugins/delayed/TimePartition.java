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

import java.util.concurrent.CompletableFuture;

public interface TimePartition {

    CompletableFuture<Void> initializeAsync();

    CompletableFuture<Position> writeAsync(ByteBuf data, long deliverTimestamp);

    CompletableFuture<Entry> readAsync(Position position);

    CompletableFuture<Void> closeAsync();

    CompletableFuture<Void> activate();

    long getStartTimestamp();

    boolean isActive();
}
