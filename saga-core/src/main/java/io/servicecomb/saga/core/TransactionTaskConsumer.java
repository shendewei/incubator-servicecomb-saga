/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.core;

import io.servicecomb.saga.core.dag.Node;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TransactionTaskConsumer implements TaskConsumer {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final CompletionService<Operation> executorService;
  private final RecoveryPolicy recoveryPolicy;

  TransactionTaskConsumer(CompletionService<Operation> executorService, RecoveryPolicy recoveryPolicy) {
    this.executorService = executorService;
    this.recoveryPolicy = recoveryPolicy;
  }

  @Override
  public void consume(Collection<Node<SagaTask>> nodes) {
    List<Future<Operation>> futures = new ArrayList<>(nodes.size());
    for (Node<SagaTask> node : nodes) {
      SagaTask task = node.value();
      futures.add(futureOf(task));
    }

    for (int i = 0; i < futures.size(); i++) {
      try {
        executorService.take().get();
      } catch (ExecutionException e) {
        throw new TransactionFailedException(e.getCause());
      } catch (InterruptedException e) {
        // TODO: 7/29/2017 what shall we do when system is shutting down?
        throw new TransactionFailedException(e);
      }
    }
  }

  @Override
  public boolean replay(Collection<Node<SagaTask>> nodes, Set<Operation> completedOperations) {

    for (Iterator<Node<SagaTask>> iterator = nodes.iterator(); iterator.hasNext(); ) {
      SagaTask task = iterator.next().value();
      if (completedOperations.contains(task.transaction())) {
        log.info("Skipped completed transaction id={} operation={} while replay", task.id(), task.transaction());
        iterator.remove();
      }
    }
    return !nodes.isEmpty();
  }

  private Future<Operation> futureOf(SagaTask task) {
    return executorService.submit(() -> {
      recoveryPolicy.apply(task);
      return task.transaction();
    });
  }
}
