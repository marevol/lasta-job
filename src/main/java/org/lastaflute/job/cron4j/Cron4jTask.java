/*
 * Copyright 2015-2017 the original author or authors.
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
package org.lastaflute.job.cron4j;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.dbflute.optional.OptionalThing;
import org.dbflute.util.DfTypeUtil;
import org.lastaflute.job.LaJob;
import org.lastaflute.job.LaJobRunner;
import org.lastaflute.job.exception.JobConcurrentlyExecutingException;
import org.lastaflute.job.key.LaJobKey;
import org.lastaflute.job.key.LaJobNote;
import org.lastaflute.job.key.LaJobUnique;
import org.lastaflute.job.log.JobErrorLog;
import org.lastaflute.job.log.JobErrorStackTracer;
import org.lastaflute.job.log.JobHistoryResource;
import org.lastaflute.job.log.JobNoticeLogLevel;
import org.lastaflute.job.subsidiary.ConcurrentJobStopper;
import org.lastaflute.job.subsidiary.CrossVMState;
import org.lastaflute.job.subsidiary.EndTitleRoll;
import org.lastaflute.job.subsidiary.ExecResultType;
import org.lastaflute.job.subsidiary.JobConcurrentExec;
import org.lastaflute.job.subsidiary.JobIdentityAttr;
import org.lastaflute.job.subsidiary.NeighborConcurrentGroup;
import org.lastaflute.job.subsidiary.NeighborConcurrentJobStopper;
import org.lastaflute.job.subsidiary.RunnerResult;
import org.lastaflute.job.subsidiary.TaskRunningState;
import org.lastaflute.job.subsidiary.VaryingCron;
import org.lastaflute.job.subsidiary.VaryingCronOption;

import it.sauronsoftware.cron4j.Task;
import it.sauronsoftware.cron4j.TaskExecutionContext;
import it.sauronsoftware.cron4j.TaskExecutor;

/**
 * @author jflute
 * @since 0.2.0 (2016/01/11 Monday)
 */
public class Cron4jTask extends Task { // unique per job in lasta job world

    // ===================================================================================
    //                                                                           Attribute
    //                                                                           =========
    protected static final String LF = "\n";

    // ===================================================================================
    //                                                                           Attribute
    //                                                                           =========
    protected VaryingCron varyingCron; // not null, can be switched
    protected final Class<? extends LaJob> jobType;
    protected final JobConcurrentExec concurrentExec;
    protected final Supplier<String> threadNaming;
    protected final LaJobRunner jobRunner; // singleton
    protected final Cron4jNow cron4jNow;
    protected final Supplier<LocalDateTime> currentTime;
    protected final TaskRunningState runningState;
    protected final Object preparingLock = new Object();
    protected final Object runningLock = new Object();
    protected final Object varyingLock = new Object();

    // ===================================================================================
    //                                                                         Constructor
    //                                                                         ===========
    public Cron4jTask(VaryingCron varyingCron, Class<? extends LaJob> jobType, JobConcurrentExec concurrentExec,
            Supplier<String> threadNaming, LaJobRunner jobRunner, Cron4jNow cron4jNow, Supplier<LocalDateTime> currentTime) {
        this.varyingCron = varyingCron;
        this.jobType = jobType;
        this.concurrentExec = concurrentExec;
        this.threadNaming = threadNaming;
        this.jobRunner = jobRunner;
        this.cron4jNow = cron4jNow;
        this.currentTime = currentTime;
        this.runningState = new TaskRunningState(currentTime);
    }

