/*
 * Copyright (c) 2016, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.andes.kernel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.server.ClusterResourceHolder;
import org.wso2.andes.server.cluster.coordination.ClusterCoordinationHandler;
import org.wso2.andes.server.cluster.coordination.hazelcast.HazelcastAgent;
import org.wso2.andes.store.FailureObservingStoreManager;
import org.wso2.andes.store.HealthAwareStore;
import org.wso2.andes.store.StoreHealthListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This task will periodically load exchanges,queues,bindings,subscriptions from database
 * and simulate cluster notifications. This is implemented to bring the node
 * to the current state of cluster in case some hazlecast notifications are missed
 */
public class AndesRecoveryTask implements Runnable, StoreHealthListener {
	/**
	 * Wildcard character to include all.
	 */
	private static final String ALL_WILDCARD = "*";

	private List<QueueListener> queueListeners = new ArrayList<>();
	private List<ExchangeListener> exchangeListeners = new ArrayList<>();
	private List<BindingListener> bindingListeners = new ArrayList<>();

	AndesContextStore andesContextStore;
	AMQPConstructStore amqpConstructStore;

    AtomicBoolean isRunning;

	// set storeOperational to true since it can be assumed that the store is operational at startup
	// if it is non-operational, the value will be updated immediately
	AtomicBoolean isContextStoreOperational = new AtomicBoolean(true);

	private static final Log log = LogFactory.getLog(AndesRecoveryTask.class);

	public AndesRecoveryTask() {

		// Register AndesRecoveryTask class as a StoreHealthListener
		FailureObservingStoreManager.registerStoreHealthListener(this);

		queueListeners.add(new ClusterCoordinationHandler(HazelcastAgent.getInstance()));
		exchangeListeners.add(new ClusterCoordinationHandler(HazelcastAgent.getInstance()));
		bindingListeners.add(new ClusterCoordinationHandler(HazelcastAgent.getInstance()));

		andesContextStore = AndesContext.getInstance().getAndesContextStore();
		amqpConstructStore = AndesContext.getInstance().getAMQPConstructStore();
        isRunning = new AtomicBoolean(false);
	}

	@Override
	public void run() {

        if(!isRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            if (isContextStoreOperational.get()) {
                log.info("Running DB sync task.");

                reloadExchangesFromDB();
                reloadQueuesFromDB();
                reloadBindingsFromDB();
                reloadSubscriptions();

            } else {
                log.warn("AndesRecoveryTask was paused due to non-operational context store.");
            }
        } catch (Throwable e) {
            log.error("Error in running andes recovery task", e);
        } finally {
            isRunning.set(false);
        }
    }

    public void executeNow() {
        run();
    }
	/**
	 * reload and recover exchanges,
	 * Queues, Bindings and Subscriptions
	 *
	 * @throws AndesException
	 */
	public void recoverExchangesQueuesBindingsSubscriptions() throws AndesException {
		if (isContextStoreOperational.get()) {
			reloadExchangesFromDB();
			reloadQueuesFromDB();
			reloadBindingsFromDB();
			reloadSubscriptions();
		} else {
			log.warn("AndesRecoveryTask was paused due to non-operational context store.");
		}
	}

	private void reloadExchangesFromDB() throws AndesException {
		if (isContextStoreOperational.get()) {
			List<AndesExchange> exchangesStored = andesContextStore.getAllExchangesStored();
			List<AndesExchange> exchangeList = amqpConstructStore.getExchanges();
			List<AndesExchange> duplicatedExchanges = new ArrayList<>(exchangesStored);

			exchangesStored.removeAll(exchangeList);
			for (AndesExchange exchange : exchangesStored) {
				for (ExchangeListener listener : exchangeListeners) {
					log.warn("Recovering node. Adding exchange " + exchange.toString());
					listener.handleClusterExchangesChanged(exchange,
					                                       ExchangeListener.ExchangeChange.Added);
				}
			}

			exchangeList.removeAll(duplicatedExchanges);
			for (AndesExchange exchange : exchangeList) {
				for (ExchangeListener listener : exchangeListeners) {
					log.warn("Recovering node. Removing exchange " + exchange.toString());
					listener.handleClusterExchangesChanged(exchange,
					                                       ExchangeListener.ExchangeChange.Deleted);
				}
			}
		} else {
			log.warn("Failed to recover exchanges from database due to non-operational context store.");
		}
	}

