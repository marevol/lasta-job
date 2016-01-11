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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Resource;

import org.dbflute.bhv.proposal.callback.ExecutedSqlCounter;
import org.dbflute.bhv.proposal.callback.TraceableSqlAdditionalInfoProvider;
import org.dbflute.hook.AccessContext;
import org.dbflute.hook.CallbackContext;
import org.dbflute.hook.SqlFireHook;
import org.dbflute.hook.SqlResultHandler;
import org.dbflute.hook.SqlStringFilter;
import org.dbflute.optional.OptionalThing;
import org.dbflute.util.DfTraceViewUtil;
import org.dbflute.util.DfTypeUtil;
import org.dbflute.util.Srl;
import org.lastaflute.core.exception.ExceptionTranslator;
import org.lastaflute.core.magic.ThreadCacheContext;
import org.lastaflute.core.mail.PostedMailCounter;
import org.lastaflute.core.util.ContainerUtil;
import org.lastaflute.db.dbflute.accesscontext.AccessContextArranger;
import org.lastaflute.db.dbflute.accesscontext.AccessContextResource;
import org.lastaflute.db.dbflute.accesscontext.PreparedAccessContext;
import org.lastaflute.db.dbflute.callbackcontext.RomanticTraceableSqlFireHook;
import org.lastaflute.db.dbflute.callbackcontext.RomanticTraceableSqlResultHandler;
import org.lastaflute.db.dbflute.callbackcontext.RomanticTraceableSqlStringFilter;
import org.lastaflute.db.jta.romanticist.SavedTransactionMemories;
import org.lastaflute.db.jta.romanticist.TransactionMemoriesProvider;
import org.lastaflute.di.core.smart.hot.HotdeployLock;
import org.lastaflute.di.core.smart.hot.HotdeployUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jflute
 * @since 0.2.0 (2016/01/10 Sunday)
 */
public class LaJobRunner {

    // ===================================================================================
    //                                                                          Definition
    //                                                                          ==========
    private static final Logger logger = LoggerFactory.getLogger(LaJobRunner.class);
    protected static final String LF = "\n";
    protected static final String EX_IND = "  "; // indent for exception message

    // ===================================================================================
    //                                                                           Attribute
    //                                                                           =========
    protected AccessContextArranger accessContextArranger; // null allowed, option

    @Resource
    private ExceptionTranslator exceptionTranslator;

    // ===================================================================================
    //                                                                         Constructor
    //                                                                         ===========
    public LaJobRunner useAccessContext(AccessContextArranger oneArgLambda) {
        if (oneArgLambda == null) {
            throw new IllegalArgumentException("The argument 'oneArgLambda' (accessContextArranger) should not be null.");
        }
        this.accessContextArranger = oneArgLambda;
        return this;
    }

    // ===================================================================================
    //                                                                                Run
    //                                                                               =====
    public void run(Class<? extends LaJob> jobType, Supplier<LaJobRuntime> runtimeSupplier) {
        if (!HotdeployUtil.isHotdeploy()) { // e.g. production, unit-test
            doRun(jobType, runtimeSupplier);
        }
        synchronized (HotdeployLock.class) { // #thiking: cannot hotdeploy, why?
            HotdeployUtil.start();
            try {
                doRun(jobType, runtimeSupplier);
            } finally {
                HotdeployUtil.stop();
            }
        }
    }

    protected void doRun(Class<? extends LaJob> jobType, Supplier<LaJobRuntime> runtimeSupplier) {
        // simplar to async manager's process
        final LaJobRuntime runtime = runtimeSupplier.get();
        arrangeThreadCacheContext(runtime);
        arrangePreparedAccessContext(runtime);
        arrangeCallbackContext(runtime);
        final Object variousPreparedObj = prepareVariousContext(runtime);
        final long before = showRunning(runtime);
        Throwable cause = null;
        try {
            actuallyRun(jobType, runtime);
        } catch (Throwable e) {
            handleJobException(runtime, before, e);
            cause = e;
        } finally {
            showFinishing(runtime, before, cause); // should be before clearing because of using them
            clearVariousContext(runtime, variousPreparedObj);
            clearPreparedAccessContext();
            clearCallbackContext();
            clearThreadCacheContext();
        }
    }