    // ===================================================================================
    //                                                                  Execute - Top Flow
    //                                                                  ==================
    @Override
    public void execute(TaskExecutionContext context) { // e.g. error handling
        try {
            final LocalDateTime activationTime = currentTime.get();
            final Cron4jJob job = findJob();
            final Thread jobThread = Thread.currentThread();
            RunnerResult runnerResult = null;
            Throwable controllerCause = null;
            try {
                runnerResult = doExecute(job, context); // not null
                if (canTriggerNext(job, runnerResult)) {
                    job.triggerNext(); // should be after current job ending
                }
            } catch (JobConcurrentlyExecutingException e) {
                error("Cannot execute the job task by concurrent execution: " + varyingCron + ", " + jobType.getSimpleName(), e);
                controllerCause = e;
            } catch (Throwable cause) { // from framework part (exception in appilcation job are already handled)
                error("Failed to execute the job task: " + varyingCron + ", " + jobType.getSimpleName(), cause);
                controllerCause = cause;
            }
            final OptionalThing<LocalDateTime> endTime = deriveEndTime(runnerResult);
            recordJobHistory(context, job, jobThread, activationTime, runnerResult, endTime, controllerCause);
        } catch (Throwable coreCause) { // controller dead
            error("Failed to control the job task: " + varyingCron + ", " + jobType.getSimpleName(), coreCause);
        }
    }

    protected Cron4jJob findJob() {
        return cron4jNow.findJobByTask(this).get();
    }

    protected OptionalThing<LocalDateTime> deriveEndTime(RunnerResult runnerResult) {
        return OptionalThing.ofNullable(runnerResult.getBeginTime().isPresent() ? currentTime.get() : null, () -> {
            throw new IllegalStateException("Not found the end-time: " + jobType);
        });
    }

    // ===================================================================================
    //                                                        Execute - Concurrent Control
    //                                                        ============================
    protected RunnerResult doExecute(Cron4jJob job, TaskExecutionContext context) { // e.g. concurrent control, cross vm
        // ...may be hard to read, synchronized hell
        final String cronExp;
        final VaryingCronOption cronOption;
        synchronized (varyingLock) {
            cronExp = varyingCron.getCronExp();
            cronOption = varyingCron.getCronOption();
        }
        final Map<String, NeighborConcurrentGroup> neighborConcurrentGroupMap = job.getNeighborConcurrentGroupMap();
        final Collection<NeighborConcurrentGroup> groupList = neighborConcurrentGroupMap.values();
        synchronized (preparingLock) { // to avoid duplicate concurrent check, waiting for previous ready 
            final OptionalThing<RunnerResult> concurrentResult = stopConcurrentJobIfNeeds(job);
            if (concurrentResult.isPresent()) {
                return concurrentResult.get();
            }
            final OptionalThing<RunnerResult> neighborConcurrentResult = synchronizedNeighborPreparing(groupList.iterator(), () -> {
                final OptionalThing<RunnerResult> result = stopNeighborConcurrentJobIfNeeds(job, neighborConcurrentGroupMap);
                if (!result.isPresent()) { // no neighbor concurrent
                    synchronized (runningLock) { // allowed job wait here to get running state
                        synchronized (runningState) { // to protect running state, begin() and end()
                            runningState.begin(); // needs to get it in preparing lock, to suppress duplicate begin()
                        }
                    }
                }
                return result;
            });
            if (neighborConcurrentResult.isPresent()) {
                return neighborConcurrentResult.get();
            }
        }
        synchronized (runningLock) { // to avoid duplicate execution, waiting for previous ending
            try {
                return synchronizedNeighborRunning(groupList.iterator(), () -> {
                    final OptionalThing<CrossVMState> crossVMState = jobRunner.getCrossVMHook().map(hook -> {
                        return hook.hookBeginning(job, runningState.getBeginTime().get()); // already begun here
                    });
                    final RunnerResult runnerResult;
                    final LocalDateTime endTime;
                    try {
                        runnerResult = actuallyExecute(job, cronExp, cronOption, context);
                    } finally {
                        endTime = currentTime.get();
                        if (crossVMState.isPresent()) { // hook exists
                            jobRunner.getCrossVMHook().alwaysPresent(hook -> { // so always present
                                hook.hookEnding(job, crossVMState.get(), endTime);
                            });
                        }
                    }
                    runnerResult.acceptEndTime(endTime); // lazy load now
                    return runnerResult;
                });
            } finally {
                synchronized (runningState) {
                    runningState.end();
                }
            }
        }
    }

