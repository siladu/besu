/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.sync.snapsync;

import static org.hyperledger.besu.ethereum.eth.sync.StorageExceptionManager.canRetryOnError;
import static org.hyperledger.besu.ethereum.eth.sync.StorageExceptionManager.getRetryableErrorCounter;

import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.AccountRangeDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.SnapDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.StorageRangeDataRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.heal.StorageTrieNodeHealingRequest;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.request.heal.TrieNodeHealingRequest;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.plugin.services.exception.StorageException;
import org.hyperledger.besu.services.tasks.Task;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersistDataStep {
  private static final Logger LOG = LoggerFactory.getLogger(PersistDataStep.class);

  private final SnapSyncProcessState snapSyncState;
  private final WorldStateStorageCoordinator worldStateStorageCoordinator;
  private final SnapWorldDownloadState downloadState;

  private final SnapSyncConfiguration snapSyncConfiguration;

  public PersistDataStep(
      final SnapSyncProcessState snapSyncState,
      final WorldStateStorageCoordinator worldStateStorageCoordinator,
      final SnapWorldDownloadState downloadState,
      final SnapSyncConfiguration snapSyncConfiguration) {
    this.snapSyncState = snapSyncState;
    this.worldStateStorageCoordinator = worldStateStorageCoordinator;
    this.downloadState = downloadState;
    this.snapSyncConfiguration = snapSyncConfiguration;
  }

  public List<Task<SnapDataRequest>> persist(final List<Task<SnapDataRequest>> tasks) {
    Optional<Task<SnapDataRequest>> currentTask = Optional.empty();
    try {
      final WorldStateKeyValueStorage.Updater updater = worldStateStorageCoordinator.updater();
      for (Task<SnapDataRequest> task : tasks) {
        currentTask = Optional.of(task);
        if (task.getData().isResponseReceived()) {
          // enqueue child requests
          final Stream<SnapDataRequest> childRequests =
              task.getData()
                  .getChildRequests(downloadState, worldStateStorageCoordinator, snapSyncState);
          if (!(task.getData() instanceof TrieNodeHealingRequest)) {
            enqueueChildren(childRequests);
          } else {
            if (!task.getData().isExpired(snapSyncState)) {
              enqueueChildren(childRequests);
            } else {
              continue;
            }
          }

          // persist nodes
          final int persistedNodes =
              task.getData()
                  .persist(
                      worldStateStorageCoordinator,
                      updater,
                      downloadState,
                      snapSyncState,
                      snapSyncConfiguration);
          if (persistedNodes > 0) {
            if (task.getData() instanceof TrieNodeHealingRequest) {
              downloadState.getMetricsManager().notifyTrieNodesHealed(persistedNodes);
            } else {
              downloadState.getMetricsManager().notifyNodesGenerated(persistedNodes);
            }
          }
        }
        currentTask = Optional.empty();
      }
      try {
        updater.commit();
      } catch (StorageException e) {
        LOG.error("exception during final updater.commit(), logging all tasks...", e);
        tasks.forEach(this::logTaskData);
        throw e;
      }
    } catch (StorageException storageException) {
      if (canRetryOnError(storageException)) {
        storageException.printStackTrace();
        currentTask.ifPresent(this::logTaskData);
        // We reset the task by setting it to null. This way, it is considered as failed by the
        // pipeline, and it will attempt to execute it again later. not display all the retryable
        // issues
        LOG.error(
            "Encountered {} retryable RocksDB errors, latest error message {}",
            getRetryableErrorCounter(),
            storageException.getMessage());
        tasks.forEach(task -> task.getData().clear());
      } else {
        throw storageException;
      }
    }
    return tasks;
  }

  private void logTaskData(final Task<SnapDataRequest> currentTask) {
    try {
      LOG.error("currentTask instanceof " + currentTask.getClass());
      SnapDataRequest data = currentTask.getData();
      LOG.error("currentTask data requestType {}", data.getRequestType());
      LOG.error("currentTask data rootHash {}", data.getRootHash());
      String dataToPrint =
          switch (data) {
            case StorageTrieNodeHealingRequest r ->
                r.getRequestType()
                    + " getAccountHash="
                    + r.getAccountHash()
                    + " getNodeHash="
                    + r.getNodeHash()
                    + " getLocation="
                    + r.getLocation()
                    + " getPathId="
                    + r.getPathId();
            case TrieNodeHealingRequest r ->
                r.getRequestType()
                    + " getNodeHash="
                    + r.getNodeHash()
                    + " getLocation="
                    + r.getLocation()
                    + " getPathId="
                    + r.getPathId();
            case AccountRangeDataRequest r ->
                r.getRequestType()
                    + " getStartKeyHash="
                    + r.getStartKeyHash()
                    + " getEndKeyHash="
                    + r.getEndKeyHash();
            case StorageRangeDataRequest r ->
                r.getRequestType()
                    + " getAccountHash="
                    + r.getAccountHash()
                    + " getStorageRoot="
                    + r.getStorageRoot()
                    + " getStartKeyHash="
                    + r.getStartKeyHash()
                    + " getEndKeyHash="
                    + r.getEndKeyHash();
            default -> "default..." + data.getRequestType() + " getRootHash=" + data.getRootHash();
          };
      LOG.error("request data: {}", dataToPrint);
    } catch (Exception e) {
      LOG.error("failed to log debug data for exception", e);
    }
  }

  /**
   * This method will heal the local flat database if necessary and persist it
   *
   * @param tasks range to heal and/or persist
   * @return completed tasks
   */
  public List<Task<SnapDataRequest>> healFlatDatabase(final List<Task<SnapDataRequest>> tasks) {
    final BonsaiWorldStateKeyValueStorage.Updater updater =
        (BonsaiWorldStateKeyValueStorage.Updater) worldStateStorageCoordinator.updater();
    for (Task<SnapDataRequest> task : tasks) {
      // heal and/or persist
      task.getData()
          .persist(
              worldStateStorageCoordinator,
              updater,
              downloadState,
              snapSyncState,
              snapSyncConfiguration);
      // enqueue child requests, these will be the right part of the ranges to complete if we have
      // not healed all the range
      enqueueChildren(
          task.getData()
              .getChildRequests(downloadState, worldStateStorageCoordinator, snapSyncState));
    }
    updater.commit();
    return tasks;
  }

  public Task<SnapDataRequest> persist(final Task<SnapDataRequest> task) {
    return persist(List.of(task)).get(0);
  }

  public Task<SnapDataRequest> healFlatDatabase(final Task<SnapDataRequest> task) {
    return healFlatDatabase(List.of(task)).get(0);
  }

  private void enqueueChildren(final Stream<SnapDataRequest> childRequests) {
    downloadState.enqueueRequests(childRequests);
  }
}
