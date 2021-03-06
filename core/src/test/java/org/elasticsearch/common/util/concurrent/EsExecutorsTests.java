/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.util.concurrent;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests for EsExecutors and its components like EsAbortPolicy.
 */
public class EsExecutorsTests extends ESTestCase {

    private TimeUnit randomTimeUnit() {
        return TimeUnit.values()[between(0, TimeUnit.values().length - 1)];
    }

    public void testFixedForcedExecution() throws Exception {
        EsThreadPoolExecutor executor = EsExecutors.newFixed(getTestName(), 1, 1, EsExecutors.daemonThreadFactory("test"));
        final CountDownLatch wait = new CountDownLatch(1);

        final CountDownLatch exec1Wait = new CountDownLatch(1);
        final AtomicBoolean executed1 = new AtomicBoolean();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    wait.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                executed1.set(true);
                exec1Wait.countDown();
            }
        });

        final CountDownLatch exec2Wait = new CountDownLatch(1);
        final AtomicBoolean executed2 = new AtomicBoolean();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                executed2.set(true);
                exec2Wait.countDown();
            }
        });

        final AtomicBoolean executed3 = new AtomicBoolean();
        final CountDownLatch exec3Wait = new CountDownLatch(1);
        executor.execute(new AbstractRunnable() {
            @Override
            protected void doRun() {
                executed3.set(true);
                exec3Wait.countDown();
            }

            @Override
            public boolean isForceExecution() {
                return true;
            }

            @Override
            public void onFailure(Throwable t) {
                throw new AssertionError(t);
            }
        });

        wait.countDown();

        exec1Wait.await();
        exec2Wait.await();
        exec3Wait.await();

        assertThat(executed1.get(), equalTo(true));
        assertThat(executed2.get(), equalTo(true));
        assertThat(executed3.get(), equalTo(true));

        executor.shutdownNow();
    }

    public void testFixedRejected() throws Exception {
        EsThreadPoolExecutor executor = EsExecutors.newFixed(getTestName(), 1, 1, EsExecutors.daemonThreadFactory("test"));
        final CountDownLatch wait = new CountDownLatch(1);

        final CountDownLatch exec1Wait = new CountDownLatch(1);
        final AtomicBoolean executed1 = new AtomicBoolean();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    wait.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                executed1.set(true);
                exec1Wait.countDown();
            }
        });

        final CountDownLatch exec2Wait = new CountDownLatch(1);
        final AtomicBoolean executed2 = new AtomicBoolean();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                executed2.set(true);
                exec2Wait.countDown();
            }
        });

        final AtomicBoolean executed3 = new AtomicBoolean();
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    executed3.set(true);
                }
            });
            fail("should be rejected...");
        } catch (EsRejectedExecutionException e) {
            // all is well
        }

        wait.countDown();

        exec1Wait.await();
        exec2Wait.await();

        assertThat(executed1.get(), equalTo(true));
        assertThat(executed2.get(), equalTo(true));
        assertThat(executed3.get(), equalTo(false));

        terminate(executor);
    }

    public void testScaleUp() throws Exception {
        final int min = between(1, 3);
        final int max = between(min + 1, 6);
        final ThreadBarrier barrier = new ThreadBarrier(max + 1);

        ThreadPoolExecutor pool = EsExecutors.newScaling(getTestName(), min, max, between(1, 100), randomTimeUnit(), EsExecutors.daemonThreadFactory("test"));
        assertThat("Min property", pool.getCorePoolSize(), equalTo(min));
        assertThat("Max property", pool.getMaximumPoolSize(), equalTo(max));

        for (int i = 0; i < max; ++i) {
            final CountDownLatch latch = new CountDownLatch(1);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    latch.countDown();
                    try {
                        barrier.await();
                        barrier.await();
                    } catch (Throwable e) {
                        barrier.reset(e);
                    }
                }
            });

            //wait until thread executes this task
            //otherwise, a task might be queued
            latch.await();
        }

        barrier.await();
        assertThat("wrong pool size", pool.getPoolSize(), equalTo(max));
        assertThat("wrong active size", pool.getActiveCount(), equalTo(max));
        barrier.await();
        terminate(pool);
    }

    public void testScaleDown() throws Exception {
        final int min = between(1, 3);
        final int max = between(min + 1, 6);
        final ThreadBarrier barrier = new ThreadBarrier(max + 1);

        final ThreadPoolExecutor pool = EsExecutors.newScaling(getTestName(), min, max, between(1, 100), TimeUnit.MILLISECONDS, EsExecutors.daemonThreadFactory("test"));
        assertThat("Min property", pool.getCorePoolSize(), equalTo(min));
        assertThat("Max property", pool.getMaximumPoolSize(), equalTo(max));

        for (int i = 0; i < max; ++i) {
            final CountDownLatch latch = new CountDownLatch(1);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    latch.countDown();
                    try {
                        barrier.await();
                        barrier.await();
                    } catch (Throwable e) {
                        barrier.reset(e);
                    }
                }
            });

            //wait until thread executes this task
            //otherwise, a task might be queued
            latch.await();
        }

        barrier.await();
        assertThat("wrong pool size", pool.getPoolSize(), equalTo(max));
        assertThat("wrong active size", pool.getActiveCount(), equalTo(max));
        barrier.await();
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertThat("wrong active count", pool.getActiveCount(), equalTo(0));
                assertThat("idle threads didn't shrink below max. (" + pool.getPoolSize() + ")", pool.getPoolSize(), lessThan(max));
            }
        });
        terminate(pool);
    }

    public void testRejectionMessageAndShuttingDownFlag() throws InterruptedException {
        int pool = between(1, 10);
        int queue = between(0, 100);
        int actions = queue + pool;
        final CountDownLatch latch = new CountDownLatch(1);
        EsThreadPoolExecutor executor = EsExecutors.newFixed(getTestName(), pool, queue, EsExecutors.daemonThreadFactory("dummy"));
        try {
            for (int i = 0; i < actions; i++) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Doesn't matter is going to be rejected
                    }

                    @Override
                    public String toString() {
                        return "dummy runnable";
                    }
                });
                fail("Didn't get a rejection when we expected one.");
            } catch (EsRejectedExecutionException e) {
                assertFalse("Thread pool registering as terminated when it isn't", e.isExecutorShutdown());
                String message = ExceptionsHelper.detailedMessage(e);
                assertThat(message, containsString("of dummy runnable"));
                assertThat(message, containsString("on EsThreadPoolExecutor[testRejectionMessage"));
                assertThat(message, containsString("queue capacity = " + queue));
                assertThat(message, containsString("[Running"));
                assertThat(message, containsString("active threads = " + pool));
                assertThat(message, containsString("queued tasks = " + queue));
                assertThat(message, containsString("completed tasks = 0"));
            }
        } finally {
            latch.countDown();
            terminate(executor);
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    // Doesn't matter is going to be rejected
                }

                @Override
                public String toString() {
                    return "dummy runnable";
                }
            });
            fail("Didn't get a rejection when we expected one.");
        } catch (EsRejectedExecutionException e) {
            assertTrue("Thread pool not registering as terminated when it is", e.isExecutorShutdown());
            String message = ExceptionsHelper.detailedMessage(e);
            assertThat(message, containsString("of dummy runnable"));
            assertThat(message, containsString("on EsThreadPoolExecutor[" + getTestName()));
            assertThat(message, containsString("queue capacity = " + queue));
            assertThat(message, containsString("[Terminated"));
            assertThat(message, containsString("active threads = 0"));
            assertThat(message, containsString("queued tasks = 0"));
            assertThat(message, containsString("completed tasks = " + actions));
        }
    }
}
