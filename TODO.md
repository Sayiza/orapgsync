[x] Make datatype get all variables
[x] XML Type
[x] aq types
[x] synonym frontend
[x] synonym extraction job and state saving
[x] synonym resolving for types
[x] clear up usage of reflections
[x] sequences before tables, because default can be a seq
[x] special column names : "#", "end", "offset"
[x] Failed to serialize LOB type LONG 
[x] Unmapped defaul values: strip comments, and brackets
[x] SQL Transform: subquery support in all phases, outer join support in all phases
[x] revisit outer join transformer once all subquery support is in place
[x] The AND handler (lines 17-30) correctly handles null (filtered ROWNUM), but OR doesn't. If ROWNUM appears in an OR condition, this will cause "null OR something".
[x] PL/SQL Variable declarations visitor (Step 25 Phase 2.1)
[x] PL/SQL Assignment statements visitor (Step 25 Phase 2.1)
[x] PL/SQL IF/ELSIF/ELSE statements visitor (Step 25 Phase 2.2)
[x] PL/SQL SELECT INTO statements visitor (Step 25 Phase 2.3)
[x] function stubs need ast parsing too! or not
[x] Frontend: - vs ?, no check for fks
[x] 2 - pass architecture for preparing types before transformation
[ ] 3 jobs: type-methos, functions, triggers
[x] 1 more job for package body data - no, integrated now
[x] 1 more job for oracle build ins
[x] evaluate usage of s/get_config for package variable replacement!
[x] insert update delete migration, basics are ok
[ ] complex types to json - sync with package needs
[x] test failures - compare to plan inline
[ ] data transfer step: CLOB size 24586928 characters exceeds limit 20971520 in column response, skipping (will insert NULL)
[ ] Unknown complex type anydata for column ... meldung anderes log level
[ ] broken views: co_loc_bew.la_master_v, co_slc_xm_legacy.dp_ex_exams_v, co_slc_xm_legacy.dp_xm_candidates_v, co_loc_bwst.bwst_v_ant
[ ] co_gpr

15:44:37 INFO  [me.ch.or.fu.se.OracleFunctionExtractor] (ForkJoinPool.commonPool-worker-2) Extracted 832 public functions/procedures from Oracle schema co_gpr
15:55:09 WARN  [io.ve.co.im.BlockedThreadChecker] (vertx-blocked-thread-checker) Thread Thread[vert.x-eventloop-thread-0,5,main] has been blocked for 2449 ms, time limit is 2000 ms: io.vertx.core.VertxException: Thread blocked
at java.base/sun.nio.ch.EPoll.wait(Native Method)
at java.base/sun.nio.ch.EPollSelectorImpl.doSelect(EPollSelectorImpl.java:121)
at java.base/sun.nio.ch.SelectorImpl.lockAndDoSelect(SelectorImpl.java:130)
at java.base/sun.nio.ch.SelectorImpl.select(SelectorImpl.java:142)
at io.netty.channel.nio.SelectedSelectionKeySetSelector.select(SelectedSelectionKeySetSelector.java:62)
at io.netty.channel.nio.NioEventLoop.select(NioEventLoop.java:883)
at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:526)
at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:994)
at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
at java.base/java.lang.Thread.run(Thread.java:1583)


15:57:31 WARN  [io.ve.co.im.BlockedThreadChecker] (vertx-blocked-thread-checker) Thread Thread[vert.x-eventloop-thread-0,5,main] has been blocked for 2475 ms, time limit is 2000 ms: io.vertx.core.VertxException: Thread blocked
at org.jboss.resteasy.reactive.server.handlers.ClassRoutingHandler.handle(ClassRoutingHandler.java:158)
at io.quarkus.resteasy.reactive.server.runtime.QuarkusResteasyReactiveRequestContext.invokeHandler(QuarkusResteasyReactiveRequestContext.java:123)
at org.jboss.resteasy.reactive.common.core.AbstractResteasyReactiveContext.run(AbstractResteasyReactiveContext.java:147)
at org.jboss.resteasy.reactive.server.handlers.RestInitialHandler.beginProcessing(RestInitialHandler.java:48)
at org.jboss.resteasy.reactive.server.vertx.ResteasyReactiveVertxHandler.handle(ResteasyReactiveVertxHandler.java:23)
at org.jboss.resteasy.reactive.server.vertx.ResteasyReactiveVertxHandler.handle(ResteasyReactiveVertxHandler.java:10)
at io.vertx.ext.web.impl.RouteState.handleContext(RouteState.java:1285)
at io.vertx.ext.web.impl.RoutingContextImplBase.iterateNext(RoutingContextImplBase.java:177)
at io.vertx.ext.web.impl.RoutingContextImpl.next(RoutingContextImpl.java:140)
at io.quarkus.vertx.http.runtime.StaticResourcesRecorder$2.handle(StaticResourcesRecorder.java:102)
at io.quarkus.vertx.http.runtime.StaticResourcesRecorder$2.handle(StaticResourcesRecorder.java:88)
at io.vertx.ext.web.impl.RouteState.handleContext(RouteState.java:1285)
at io.vertx.ext.web.impl.RoutingContextImplBase.iterateNext(RoutingContextImplBase.java:140)
at io.vertx.ext.web.impl.RoutingContextImpl.next(RoutingContextImpl.java:140)
at io.vertx.ext.web.handler.impl.StaticHandlerImpl.lambda$sendStatic$1(StaticHandlerImpl.java:290)
at io.vertx.core.impl.future.FutureImpl$4.onSuccess(FutureImpl.java:176)
at io.vertx.core.impl.future.FutureBase.lambda$emitSuccess$0(FutureBase.java:60)
at io.netty.util.concurrent.AbstractEventExecutor.runTask(AbstractEventExecutor.java:173)
at io.netty.util.concurrent.AbstractEventExecutor.safeExecute(AbstractEventExecutor.java:166)
at io.netty.util.concurrent.SingleThreadEventExecutor.runAllTasks(SingleThreadEventExecutor.java:469)
at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:569)
at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:994)
at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
at java.base/java.lang.Thread.run(Thread.java:1583)