    protected void actuallyRun(Class<? extends LaJob> jobType, LaJobRuntime runtime) {
        final LaJob job = ContainerUtil.getComponent(jobType);
        job.run(runtime);
    }

    // ===================================================================================
    //                                                                            Show Job
    //                                                                            ========
    protected long showRunning(LaJobRuntime runtime) {
        if (logger.isInfoEnabled()) {
            logger.info("#flow #job ...Running job: {}", runtime.toCronMethodDisp());
        }
        return System.currentTimeMillis();
    }

    protected void showFinishing(LaJobRuntime runtime, long before, Throwable cause) {
        if (logger.isInfoEnabled()) {
            final long after = System.currentTimeMillis();
            final StringBuilder sb = new StringBuilder();
            sb.append("#flow #job ...Finishing job: ").append(runtime.toMethodDisp());
            sb.append(LF).append("[Job Result]");
            sb.append(LF).append(" performanceView: ").append(toPerformanceView(before, after));
            extractSqlCount().ifPresent(counter -> {
                sb.append(LF).append(" sqlCount: ").append(counter.toLineDisp());
            });
            extractMailCount().ifPresent(counter -> {
                sb.append(LF).append(" mailCount: ").append(counter.toLineDisp());
            });
            sb.append(LF).append(" runtime: ").append(runtime);
            runtime.getEndTitleRoll().ifPresent(roll -> {
                sb.append(LF).append(" endTitleRoll:");
                roll.getDataMap().forEach((key, value) -> {
                    sb.append(LF).append("   ").append(key).append(": ").append(value);
                });
            });
            if (cause != null) {
                sb.append(LF).append(" cause: ").append(cause.getClass().getSimpleName()).append(" *Read the exception message!");
            }
            sb.append(LF).append(LF); // to separate from job logging
            logger.info(sb.toString());
        }
    }

    protected String toPerformanceView(long before, long after) {
        return DfTraceViewUtil.convertToPerformanceView(after - before);
    }

    // ===================================================================================
    //                                                                        Thread Cache
    //                                                                        ============
    protected void arrangeThreadCacheContext(LaJobRuntime runtime) {
        ThreadCacheContext.initialize();
        ThreadCacheContext.registerRequestPath(runtime.getJobType().getName());
        ThreadCacheContext.registerEntryMethod(runtime.getRunMethod());
    }

    protected void clearThreadCacheContext() {
        ThreadCacheContext.clear();
    }

    // ===================================================================================
    //                                                                       AccessContext
    //                                                                       =============
    protected void arrangePreparedAccessContext(LaJobRuntime runtime) {
        if (accessContextArranger == null) {
            return;
        }
        final AccessContextResource resource = createAccessContextResource(runtime);
        final AccessContext context = accessContextArranger.arrangePreparedAccessContext(resource);
        if (context == null) {
            String msg = "Cannot return null from access context arranger: " + accessContextArranger + " runtime=" + runtime;
            throw new IllegalStateException(msg);
        }
        PreparedAccessContext.setAccessContextOnThread(context);
    }

    protected AccessContextResource createAccessContextResource(LaJobRuntime runtime) {
        return new AccessContextResource(DfTypeUtil.toClassTitle(runtime.getJobType()), runtime.getRunMethod());
    }

    protected void clearPreparedAccessContext() {
        PreparedAccessContext.clearAccessContextOnThread();
    }

    // ===================================================================================
    //                                                                     CallbackContext
    //                                                                     ===============
    protected void arrangeCallbackContext(LaJobRuntime runtime) {
        CallbackContext.setSqlFireHookOnThread(createSqlFireHook(runtime));
        CallbackContext.setSqlStringFilterOnThread(createSqlStringFilter(runtime));
        CallbackContext.setSqlResultHandlerOnThread(createSqlResultHandler());
    }