    // -----------------------------------------------------
    //                                   (Myself) Concurrent
    //                                   -------------------
    protected OptionalThing<RunnerResult> stopConcurrentJobIfNeeds(Cron4jJob job) { // in preparing lock
        synchronized (runningState) {
            if (isRunningNow()) {
                final OptionalThing<RunnerResult> concurrentResult = createConcurrentJobStopper().stopIfNeeds(job, () -> {
                    return runningState.getBeginTime().get().toString(); // locked so can get safely
                });
                if (concurrentResult.isPresent()) {
                    return concurrentResult;
                }
                // will wait for previous job by synchronization later
            }
        }
        return OptionalThing.empty();
    }

    protected ConcurrentJobStopper createConcurrentJobStopper() {
        return new ConcurrentJobStopper();
    }

    // -----------------------------------------------------
    //                                   Neighbor Concurrent
    //                                   -------------------
    protected OptionalThing<RunnerResult> synchronizedNeighborPreparing(Iterator<NeighborConcurrentGroup> groupIte,
            Supplier<OptionalThing<RunnerResult>> runner) { // in preparing lock
        if (groupIte.hasNext()) {
            final NeighborConcurrentGroup group = groupIte.next();
            synchronized (group.getGroupPreparingLock()) {
                return synchronizedNeighborPreparing(groupIte, runner);
            }
        } else {
            return runner.get();
        }
    }

    protected RunnerResult synchronizedNeighborRunning(Iterator<NeighborConcurrentGroup> groupIte, Supplier<RunnerResult> runner) { // in running lock
        if (groupIte.hasNext()) {
            final NeighborConcurrentGroup group = groupIte.next();
            synchronized (group.getGroupRunningLock()) {
                return synchronizedNeighborRunning(groupIte, runner);
            }
        } else {
            return runner.get();
        }
    }

    protected OptionalThing<RunnerResult> stopNeighborConcurrentJobIfNeeds(Cron4jJob job,
            Map<String, NeighborConcurrentGroup> neighborConcurrentGroupMap) { // in neighbor preparing lock
        return createNeighborConcurrentJobStopper(neighborConcurrentGroupMap).stopIfNeeds(job, jobState -> {
            return jobState.mapExecutingNow(execState -> execState.getBeginTime().toString()).orElseGet(() -> {
                return "*the job have just ended now"; // may be ended while message building
            });
        });
        // will wait for previous job by synchronization later
    }

    protected NeighborConcurrentJobStopper createNeighborConcurrentJobStopper(
            Map<String, NeighborConcurrentGroup> neighborConcurrentGroupMap) {
        return new NeighborConcurrentJobStopper(jobKey -> cron4jNow.findJobByKey(jobKey), neighborConcurrentGroupMap);
    }

    // ===================================================================================
    //                                                          Execute - Really Executing
    //                                                          ==========================
    // in execution lock, cannot use varingCron here
    protected RunnerResult actuallyExecute(JobIdentityAttr identityProvider, String cronExp, VaryingCronOption cronOption,
            TaskExecutionContext context) { // in synchronized world
        adjustThreadNameIfNeeds(cronOption);
        return runJob(identityProvider, cronExp, cronOption, context);
    }

    protected void adjustThreadNameIfNeeds(VaryingCronOption cronOption) { // because of too long name of cron4j
        final String supplied = threadNaming.get();
        final Thread currentThread = Thread.currentThread();
        if (currentThread.getName().equals(supplied)) { // already adjusted
            return;
        }
        currentThread.setName(supplied);
    }

    protected RunnerResult runJob(JobIdentityAttr identityProvider, String cronExp, VaryingCronOption cronOption,
            TaskExecutionContext cron4jContext) {
        final LocalDateTime beginTime = runningState.getBeginTime().get(); // already begun here
        return jobRunner.run(jobType, () -> {
            return createCron4jRuntime(identityProvider, cronExp, cronOption, beginTime, cron4jContext);
        }).acceptEndTime(currentTime.get());
    }

