/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.lastaflute.job;

import java.util.ArrayList;
import java.util.List;

import org.dbflute.util.DfReflectionUtil;
import org.lastaflute.core.util.ContainerUtil;
import org.lastaflute.di.core.smart.hot.HotdeployUtil;
import org.lastaflute.di.naming.NamingConvention;
import org.lastaflute.job.cron4j.Cron4jCron;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.sauronsoftware.cron4j.Scheduler;

/**
 * @author jflute
 * @since 0.2.0 (2016/01/09 Saturday)
 */
public class LastaJobStarter {

    // ===================================================================================
    //                                                                          Definition
    //                                                                          ==========
    private static final Logger logger = LoggerFactory.getLogger(LastaJobStarter.class);

    // ===================================================================================
    //                                                                               Start
    //                                                                               =====
    public void start() {
        final boolean needsHot = !HotdeployUtil.isAlreadyHotdeploy(); // just in case
        if (needsHot) {
            HotdeployUtil.start();
        }
        final Scheduler cron4jScheduler;
        try {
            final LaJobScheduler scheduler = findScheduler();
            inject(scheduler);
            final LaJobRunner jobRunner = scheduler.createRunner();
            inject(jobRunner);
            cron4jScheduler = createCron4jScheduler(jobRunner);
            scheduler.schedule(createCron(cron4jScheduler, jobRunner));
            showBoot(scheduler, jobRunner, cron4jScheduler);
        } finally {
            if (needsHot) {
                HotdeployUtil.stop();
            }
        }
        startCron(cron4jScheduler);
    }

    protected void showBoot(LaJobScheduler scheduler, LaJobRunner jobRunner, Scheduler cron4jScheduler) {
        logger.info("[Job Scheduling]");
        logger.info(" scheduler: {}", scheduler);
        logger.info(" jobRunner: {}", jobRunner);
        logger.info(" cron4j: {}", cron4jScheduler);
    }

    // -----------------------------------------------------
    //                                        Find Scheduler
    //                                        --------------
    protected LaJobScheduler findScheduler() {
        final List<LaJobScheduler> schedulerList = new ArrayList<LaJobScheduler>(); // to check not found
        final List<String> derivedNameList = new ArrayList<String>(); // for exception message
        final NamingConvention convention = getNamingConvention();
        for (String root : convention.getRootPackageNames()) {
            final String schedulerName = buildSchedulerName(root);
            derivedNameList.add(schedulerName);
            final Class<?> schedulerType;
            try {
                schedulerType = forSchedulerName(schedulerName);
            } catch (ClassNotFoundException ignored) {
                continue;
            }
            final LaJobScheduler scheduler = createScheduler(schedulerType);
            schedulerList.add(scheduler);
        }
        if (schedulerList.isEmpty()) {
            throw new IllegalStateException("Not found the scheduler object: " + derivedNameList);
        } else if (schedulerList.size() >= 2) {
            throw new IllegalStateException("Duplicate scheduler object: " + schedulerList);
        }
        return schedulerList.get(0);
    }

    // -----------------------------------------------------
    //                                      Cron4j Scheduler
    //                                      ----------------
    protected Scheduler createCron4jScheduler(LaJobRunner jobRunner) {
        return new Scheduler();
    }

    protected LaCron createCron(Scheduler scheduler, LaJobRunner runner) {
        return new Cron4jCron(scheduler, runner);
    }

    // -----------------------------------------------------
    //                                            Scheduling
    //                                            ----------
    protected String buildSchedulerName(String root) {
        return root + "." + getSchedulerPackage() + "." + getSchedulerPureName();
    }

    protected String getSchedulerPackage() {
        return "job";
    }

    protected String getSchedulerPureName() {
        return "AllJobScheduler";
    }

    protected Class<?> forSchedulerName(String schedulingName) throws ClassNotFoundException {
        return Class.forName(schedulingName, false, Thread.currentThread().getContextClassLoader());
    }

    protected LaJobScheduler createScheduler(Class<?> schedulingType) {
        final Object schedulerObj = DfReflectionUtil.newInstance(schedulingType);
        if (!(schedulerObj instanceof LaJobScheduler)) {
            throw new IllegalStateException("Your scheduler should implement LaScheduler: " + schedulerObj);
        }
        return (LaJobScheduler) schedulerObj;
    }

    // -----------------------------------------------------
    //                                            Start Cron
    //                                            ----------
    protected void startCron(Scheduler cron4jScheduler) {
        new Thread(() -> { // use plain thread for silent asynchronous
            try {
                Thread.sleep(3000L); // delay to wait for finishing application boot
            } catch (InterruptedException ignored) {}
            try {
                cron4jScheduler.start();
            } catch (Throwable cause) {
                logger.error("Failed to start job scheduling: " + cron4jScheduler, cause);
            }
        }).start();
    }

    // ===================================================================================
    //                                                                           Component
    //                                                                           =========
    protected NamingConvention getNamingConvention() {
        return ContainerUtil.getComponent(NamingConvention.class);
    }

    protected void inject(Object target) {
        ContainerUtil.injectSimply(target);
    }
}