    protected SqlFireHook createSqlFireHook(LaJobRuntime runtime) {
        return newRomanticTraceableSqlFireHook();
    }

    protected RomanticTraceableSqlFireHook newRomanticTraceableSqlFireHook() {
        return new RomanticTraceableSqlFireHook();
    }

    protected SqlStringFilter createSqlStringFilter(LaJobRuntime runtime) {
        return newRomanticTraceableSqlStringFilter(runtime.getRunMethod(), () -> buildSqlMarkingAdditionalInfo());
    }

    protected String buildSqlMarkingAdditionalInfo() {
        return null; // no additional info as default
    }

    protected RomanticTraceableSqlStringFilter newRomanticTraceableSqlStringFilter(Method actionMethod,
            TraceableSqlAdditionalInfoProvider additionalInfoProvider) {
        return new RomanticTraceableSqlStringFilter(actionMethod, additionalInfoProvider);
    }

    protected SqlResultHandler createSqlResultHandler() {
        return newRomanticTraceableSqlResultHandler();
    }

    protected RomanticTraceableSqlResultHandler newRomanticTraceableSqlResultHandler() {
        return new RomanticTraceableSqlResultHandler();
    }

    protected void clearCallbackContext() {
        CallbackContext.clearSqlResultHandlerOnThread();
        CallbackContext.clearSqlStringFilterOnThread();
        CallbackContext.clearSqlFireHookOnThread();
    }

    // ===================================================================================
    //                                                                      VariousContext
    //                                                                      ==============
    protected Object prepareVariousContext(LaJobRuntime runtime) { // for extension
        return null;
    }

    protected void clearVariousContext(LaJobRuntime runtime, Object variousPreparedObj) { // for extension
    }

    // ===================================================================================
    //                                                                  Exception Handling
    //                                                                  ==================
    protected void handleJobException(LaJobRuntime runtime, long before, Throwable cause) {
        // not use second argument here, same reason as logging filter
        final Throwable handled = exceptionTranslator.filterCause(cause);
        logger.error(buildJobExceptionMessage(runtime, before, handled));
    }