    protected Cron4jRuntime createCron4jRuntime(JobIdentityAttr identityProvider, String cronExp, VaryingCronOption cronOption,
            LocalDateTime beginTime, TaskExecutionContext cron4jContext) {
        final LaJobKey jobKey = identityProvider.getJobKey();
        final OptionalThing<LaJobNote> jobNote = identityProvider.getJobNote();
        final OptionalThing<LaJobUnique> jobUnique = identityProvider.getJobUnique();
        final Map<String, Object> parameterMap = extractParameterMap(cronOption);
        final JobNoticeLogLevel noticeLogLevel = cronOption.getNoticeLogLevel();
        return new Cron4jRuntime(jobKey, jobNote, jobUnique, cronExp, jobType, parameterMap, noticeLogLevel, beginTime, cron4jContext);
    }

    protected Map<String, Object> extractParameterMap(VaryingCronOption cronOption) {
        return cronOption.getParamsSupplier().map(supplier -> supplier.supply()).orElse(Collections.emptyMap());
    }

    // ===================================================================================
    //                                                                  Execute - Suppoter
    //                                                                  ==================
    // -----------------------------------------------------
    //                                          Next Trigger
    //                                          ------------
    protected boolean canTriggerNext(Cron4jJob job, RunnerResult runnerResult) {
        return !runnerResult.getCause().isPresent() && !runnerResult.isNextTriggerSuppressed();
    }

    // -----------------------------------------------------
    //                                           Job History
    //                                           -----------
    protected void recordJobHistory(TaskExecutionContext context, Cron4jJob job, Thread jobThread, LocalDateTime activationTime,
            RunnerResult runnerResult, OptionalThing<LocalDateTime> endTime, Throwable controllerCause) {
        final TaskExecutor taskExecutor = context.getTaskExecutor();
        final Cron4jJobHistory jobHistory = prepareJobHistory(job, activationTime, runnerResult, endTime, controllerCause);
        final int historyLimit = getHistoryLimit();
        jobRunner.getHistoryHook().ifPresent(hook -> {
            hook.hookRecord(jobHistory, new JobHistoryResource(historyLimit));
        });
        Cron4jJobHistory.record(taskExecutor, jobHistory, historyLimit);
    }

    protected Cron4jJobHistory prepareJobHistory(Cron4jJob job, LocalDateTime activationTime, RunnerResult runnerResult,
            OptionalThing<LocalDateTime> endTime, Throwable controllerCause) {
        final OptionalThing<LocalDateTime> beginTime = runnerResult.getBeginTime();
        final Cron4jJobHistory jobHistory;
        if (controllerCause == null) { // mainly here, and runnerResult is not null here
            jobHistory = createJobHistory(job, activationTime, beginTime, endTime, () -> {
                return deriveRunnerExecResultType(runnerResult);
            }, runnerResult.getEndTitleRoll(), runnerResult.getCause());
        } else if (controllerCause instanceof JobConcurrentlyExecutingException) {
            jobHistory = createJobHistory(job, activationTime, beginTime, endTime, () -> ExecResultType.ERROR_BY_CONCURRENT,
                    OptionalThing.empty(), OptionalThing.of(controllerCause));
        } else { // may be framework exception
            jobHistory = createJobHistory(job, activationTime, beginTime, endTime, () -> ExecResultType.CAUSED_BY_FRAMEWORK,
                    OptionalThing.empty(), OptionalThing.of(controllerCause));
        }
        return jobHistory;
    }

    protected ExecResultType deriveRunnerExecResultType(RunnerResult runnerResult) {
        if (runnerResult.getCause().isPresent()) {
            return ExecResultType.CAUSED_BY_APPLICATION;
        } else if (runnerResult.isQuitByConcurrent()) {
            return ExecResultType.QUIT_BY_CONCURRENT;
        } else {
            return ExecResultType.SUCCESS;
        }
    }