15:58:45 WARN  [io.ve.co.im.BlockedThreadChecker] (vertx-blocked-thread-checker) Thread Thread[vert.x-eventloop-thread-0,5,main] has been blocked for 2495 ms, time limit is 2000 ms: io.vertx.core.VertxException: Thread blocked
at io.vertx.core.streams.impl.InboundBuffer.lambda$asyncDrain$1(InboundBuffer.java:317)
at io.vertx.core.impl.ContextInternal.dispatch(ContextInternal.java:270)
at io.vertx.core.impl.ContextInternal.dispatch(ContextInternal.java:252)
at io.vertx.core.impl.ContextInternal.lambda$runOnContext$0(ContextInternal.java:50)
at io.netty.util.concurrent.AbstractEventExecutor.runTask(AbstractEventExecutor.java:173)
at io.netty.util.concurrent.AbstractEventExecutor.safeExecute(AbstractEventExecutor.java:166)
at io.netty.util.concurrent.SingleThreadEventExecutor.runAllTasks(SingleThreadEventExecutor.java:469)
at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:569)
at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:994)
at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
at java.base/java.lang.Thread.run(Thread.java:1583)


16:04:46 WARN  [io.ve.co.im.BlockedThreadChecker] (vertx-blocked-thread-checker) Thread Thread[vert.x-eventloop-thread-0,5,main] has been blocked for 2665 ms, time limit is 2000 ms: io.vertx.core.VertxException: Thread blocked
at java.base/sun.nio.ch.EPoll.wait(Native Method)
at java.base/sun.nio.ch.EPollSelectorImpl.doSelect(EPollSelectorImpl.java:121)
at java.base/sun.nio.ch.SelectorImpl.lockAndDoSelect(SelectorImpl.java:130)
at java.base/sun.nio.ch.SelectorImpl.select(SelectorImpl.java:142)
at io.netty.channel.nio.SelectedSelectionKeySetSelector.select(SelectedSelectionKeySetSelector.java:62)
at io.netty.channel.nio.NioEventLoop.select(NioEventLoop.java:883)
at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:526)
at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:994)
at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
at java.base/java.lang.Thread.run(Thread.java:1583)



--
16:10:08 ERROR [me.ch.or.co.jo.se.JobService] (pool-24-thread-1) Job failed: oracle-function-51c61f07-f99e-43fe-8f54-e56ce2ab8c94

Exception in JobService.java:74
72                      execution.setProgress(progress);
73                      log.debug("Job {} progress: {}%", jobId, progress.getPercentage());
-> 74                  }).get();
75  
76                  execution.setResult(result);

: java.util.concurrent.ExecutionException: java.lang.OutOfMemoryError: Java heap space
at java.base/java.util.concurrent.CompletableFuture.reportGet(CompletableFuture.java:396)
at java.base/java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2073)
at me.christianrobert.orapgsync.core.job.service.JobService.lambda$submitJob$1(JobService.java:74)
at java.base/java.util.concurrent.CompletableFuture$AsyncSupply.run(CompletableFuture.java:1768)
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: java.lang.OutOfMemoryError: Java heap space
at org.antlr.v4.runtime.atn.ParserATNSimulator.ruleTransition(ParserATNSimulator.java:1898)
at org.antlr.v4.runtime.atn.ParserATNSimulator.getEpsilonTarget(ParserATNSimulator.java:1753)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1527)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1482)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1482)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1482)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1482)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1499)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closureCheckingStopState(ParserATNSimulator.java:1482)
at org.antlr.v4.runtime.atn.ParserATNSimulator.closure_(ParserATNSimulator.java:1572)