    protected String buildJobExceptionMessage(LaJobRuntime runtime, long before, Throwable cause) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Failed to run the job process: #flow #job");
        sb.append(LF);
        sb.append("/= = = = = = = = = = = = = = = = = = = = = = = = = = = = = = = = = = =: ");
        sb.append(runtime.getJobType().getName());
        sb.append(LF).append(EX_IND);
        sb.append("jobRuntime=").append(runtime);
        setupExceptionMessageAccessContext(sb, runtime);
        setupExceptionMessageCallbackContext(sb, runtime);
        setupExceptionMessageVariousContext(sb, runtime, cause);
        setupExceptionMessageSqlCountIfExists(sb);
        setupExceptionMessageTransactionMemoriesIfExists(sb);
        setupExceptionMessageMailCountIfExists(sb);
        final long after = System.currentTimeMillis();
        final String performanceView = toPerformanceView(before, after);
        sb.append(LF);
        sb.append("= = = = = = = = = =/ [").append(performanceView).append("] #").append(Integer.toHexString(cause.hashCode()));
        buildExceptionStackTrace(cause, sb);
        return sb.toString().trim();
    }

    protected void setupExceptionMessageAccessContext(StringBuilder sb, LaJobRuntime runtime) {
        sb.append(LF).append(EX_IND).append("; accessContext=").append(PreparedAccessContext.getAccessContextOnThread());
    }

    protected void setupExceptionMessageCallbackContext(StringBuilder sb, LaJobRuntime runtime) {
        sb.append(LF).append(EX_IND).append("; callbackContext=").append(CallbackContext.getCallbackContextOnThread());
    }

    protected void setupExceptionMessageVariousContext(StringBuilder sb, LaJobRuntime runtime, Throwable cause) {
        final StringBuilder variousContextSb = new StringBuilder();
        buildVariousContextInJobExceptionMessage(variousContextSb, runtime, cause);
        if (variousContextSb.length() > 0) {
            sb.append(LF).append(EX_IND).append(variousContextSb.toString());
        }
    }

    protected void buildVariousContextInJobExceptionMessage(StringBuilder sb, LaJobRuntime runtime, Throwable cause) {
    }

    protected void setupExceptionMessageSqlCountIfExists(StringBuilder sb) {
        extractSqlCount().ifPresent(counter -> {
            sb.append(LF).append(EX_IND).append("; sqlCount=").append(counter.toLineDisp());
        });
    }

    protected void setupExceptionMessageTransactionMemoriesIfExists(StringBuilder sb) {
        // e.g.
        // _/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/_/
        // ; transactionMemories=wholeShow:  
        // *RomanticTransaction@2d1cd52a
        // << Transaction Current State >>
        // beginning time: 2015/12/22 12:04:40.574
        // table command: map:{PRODUCT = list:{selectCursor ; scalarSelect(LocalDate).max}}
        // << Transaction Recent Result >>
        // 1. (2015/12/22 12:04:40.740) [00m00s027ms] PRODUCT@selectCursor => Object:{}
        // 2. (2015/12/22 12:04:40.773) [00m00s015ms] PRODUCT@scalarSelect(LocalDate).max => LocalDate:{value=2013-08-02}
        // _/_/_/_/_/_/_/_/_/_/
        final SavedTransactionMemories memories = ThreadCacheContext.findTransactionMemories();
        if (memories != null) {
            final List<TransactionMemoriesProvider> providerList = memories.getOrderedProviderList();
            final StringBuilder txSb = new StringBuilder();
            for (TransactionMemoriesProvider provider : providerList) {
                provider.provide().ifPresent(result -> {
                    if (txSb.length() == 0) {
                        txSb.append(LF).append(EX_IND).append("; transactionMemories=wholeShow:");
                    }
                    txSb.append(Srl.indent(EX_IND.length(), LF + "*" + result));
                });
            }
            sb.append(txSb);
        }
    }

    protected void setupExceptionMessageMailCountIfExists(StringBuilder sb) {
        extractMailCount().ifPresent(counter -> {
            sb.append(LF).append(EX_IND).append("; mailCount=").append(counter.toLineDisp());
        });
    }

    protected void buildExceptionStackTrace(Throwable cause, StringBuilder sb) { // similar to logging filter
        sb.append(LF);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        PrintStream ps = null;
        try {
            ps = new PrintStream(out);
            cause.printStackTrace(ps);
            final String encoding = "UTF-8";
            try {
                sb.append(out.toString(encoding));
            } catch (UnsupportedEncodingException continued) {
                logger.warn("Unknown encoding: " + encoding, continued);
                sb.append(out.toString()); // retry without encoding
            }
        } finally {
            if (ps != null) {
                ps.close();
            }
        }
    }

    // ===================================================================================
    //                                                                           SQL Count
    //                                                                           =========
    protected OptionalThing<ExecutedSqlCounter> extractSqlCount() {
        final CallbackContext context = CallbackContext.getCallbackContextOnThread();
        if (context == null) {
            return OptionalThing.empty();
        }
        final SqlStringFilter filter = context.getSqlStringFilter();
        if (filter == null || !(filter instanceof ExecutedSqlCounter)) {
            return OptionalThing.empty();
        }
        return OptionalThing.of(((ExecutedSqlCounter) filter));
    }

    // ===================================================================================
    //                                                                          Mail Count
    //                                                                          ==========
    protected OptionalThing<PostedMailCounter> extractMailCount() {
        return OptionalThing.ofNullable(ThreadCacheContext.findMailCounter(), () -> {
            throw new IllegalStateException("Not found the mail count in the thread cache.");
        });
    }

    // ===================================================================================
    //                                                                      Basic Override
    //                                                                      ==============
    @Override
    public String toString() {
        return DfTypeUtil.toClassTitle(this) + ":{accessContext=" + accessContextArranger + "}@" + Integer.toHexString(hashCode());
    }
}