    protected Cron4jJobHistory createJobHistory(Cron4jJob job, LocalDateTime activationTime, OptionalThing<LocalDateTime> beginTime,
            OptionalThing<LocalDateTime> endTime, Supplier<ExecResultType> execResultTypeProvider, OptionalThing<EndTitleRoll> endTitleRoll,
            OptionalThing<Throwable> cause) {
        final LaJobKey jobKey = job.getJobKey();
        final OptionalThing<LaJobNote> jobNote = job.getJobNote();
        final OptionalThing<LaJobUnique> jobUnique = job.getJobUnique();
        final OptionalThing<String> cronExp = job.getCronExp();
        final String jobTypeFqcn = job.getJobType().getName();
        final ExecResultType execResultType = execResultTypeProvider.get();
        return new Cron4jJobHistory(jobKey, jobNote, jobUnique // identity
                , cronExp, jobTypeFqcn // cron
                , activationTime, beginTime, endTime // execution time
                , execResultType // execution result
                , endTitleRoll, cause);
    }

    protected int getHistoryLimit() {
        return 300;
    }

    // -----------------------------------------------------
    //                                             Error Log
    //                                             ---------
    protected void error(String msg, Throwable cause) {
        final String unifiedMsg = msg + LF + new JobErrorStackTracer().buildExceptionStackTrace(cause);
        jobRunner.getErrorLogHook().ifPresent(hook -> {
            hook.hookError(unifiedMsg);
        });
        JobErrorLog.log(unifiedMsg);
    }

    // ===================================================================================
    //                                                                              Switch
    //                                                                              ======
    public void becomeNonCrom() {
        synchronized (varyingLock) {
            this.varyingCron = createVaryingCron(Cron4jCron.NON_CRON, varyingCron.getCronOption());
        }
    }

    public void switchCron(String cronExp, VaryingCronOption cronOption) {
        synchronized (varyingLock) {
            this.varyingCron = createVaryingCron(cronExp, cronOption);
        }
    }

    protected VaryingCron createVaryingCron(String cronExp, VaryingCronOption cronOption) {
        return new VaryingCron(cronExp, cronOption);
    }

    // ===================================================================================
    //                                                                       Determination
    //                                                                       =============
    @Override
    public boolean canBeStopped() {
        return true; // fixedly
    }

    public boolean isNonCron() {
        return Cron4jCron.isNonCronExp(varyingCron.getCronExp());
    }

    // ===================================================================================
    //                                                                             Running
    //                                                                             =======
    public <RESULT> OptionalThing<RESULT> syncRunningCall(Function<TaskRunningState, RESULT> oneArgLambda) {
        synchronized (runningState) {
            if (runningState.getBeginTime().isPresent()) {
                return OptionalThing.ofNullable(oneArgLambda.apply(runningState), () -> {
                    throw new IllegalStateException("Not found the result from your scope: " + jobType);
                });
            } else {
                return OptionalThing.ofNullable(null, () -> {
                    throw new IllegalStateException("Not running now: " + jobType);
                });
            }
        }
    }

    // ===================================================================================
    //                                                                      Basic Override
    //                                                                      ==============
    @Override
    public String toString() {
        final String title = DfTypeUtil.toClassTitle(this);
        final String cronExpExp;
        final VaryingCronOption cronOption;
        synchronized (varyingLock) {
            cronExpExp = isNonCron() ? "non-cron" : varyingCron.getCronExp();
            cronOption = varyingCron.getCronOption();
        }
        return title + ":{" + cronExpExp + ", " + jobType.getName() + ", " + concurrentExec + ", " + cronOption + "}";
    }

    // ===================================================================================
    //                                                                            Accessor
    //                                                                            ========
    public VaryingCron getVaryingCron() {
        return varyingCron;
    }

    public Class<? extends LaJob> getJobType() {
        return jobType;
    }

    public JobConcurrentExec getConcurrentExec() {
        return concurrentExec;
    }

    public Object getPreparingLock() {
        return preparingLock;
    }

    public Object getRunningLock() {
        return runningLock;
    }

    public Object getVaryingLock() {
        return varyingLock;
    }

    public boolean isRunningNow() {
        synchronized (runningState) {
            return runningState.getBeginTime().isPresent();
        }
    }

    public TaskRunningState getRunningState() {
        return runningState;
    }
}