	private void reloadQueuesFromDB() throws AndesException {
		if (isContextStoreOperational.get()) {
			List<AndesQueue> queuesStored = andesContextStore.getAllQueuesStored();
			List<AndesQueue> queueList = amqpConstructStore.getQueues(ALL_WILDCARD);
			List<AndesQueue> duplicatedQueues = new ArrayList<>(queuesStored);

			queuesStored.removeAll(queueList);
			for (AndesQueue queue : queuesStored) {
				for (QueueListener listener : queueListeners) {
					log.warn("Recovering node. Adding queue " + queue.toString());
					/**
					 * Ignoring MQTT queues when recovering as they are already stored in the database. 
					 */
					if (queue.getProtocolType() != ProtocolType.MQTT) {
						listener.handleClusterQueuesChanged(queue, QueueListener.QueueEvent.ADDED);
					}
				}
			}

			queueList.removeAll(duplicatedQueues);
			for (AndesQueue queue : queueList) {
				for (QueueListener listener : queueListeners) {
					log.warn("Recovering node. Removing queue " + queue.toString());
					listener.handleClusterQueuesChanged(queue, QueueListener.QueueEvent.DELETED);
				}
			}
		} else {
			log.warn("Failed to recover queues from database due to non-operational context store.");
		}
	}

	private void reloadBindingsFromDB() throws AndesException {
		if (isContextStoreOperational.get()) {
			List<AndesExchange> exchanges = andesContextStore.getAllExchangesStored();
			for (AndesExchange exchange : exchanges) {
				List<AndesBinding> bindingsStored =
						andesContextStore.getBindingsStoredForExchange(exchange.exchangeName);
				List<AndesBinding> bindingsForExchange =
						amqpConstructStore.getBindingsForExchange(exchange.exchangeName);
				List<AndesBinding> duplicatedBindings = new ArrayList<>(bindingsStored);
				bindingsStored.removeAll(bindingsForExchange);
				for (AndesBinding binding : bindingsStored) {
					for (BindingListener listener : bindingListeners) {
						log.warn("Recovering node. Adding binding " + binding.toString());
						listener.handleClusterBindingsChanged(binding,
						                                      BindingListener.BindingEvent.ADDED);
					}
				}

				bindingsForExchange.removeAll(duplicatedBindings);
				for (AndesBinding binding : bindingsForExchange) {
					for (BindingListener listener : bindingListeners) {
						log.warn("Recovering node. removing binding " + binding.toString());
						listener.handleClusterBindingsChanged(binding,
						                                      BindingListener.BindingEvent.DELETED);
					}
				}
			}
		} else {
			log.warn("Failed to recover bindings from database due to non-operational context store.");
		}
	}

	private void reloadSubscriptions() throws AndesException {
		if (isContextStoreOperational.get()) {
			ClusterResourceHolder.getInstance().getSubscriptionManager()
			                     .reloadSubscriptionsFromStorage();
		} else {
			log.warn("Failed to recover subscriptions from database due to non-operational context store.");
		}
	}

	/**
	 * Invoked when specified store becomes non-operational
	 *
	 * @param store the store which went offline.
	 * @param ex    exception
	 */
	@Override
	public void storeNonOperational(HealthAwareStore store, Exception ex) {
		if (store.getClass().getSuperclass().isInstance(AndesContextStore.class)) {
			isContextStoreOperational.set(false);
			log.info("AndesRecoveryTask paused due to non-operational context store.");
		}
	}

	/**
	 * Invoked when specified store becomes operational
	 *
	 * @param store Reference to the operational store
	 */
	@Override
	public void storeOperational(HealthAwareStore store) {
		if (store.getClass().getSuperclass().isInstance(AndesContextStore.class)) {
			isContextStoreOperational.set(true);
			log.info("AndesRecoveryTask became operational.");
		}
	}
}
